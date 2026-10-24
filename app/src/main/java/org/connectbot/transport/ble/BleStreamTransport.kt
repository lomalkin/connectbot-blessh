/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.transport.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportException
import timber.log.Timber
import java.util.Locale
import java.util.UUID

/**
 * sshlib [Transport] over a ble-term server's BleStream GATT service.
 *
 * One coroutine feeds received notifications through [BleStreamLink] into
 * [inbound]; another drains frames from the link into GATT writes, one write
 * in flight at a time (Android delivers onCharacteristicWrite for writes
 * without response once the stack has buffered the packet).
 */
class BleStreamTransport private constructor(
    private val client: GattClient,
    private val scope: CoroutineScope,
    maxFrame: Int,
) : Transport {
    private val link = BleStreamLink(maxFrame, RECV_WINDOW)
    private val lock = Mutex()
    private val readLock = Mutex()
    private val wakeWriter = Channel<Unit>(Channel.CONFLATED)
    private val writerIdle = Channel<Unit>(Channel.CONFLATED)
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()

    @Volatile
    private var failure: TransportException? = null

    @Volatile
    private var closed = false

    private var buffer = ByteArray(0)
    private var bufferOffset = 0

    override val isConnected: Boolean
        get() = !closed && failure == null

    private fun start() {
        scope.launch { readerLoop() }
        scope.launch { writerLoop() }
        scope.launch {
            val status = client.disconnected.await()
            fail(TransportException("Bluetooth connection lost (status $status)"))
        }
        wakeWriter.trySend(Unit)
    }

    private fun fail(e: TransportException) {
        if (failure == null) failure = e
        opened.completeExceptionally(e)
        inbound.close(e)
        writerIdle.trySend(Unit)
    }

    private suspend fun readerLoop() {
        for (pkt in client.notifications) {
            val events = try {
                lock.withLock { link.onPacket(pkt) }
            } catch (e: BleStreamLink.ProtocolException) {
                Timber.w("BLE protocol error: %s", e.message)
                lock.withLock { link.close(BleStreamLink.CLOSE_PROTOCOL_ERROR, e.message ?: "") }
                wakeWriter.trySend(Unit)
                fail(TransportException("BLE protocol error: ${e.message}"))
                return
            }
            for (ev in events) {
                when (ev) {
                    is BleStreamLink.Event.Open -> opened.complete(Unit)
                    is BleStreamLink.Event.Data -> inbound.trySend(ev.bytes)
                    is BleStreamLink.Event.Closed -> fail(TransportException(describeClose(ev.reason, ev.message)))
                }
            }
            wakeWriter.trySend(Unit)
        }
    }

    private suspend fun writerLoop() {
        try {
            while (scope.isActive) {
                val frame = lock.withLock { link.pollTransmit() }
                if (frame == null) {
                    writerIdle.trySend(Unit)
                    wakeWriter.receive()
                    continue
                }
                client.writeFrame(frame)
            }
        } catch (e: TransportException) {
            fail(e)
        }
    }

    override suspend fun read(count: Int): ByteArray = readLock.withLock {
        val result = ByteArray(count)
        var filled = 0
        while (filled < count) {
            if (bufferOffset < buffer.size) {
                val n = minOf(buffer.size - bufferOffset, count - filled)
                System.arraycopy(buffer, bufferOffset, result, filled, n)
                bufferOffset += n
                filled += n
                lock.withLock { link.consumed(n) }
                wakeWriter.trySend(Unit)
            } else {
                val chunk = inbound.receiveCatching().getOrNull()
                    ?: throw failure ?: TransportException("BLE stream closed")
                buffer = chunk
                bufferOffset = 0
            }
        }
        result
    }

    override suspend fun write(data: ByteArray) {
        while (true) {
            failure?.let { throw it }
            if (closed) throw TransportException("BLE stream closed")
            val queued = lock.withLock {
                if (link.pendingOut() < MAX_PENDING) {
                    link.send(data)
                    true
                } else {
                    false
                }
            }
            wakeWriter.trySend(Unit)
            if (queued) return
            writerIdle.receive()
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        if (failure == null) {
            lock.withLock { link.close(BleStreamLink.CLOSE_NORMAL, "client closed") }
            wakeWriter.trySend(Unit)
            withTimeoutOrNull(CLOSE_FLUSH_MS) { writerIdle.receive() }
        }
        client.close()
        fail(TransportException("BLE stream closed"))
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    internal class GattClient : BluetoothGattCallback() {
        lateinit var gatt: BluetoothGatt
        lateinit var rx: BluetoothGattCharacteristic
        private val opMutex = Mutex()

        @Volatile
        private var pending: CompletableDeferred<Int>? = null

        val connected = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Int>()
        val notifications = Channel<ByteArray>(Channel.UNLIMITED)

        @Volatile
        var mtu = DEFAULT_MTU

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Timber.d("BLE connection state %d status %d", newState, status)
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val e = TransportException("Bluetooth connection failed (status $status)")
                connected.completeExceptionally(e)
                disconnected.complete(status)
                pending?.completeExceptionally(e)
                notifications.close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            pending?.complete(status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == BleStreamLink.TX_UUID) notifications.trySend(value.copyOf())
        }

        @Deprecated("Used before API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == BleStreamLink.TX_UUID) {
                characteristic.value?.let { notifications.trySend(it.copyOf()) }
            }
        }

        /** Runs one GATT operation; returns null if the stack refused to start it. */
        private suspend fun op(name: String, timeoutMs: Long = OP_TIMEOUT_MS, start: () -> Boolean): Int? = opMutex.withLock {
            if (disconnected.isCompleted) throw TransportException("Bluetooth connection lost")
            val result = CompletableDeferred<Int>()
            pending = result
            try {
                if (!start()) return@withLock null
                withTimeout(timeoutMs) { result.await() }
            } catch (_: TimeoutCancellationException) {
                throw TransportException("Bluetooth $name timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                throw e
            } catch (e: Exception) {
                throw TransportException("Bluetooth $name failed: ${e.message}", e)
            } finally {
                pending = null
            }
        }

        private suspend fun opRetrying(name: String, start: () -> Boolean): Int {
            repeat(START_RETRIES) {
                op(name, start = start)?.let { return it }
                delay(START_RETRY_DELAY_MS)
            }
            throw TransportException("Bluetooth $name could not be started")
        }

        suspend fun requestMtu(): Int {
            opRetrying("MTU request") { gatt.requestMtu(REQUESTED_MTU) }
            return mtu
        }

        suspend fun discover() {
            val status = opRetrying("service discovery") { gatt.discoverServices() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw TransportException("Service discovery failed (status $status)")
        }

        @Suppress("DEPRECATION")
        suspend fun enableNotifications(tx: BluetoothGattCharacteristic) {
            if (!gatt.setCharacteristicNotification(tx, true)) throw TransportException("Cannot enable notifications")
            val cccd = tx.getDescriptor(CCCD_UUID) ?: throw TransportException("Server has no notification descriptor")
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val status = opRetrying("subscription") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    cccd.value = value
                    gatt.writeDescriptor(cccd)
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) throw TransportException("Subscribing failed (status $status)")
        }

        @Suppress("DEPRECATION")
        suspend fun writeFrame(frame: ByteArray) {
            val status = opRetrying("write") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(rx, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
                } else {
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    rx.value = frame
                    gatt.writeCharacteristic(rx)
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) throw TransportException("Bluetooth write failed (status $status)")
        }

        fun close() {
            if (!this::gatt.isInitialized) return
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Timber.d(e, "Error closing GATT")
            }
            disconnected.complete(-1)
            notifications.close()
        }
    }

    companion object {
        private const val RECV_WINDOW = 32 * 1024
        private const val MAX_PENDING = 32 * 1024
        private const val REQUESTED_MTU = 517
        private const val DEFAULT_MTU = 23
        private const val OP_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val CLOSE_FLUSH_MS = 1_000L
        private const val START_RETRIES = 100
        private const val START_RETRY_DELAY_MS = 10L
        private const val CONNECT_ATTEMPTS = 3
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun normalizeAddress(address: String): String? {
            val hex = address.trim().replace(":", "").replace("-", "").uppercase(Locale.US)
            if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
            return hex.chunked(2).joinToString(":")
        }

        private fun describeClose(reason: Int, message: String): String {
            val what = when (reason) {
                BleStreamLink.CLOSE_NORMAL -> "Server closed the session"
                BleStreamLink.CLOSE_BUSY -> "Server is busy with another client"
                BleStreamLink.CLOSE_PROTOCOL_ERROR -> "Server reported a protocol error"
                BleStreamLink.CLOSE_SESSION_UNAVAILABLE -> "Server could not start an SSH session"
                BleStreamLink.CLOSE_TIMEOUT -> "Server timed out"
                else -> "Server closed the link"
            }
            return if (message.isEmpty()) what else "$what: $message"
        }

        /**
         * Connects to the ble-term server at [address] and completes the
         * BleStream handshake. [status] receives progress lines for the terminal.
         */
        @SuppressLint("MissingPermission")
        suspend fun connect(context: Context, address: String, dispatcher: CoroutineDispatcher, status: (String) -> Unit): BleStreamTransport {
            if (!hasPermission(context)) {
                throw TransportException("Nearby devices permission is required for Bluetooth LE hosts")
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw TransportException("Bluetooth is not available on this device")
            if (!adapter.isEnabled) throw TransportException("Bluetooth is turned off")
            val mac = normalizeAddress(address)
                ?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
                ?: throw TransportException("Invalid Bluetooth address: $address")
            val device = adapter.getRemoteDevice(mac)

            var lastError: TransportException? = null
            for (attempt in 1..CONNECT_ATTEMPTS) {
                status("Connecting to $mac over Bluetooth LE" + if (attempt > 1) " (attempt $attempt)" else "")
                val client = GattClient()
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                try {
                    client.gatt = device.connectGatt(context, false, client, BluetoothDevice.TRANSPORT_LE)
                        ?: throw TransportException("Cannot create GATT connection")
                    withTimeout(CONNECT_TIMEOUT_MS) { client.connected.await() }
                    val mtu = client.requestMtu()
                    client.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    client.discover()
                    val service = client.gatt.getService(BleStreamLink.SERVICE_UUID)
                        ?: throw TransportException("$mac is not a ble-term server")
                    val rx = service.getCharacteristic(BleStreamLink.RX_UUID)
                    val tx = service.getCharacteristic(BleStreamLink.TX_UUID)
                    if (rx == null || tx == null) throw TransportException("$mac has an incomplete ble-term service")
                    client.rx = rx
                    client.enableNotifications(tx)
                    val transport = BleStreamTransport(client, scope, mtu - 3)
                    transport.start()
                    withTimeout(HANDSHAKE_TIMEOUT_MS) { transport.opened.await() }
                    status("Bluetooth LE link up (MTU $mtu)")
                    return transport
                } catch (e: TimeoutCancellationException) {
                    lastError = TransportException("Bluetooth LE connection timed out")
                } catch (e: TransportException) {
                    lastError = e
                } catch (e: SecurityException) {
                    lastError = TransportException("Bluetooth permission denied", e)
                }
                Timber.w(lastError, "BLE connect attempt %d failed", attempt)
                client.close()
                scope.cancel()
                // Retry only failures to establish the link, not server refusals.
                val refused = lastError?.message.orEmpty()
                if (refused.startsWith("Server") || refused.contains("ble-term")) break
                delay(500)
            }
            throw lastError ?: TransportException("Bluetooth LE connection failed")
        }
    }
}
