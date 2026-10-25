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
import android.os.SystemClock
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
import org.connectbot.R
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
    private val pairing: PairingWatch,
) : Transport {
    private val link = BleStreamLink(maxFrame, RECV_WINDOW)
    private val lock = Mutex()
    private val readLock = Mutex()
    private val wakeWriter = Channel<Unit>(Channel.CONFLATED)

    /** Signalled whenever the writer took a frame from the link or the link failed. */
    private val progress = Channel<Unit>(Channel.CONFLATED)
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()
    private val idle = IdleProbe(IDLE_PROBE_MS, SystemClock.elapsedRealtime())

    /** Completed by the writer once a CLOSE frame (and everything before it) was written. */
    private val closeSent = CompletableDeferred<Unit>()

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
        scope.launch { probeLoop() }
        wakeWriter.trySend(Unit)
    }

    /** Checks a quiet session now and then (see [IdleProbe]). */
    private suspend fun probeLoop() {
        while (scope.isActive) {
            delay(PROBE_CHECK_MS)
            val queued = lock.withLock {
                (link.isOpen && !link.isClosing && idle.due(SystemClock.elapsedRealtime())).also { if (it) link.probe() }
            }
            if (queued) wakeWriter.trySend(Unit)
        }
    }

    private fun fail(e: TransportException) {
        if (failure == null) failure = e
        opened.completeExceptionally(e)
        inbound.close(e)
        progress.trySend(Unit)
    }

    private suspend fun readerLoop() {
        var warnedReorder = false
        // The channel is closed on disconnect only after all received
        // notifications are queued, so data and CLOSE are never lost to it.
        for (pkt in client.notifications) {
            val events = try {
                lock.withLock {
                    idle.received(SystemClock.elapsedRealtime())
                    link.onPacket(pkt)
                }
            } catch (e: BleStreamLink.ProtocolException) {
                Timber.w("BLE protocol error: %s", e.message)
                lock.withLock { link.close(BleStreamLink.CLOSE_PROTOCOL_ERROR, e.message ?: "") }
                wakeWriter.trySend(Unit)
                withTimeoutOrNull(CLOSE_FLUSH_MS) { closeSent.await() }
                fail(TransportException("BLE protocol error: ${e.message}"))
                return
            }
            if (!warnedReorder && link.reordered > 0) {
                warnedReorder = true
                Timber.w("BLE frames arrive out of order; BleStream v1 assumes a single ATT bearer")
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
        // A pairing request can come at any time, e.g. when the server opens
        // EATT after login; the phone then drops the link.
        fail(TransportException(if (pairing.requested) pairing.message else "Bluetooth connection lost"))
    }

    private suspend fun writerLoop() {
        try {
            while (scope.isActive) {
                val frame = lock.withLock {
                    link.pollTransmit().also {
                        if (it == null && link.isClosing) closeSent.complete(Unit)
                    }
                }
                if (frame == null) {
                    wakeWriter.receive()
                    continue
                }
                if (BleStreamLink.isProbe(frame)) {
                    try {
                        client.writeFrame(frame, withResponse = true)
                    } catch (e: TransportException) {
                        throw TransportException("The ble-term server stopped responding (${e.message})", e)
                    }
                } else {
                    client.writeFrame(frame)
                }
                progress.trySend(Unit)
            }
        } catch (e: TransportException) {
            // A dropped link is reported by the reader once it drained the
            // notifications received before the drop.
            if (!client.disconnected.isCompleted) fail(e)
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
            if (queued) {
                wakeWriter.trySend(Unit)
                return
            }
            progress.receive()
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        pairing.stop()
        if (failure == null) {
            // Deliver queued SSH data (e.g. SSH_MSG_DISCONNECT) before CLOSE.
            withTimeoutOrNull(CLOSE_FLUSH_MS) {
                while (failure == null && lock.withLock { link.pendingOut() } > 0) progress.receive()
            }
            lock.withLock { link.close(BleStreamLink.CLOSE_NORMAL, "client closed") }
            wakeWriter.trySend(Unit)
            withTimeoutOrNull(CLOSE_FLUSH_MS) { closeSent.await() }
            // Leave the subscription off, so the next connection's enabling
            // write changes it (see enableNotifications).
            withTimeoutOrNull(CLOSE_FLUSH_MS) {
                try {
                    client.writeCccd(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                } catch (e: TransportException) {
                    Timber.d(e, "Unsubscribing failed")
                }
            }
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

        enum class Op { MTU, DISCOVER, DESCRIPTOR, WRITE }

        private class Pending(val op: Op, val result: CompletableDeferred<Int>)

        @Volatile
        private var pending: Pending? = null

        /** Completes the current operation only if [op] is the callback it waits for. */
        private fun complete(op: Op, status: Int) {
            pending?.takeIf { it.op == op }?.result?.complete(status)
        }

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
                pending?.result?.completeExceptionally(e)
                notifications.close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            complete(Op.MTU, status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            complete(Op.DISCOVER, status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            complete(Op.DESCRIPTOR, status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            complete(Op.WRITE, status)
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
        private suspend fun op(op: Op, name: String, timeoutMs: Long = OP_TIMEOUT_MS, start: () -> Boolean): Int? = opMutex.withLock {
            if (disconnected.isCompleted) throw TransportException("Bluetooth connection lost")
            val result = CompletableDeferred<Int>()
            pending = Pending(op, result)
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

        private suspend fun opRetrying(op: Op, name: String, start: () -> Boolean): Int {
            repeat(START_RETRIES) {
                op(op, name, start = start)?.let { return it }
                delay(START_RETRY_DELAY_MS)
            }
            throw TransportException("Bluetooth $name could not be started")
        }

        /** Requests a large MTU; failures are not fatal, the current MTU is used then. */
        suspend fun requestMtu(): Int {
            try {
                opRetrying(Op.MTU, "MTU request") { gatt.requestMtu(REQUESTED_MTU) }
            } catch (e: TransportException) {
                if (disconnected.isCompleted) throw e
                Timber.w("MTU request failed (%s), using MTU %d", e.message, mtu)
            }
            return mtu
        }

        suspend fun discover() {
            val status = opRetrying(Op.DISCOVER, "service discovery") { gatt.discoverServices() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw TransportException("Service discovery failed (status $status)")
        }

        lateinit var tx: BluetoothGattCharacteristic

        /**
         * Subscribes to [tx]. The descriptor is cleared first: a server on
         * BlueZ before 5.69 only notices a subscription when the value
         * changes, and a previous session on the same link may have left it
         * set.
         */
        suspend fun enableNotifications(tx: BluetoothGattCharacteristic) {
            this.tx = tx
            if (!gatt.setCharacteristicNotification(tx, true)) throw TransportException("Cannot enable notifications")
            writeCccd(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            writeCccd(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        @Suppress("DEPRECATION")
        suspend fun writeCccd(value: ByteArray) {
            val cccd = tx.getDescriptor(CCCD_UUID) ?: throw TransportException("Server has no notification descriptor")
            val status = opRetrying(Op.DESCRIPTOR, "subscription") {
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
        suspend fun writeFrame(frame: ByteArray, withResponse: Boolean = false) {
            val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val status = opRetrying(Op.WRITE, "write") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(rx, frame, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    rx.writeType = type
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
        private const val IDLE_PROBE_MS = 20_000L
        private const val PROBE_CHECK_MS = 1_000L
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

        internal fun describeClose(reason: Int, message: String): String {
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
                throw TransportException("ConnectBot needs the Nearby devices permission for Bluetooth LE hosts: open the host in the editor or allow it in system settings")
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw TransportException("Bluetooth is not available on this device")
            if (!adapter.isEnabled) throw TransportException("Bluetooth is turned off")
            val mac = normalizeAddress(address)
                ?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
                ?: throw TransportException("Invalid Bluetooth address: $address")
            val device = adapter.getRemoteDevice(mac)

            val pairing = PairingWatch(context, mac)
            try {
                return connectAttempts(context, device, mac, dispatcher, status, pairing)
            } catch (e: Throwable) {
                pairing.stop()
                throw e
            }
        }

        @SuppressLint("MissingPermission")
        private suspend fun connectAttempts(
            context: Context,
            device: BluetoothDevice,
            mac: String,
            dispatcher: CoroutineDispatcher,
            status: (String) -> Unit,
            pairing: PairingWatch,
        ): BleStreamTransport {
            var lastError: TransportException? = null
            for (attempt in 1..CONNECT_ATTEMPTS) {
                status(
                    if (attempt == 1) {
                        context.getString(R.string.ble_status_connecting, mac)
                    } else {
                        context.getString(R.string.ble_status_connecting_attempt, mac, attempt)
                    },
                )
                val client = GattClient()
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                var connected = false
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
                    val transport = BleStreamTransport(client, scope, mtu - 3, pairing)
                    transport.start()
                    withTimeout(HANDSHAKE_TIMEOUT_MS) { transport.opened.await() }
                    status(context.getString(R.string.ble_status_link_up, mtu))
                    connected = true
                    return transport
                } catch (e: TimeoutCancellationException) {
                    lastError = TransportException("Bluetooth LE connection timed out")
                } catch (e: TransportException) {
                    lastError = e
                } catch (e: SecurityException) {
                    lastError = TransportException("Bluetooth permission denied", e)
                } finally {
                    // Also on cancellation: a leaked GattClient keeps the link and a
                    // system-wide GATT client slot.
                    if (!connected) {
                        client.close()
                        scope.cancel()
                    }
                }
                Timber.w(lastError, "BLE connect attempt %d failed", attempt)
                if (pairing.requested) throw TransportException(pairing.message)
                if (!shouldRetry(lastError)) break
                delay(500)
            }
            throw lastError ?: TransportException("Bluetooth LE connection failed")
        }

        /** Retry only failures to establish the link, not refusals by the server. */
        internal fun shouldRetry(error: TransportException?): Boolean {
            val message = error?.message.orEmpty()
            return !message.startsWith("Server") && !message.contains("ble-term")
        }
    }
}
