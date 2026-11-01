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

import java.util.Locale

/**
 * A blessh server's INFO characteristic (blessh docs/protocol.md, "GATT
 * service"): `key=value` pairs separated by `;`. Unknown keys are ignored.
 */
data class ServerInfo(
    val name: String?,
    /** PSMs of the server's L2CAP channels, in the order to try them. */
    val psms: List<Int>,
    /** Version of the channel handshake the server speaks; null without channels. */
    val channelVersion: Int?,
    /** False when the server does not serve the GATT stream (`gatt=0`). */
    val gattStream: Boolean,
    /** True while another device holds the GATT stream (`busy=1`). */
    val busy: Boolean,
) {
    /**
     * The channels this client may use: the server lists them, speaks a
     * handshake version this client can negotiate down to, and the phone has
     * LE connection-oriented channels (Android 10).
     */
    fun channelsFor(sdk: Int): List<Int> = if (sdk >= L2CAP_MIN_SDK && (channelVersion ?: 0) >= ChannelHandshake.VERSION) psms else emptyList()

    companion object {
        /** Android 10: `BluetoothDevice.createInsecureL2capChannel`. */
        const val L2CAP_MIN_SDK = 29

        fun parse(value: String): ServerInfo {
            val keys = value.split(';').mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) null else pair.substring(0, i) to pair.substring(i + 1)
            }.toMap()
            return ServerInfo(
                name = keys["name"]?.takeIf { it.isNotEmpty() },
                psms = keys["psm"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..0xFFFF },
                channelVersion = keys["chan"]?.toIntOrNull(),
                gattStream = keys["gatt"] != "0",
                busy = keys["busy"] == "1",
            )
        }
    }
}

/** Server names (blessh docs/protocol.md, "Names"). */
object ServerNames {
    /** Longest name: what fits the scan response of a legacy advertisement. */
    const val MAX_LEN = 29

    /** Whether [name] is a valid server name: host name rules, at most [MAX_LEN] bytes. */
    fun isValid(name: String): Boolean {
        if (name.isEmpty() || name.toByteArray().size > MAX_LEN) return false
        return name.split('.').all { label ->
            label.isNotEmpty() &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' } &&
                !label.startsWith('-') &&
                !label.endsWith('-')
        }
    }

    /** Whether two names are the same server: ASCII case-insensitive, a trailing dot ignored. */
    fun same(a: String, b: String): Boolean = a.removeSuffix(".").lowercase(Locale.US) == b.removeSuffix(".").lowercase(Locale.US)
}
