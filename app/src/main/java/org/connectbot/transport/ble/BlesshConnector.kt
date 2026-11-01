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
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.R
import org.connectbot.sshlib.transport.TransportException
import timber.log.Timber
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects to a blessh server as blessh docs/protocol.md requires of a
 * client ("Requirements"): find the server by Bluetooth address or by name,
 * read INFO, try the server's L2CAP channels in order with the channel
 * handshake, and fall back to the GATT stream unless the server has none.
 */
object BlesshConnector {
    private const val CONNECT_ATTEMPTS = 3
    private const val CONNECT_TIMEOUT_MS = 20_000L

    /** A remembered address may be gone (macOS servers change theirs): try it briefly. */
    private const val REMEMBERED_TIMEOUT_MS = 8_000L
    private const val SCAN_TIMEOUT_MS = 15_000L
    private const val CHANNEL_CONNECT_TIMEOUT_MS = 10_000L
    private const val PREFS = "blessh_servers"

    /**
     * Connects to [target], a Bluetooth address or a server name. [status]
     * receives progress lines for the terminal.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(context: Context, target: String, dispatcher: CoroutineDispatcher, status: (String) -> Unit): BlesshTransport {
        if (!BleStreamTransport.hasPermission(context)) {
            throw TransportException("ConnectBot needs the Nearby devices permission for Bluetooth LE hosts: open the host in the editor or allow it in system settings")
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw TransportException("Bluetooth is not available on this device")
        if (!adapter.isEnabled) throw TransportException("Bluetooth is turned off")

        val address = BleStreamTransport.normalizeAddress(target)?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
        if (address != null) return connectDevice(context, adapter.getRemoteDevice(address), null, dispatcher, status)

        val name = target.trim()
        if (!ServerNames.isValid(name)) throw TransportException("Not a Bluetooth address or a server name: $target")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = name.removeSuffix(".").lowercase(Locale.US)
        prefs.getString(key, null)?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }?.let { remembered ->
            status(context.getString(R.string.ble_status_connecting, "$name ($remembered)"))
            try {
                return connectDevice(context, adapter.getRemoteDevice(remembered), name, dispatcher, status, quick = true)
            } catch (e: TransportException) {
                if (isRefusal(e)) throw e
                Timber.i(e, "Remembered address %s of %s did not work", remembered, name)
            }
        }
        cannotScan(
            Build.VERSION.SDK_INT,
            { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED },
            context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true,
        )?.let { throw TransportException("Cannot look for \"$name\": $it") }
        status(context.getString(R.string.ble_status_searching, name))
        val device = findByName(adapter, name)
            ?: throw TransportException("No blessh server named \"$name\" found nearby")
        val transport = connectDevice(context, device, name, dispatcher, status)
        prefs.edit { putString(key, device.address) }
        return transport
    }

    /**
     * Why the phone cannot scan for a server by name, or null if it can.
     * Without the permission, or with location off before Android 12, the
     * system silently withholds all scan results.
     */
    internal fun cannotScan(sdk: Int, granted: (String) -> Boolean, locationOn: Boolean): String? = when {
        sdk >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_SCAN) ->
            "ConnectBot needs the Nearby devices permission; open the host in the editor and tap Scan once to grant it"

        sdk < Build.VERSION_CODES.S && !granted(Manifest.permission.ACCESS_FINE_LOCATION) ->
            "ConnectBot needs the location permission to scan on Android 11 and earlier; open the host in the editor and tap Scan once to grant it"

        sdk < Build.VERSION_CODES.S && !locationOn ->
            "Android 11 and earlier find Bluetooth devices only while location is on; turn it on, or save the host with the server's Bluetooth address"

        else -> null
    }

    /**
     * What the server or this phone decided, which another attempt would
     * not change: the server's refusals and CLOSE reasons, a device that is
     * not a blessh server, a server this phone cannot use. Everything else
     * (a link or a channel that failed) is retried.
     */
    private val REFUSALS = listOf(
        "Server refused",
        "Server is busy",
        "Server closed",
        "Server ended",
        "Server reported",
        "Server timed out",
        "Server offers only L2CAP channels, which need",
        "not a blessh server",
        "incomplete blessh service",
        "is now \"",
    )

    internal fun isRefusal(e: TransportException?): Boolean {
        val message = e?.message.orEmpty()
        return REFUSALS.any { message.contains(it) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectDevice(
        context: Context,
        device: BluetoothDevice,
        expectedName: String?,
        dispatcher: CoroutineDispatcher,
        status: (String) -> Unit,
        quick: Boolean = false,
    ): BlesshTransport {
        val mac = device.address
        val pairing = PairingWatch(context, mac)
        var lastError: TransportException? = null
        val attempts = if (quick) 1 else CONNECT_ATTEMPTS
        for (attempt in 1..attempts) {
            if (!quick) {
                status(
                    if (attempt == 1) {
                        context.getString(R.string.ble_status_connecting, mac)
                    } else {
                        context.getString(R.string.ble_status_connecting_attempt, mac, attempt)
                    },
                )
            }
            var link: GattLink? = null
            var done = false
            try {
                link = BleStreamTransport.openGatt(context, device, if (quick) REMEMBERED_TIMEOUT_MS else CONNECT_TIMEOUT_MS)
                val serverName = link.info?.name
                if (expectedName != null && serverName != null && !ServerNames.same(serverName, expectedName)) {
                    // Another server took the remembered address.
                    throw TransportException("$mac is now \"$serverName\", not \"$expectedName\"")
                }
                val transport = openTransport(context, link, dispatcher, pairing, status)
                done = true
                return transport
            } catch (e: TransportException) {
                lastError = e
            } finally {
                if (!done) link?.client?.close()
            }
            Timber.w(lastError, "BLE connect attempt %d to %s failed", attempt, mac)
            if (pairing.requested) {
                pairing.stop()
                throw TransportException(pairing.message)
            }
            if (isRefusal(lastError)) break
            if (attempt < attempts) delay(500)
        }
        pairing.stop()
        throw lastError ?: TransportException("Bluetooth LE connection failed")
    }

    /** The channels, then the GATT stream (blessh docs/protocol.md, "Channel handshake"). */
    private suspend fun openTransport(
        context: Context,
        link: GattLink,
        dispatcher: CoroutineDispatcher,
        pairing: PairingWatch,
        status: (String) -> Unit,
    ): BlesshTransport {
        val info = link.info ?: ServerInfo(null, emptyList(), null, gattStream = true, busy = false)
        val channels = info.channelsFor(Build.VERSION.SDK_INT)
        var channelError: String? = null
        // Without a GATT stream to fall back to, a channel that stalled gets
        // a second try on the same link: some stacks stall now and then
        // (seen between Android and a macOS server).
        val rounds = if (info.gattStream) 1 else 2
        if (channels.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (psm in List(rounds) { channels }.flatten()) {
                status(context.getString(R.string.ble_status_channel, psm))
                when (val r = openChannel(link, psm, dispatcher)) {
                    is ChannelAttempt.Open -> {
                        pairing.stop()
                        status(context.getString(R.string.ble_status_channel_up, psm))
                        return L2capTransport(r.socket, link.client, dispatcher)
                    }

                    is ChannelAttempt.Failed -> {
                        Timber.w("Channel on PSM %d: %s", psm, r.why)
                        channelError = r.why
                    }
                }
            }
        }
        if (!info.gattStream) {
            throw TransportException(
                if (Build.VERSION.SDK_INT < ServerInfo.L2CAP_MIN_SDK || info.psms.isEmpty()) {
                    "Server offers only L2CAP channels, which need Android 10 or later"
                } else {
                    "No L2CAP channel of the server worked (${channelError ?: "its handshake version is not supported"}), and it serves no GATT stream"
                },
            )
        }
        if (channelError != null) status(context.getString(R.string.ble_status_channel_fallback))
        return BleStreamTransport.startStream(context, link, dispatcher, pairing, status)
    }

    private sealed class ChannelAttempt {
        class Open(val socket: BluetoothSocket) : ChannelAttempt()

        class Failed(val why: String) : ChannelAttempt()
    }

    /** Opens the channel on [psm] and runs the handshake; a refusal ends the whole attempt. */
    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private suspend fun openChannel(link: GattLink, psm: Int, dispatcher: CoroutineDispatcher): ChannelAttempt {
        val socket = try {
            link.client.gatt.device.createInsecureL2capChannel(psm)
        } catch (e: IOException) {
            return ChannelAttempt.Failed("cannot create the channel: ${e.message}")
        } catch (e: SecurityException) {
            throw TransportException("Bluetooth permission denied", e)
        }
        var open = false
        try {
            connectWithin(socket, dispatcher, CHANNEL_CONNECT_TIMEOUT_MS)?.let { return ChannelAttempt.Failed(it) }
            val result = try {
                ChannelHandshake.run(socket.inputStream, socket.outputStream, { closeQuietly(socket) }, dispatcher)
            } catch (e: IOException) {
                return ChannelAttempt.Failed("handshake failed: ${e.message}")
            }
            return when (result) {
                is ChannelHandshake.Result.Accepted -> {
                    open = true
                    ChannelAttempt.Open(socket)
                }

                is ChannelHandshake.Result.Refused -> throw TransportException("Server refused the session: ${result.reason}")

                is ChannelHandshake.Result.Stalled -> ChannelAttempt.Failed("the channel stalled (echo ${result.echoed} of ${result.expected} bytes)")
            }
        } finally {
            if (!open) closeQuietly(socket)
        }
    }

    /**
     * Connects [socket]; returns why it failed, or null. connect() blocks
     * without a timeout of its own and does not react to interrupts:
     * closing the socket after [timeoutMs] is what ends it.
     */
    private suspend fun connectWithin(socket: BluetoothSocket, dispatcher: CoroutineDispatcher, timeoutMs: Long): String? = coroutineScope {
        val timedOut = AtomicBoolean(false)
        val timer = launch {
            delay(timeoutMs)
            timedOut.set(true)
            closeQuietly(socket)
        }
        try {
            runInterruptible(dispatcher) { socket.connect() }
            null
        } catch (e: IOException) {
            if (timedOut.get()) "connecting timed out" else "cannot connect: ${e.message}"
        } finally {
            timer.cancel()
        }
    }

    private fun closeQuietly(socket: BluetoothSocket) {
        try {
            socket.close()
        } catch (e: IOException) {
            Timber.d(e, "Closing a channel failed")
        }
    }

    /**
     * Scans for a server advertising [name]. No scan filter: on some phones
     * (seen on Android 11) a service UUID filter lets nothing through.
     */
    @SuppressLint("MissingPermission")
    private suspend fun findByName(adapter: BluetoothAdapter, name: String): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<BluetoothDevice?>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                if (record.serviceUuids?.any { it.uuid == BleStreamLink.SERVICE_UUID } != true) return
                val advertised = record.deviceName ?: return
                if (ServerNames.same(advertised, name)) found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.w("BLE scan failed: %d", errorCode)
                found.complete(null)
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(emptyList<ScanFilter>(), settings, callback)
        } catch (e: SecurityException) {
            throw TransportException("ConnectBot needs the Nearby devices permission to find \"$name\"", e)
        }
        try {
            return withTimeoutOrNull(SCAN_TIMEOUT_MS) { found.await() }
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                Timber.d(e, "stopScan failed")
            }
        }
    }
}
