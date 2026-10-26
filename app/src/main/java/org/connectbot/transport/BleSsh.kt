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

package org.connectbot.transport

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.DisconnectReason
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.sshlib.transport.TransportFactory
import org.connectbot.transport.ble.BleStreamTransport
import java.util.Locale
import java.util.regex.Pattern

/**
 * SSH over Bluetooth LE: a regular SSH session whose byte stream runs over a
 * ble-term server's GATT service instead of TCP. The host name is the
 * server's Bluetooth address; there is no port.
 */
class BleSsh : SSH {
    constructor() : super()

    constructor(host: Host?, bridge: TerminalBridge?, manager: TerminalManager?) : super(host, bridge, manager)

    override fun instanceProtocolName(): String = PROTOCOL

    override fun canForwardPorts(): Boolean = false

    override fun usesNetwork(): Boolean = false

    override fun resetOnConnectionChange(): Boolean = false

    override fun obscureKeystrokeTiming(): Boolean = false

    override fun directTransportFactory(dispatchers: CoroutineDispatchers): TransportFactory? {
        val context: Context = manager ?: return null
        val address = host?.hostname ?: return null
        return TransportFactory {
            BleStreamTransport.connect(context, address, dispatchers.io) { bridge?.outputLine(it) }.also { transport = it }
        }
    }

    @Volatile
    private var transport: BleStreamTransport? = null

    /**
     * Shows why the Bluetooth LE link ended (the server's CLOSE reason, a
     * pairing request, a server that stopped answering): the generic
     * disconnect handling only reports that the host closed the connection.
     */
    override fun onDisconnect(reason: DisconnectReason) {
        if (reason != DisconnectReason.SESSION_EXIT && reason != DisconnectReason.USER_REQUESTED) {
            transport?.failureMessage?.let { bridge?.outputLine(it) }
        }
        transport = null
        super.onDisconnect(reason)
    }

    override fun getDefaultPort(): Int = DEFAULT_PORT

    override fun getDefaultNickname(username: String?, hostname: String?, port: Int): String = String.format(Locale.US, "ble:%s@%s", username, hostname)

    override fun createHost(uri: Uri): Host {
        val hostname = addressFromUri(uri)
        val username = uri.userInfo ?: ""
        return Host.createBleHost(getDefaultNickname(username, hostname, DEFAULT_PORT), hostname, username)
    }

    override fun getSelectionArgs(uri: Uri, selection: MutableMap<String, String>) {
        selection["protocol"] = PROTOCOL
        selection["nickname"] = uri.fragment ?: ""
        selection["hostname"] = addressFromUri(uri)
        selection["port"] = DEFAULT_PORT.toString()
        selection["username"] = uri.userInfo ?: ""
    }

    companion object {
        private const val PROTOCOL = "ble"
        private const val DEFAULT_PORT = 0

        private val addressMask = Pattern.compile(
            "^(.+)@([0-9a-f]{2}(?:[:-]?[0-9a-f]{2}){5})$",
            Pattern.CASE_INSENSITIVE,
        )

        /** URI authority form of an address: colons would be read as a port separator. */
        fun uriHost(address: String): String = address.replace(":", "").replace("-", "").uppercase(Locale.US)

        private fun addressFromUri(uri: Uri): String {
            val raw = uri.encodedAuthority?.substringAfter('@') ?: uri.host ?: ""
            return BleStreamTransport.normalizeAddress(raw) ?: raw
        }

        @JvmStatic
        fun getProtocolName(): String = PROTOCOL

        @JvmStatic
        fun getUri(input: String): Uri? {
            val matcher = addressMask.matcher(input.trim())
            if (!matcher.matches()) return null
            return "$PROTOCOL://${Uri.encode(matcher.group(1))}@${uriHost(matcher.group(2))}/#${Uri.encode(input)}".toUri()
        }

        @JvmStatic
        fun getFormatHint(context: Context): String = String.format(
            "%s@%s",
            context.getString(R.string.format_username),
            context.getString(R.string.format_ble_address),
        )
    }
}
