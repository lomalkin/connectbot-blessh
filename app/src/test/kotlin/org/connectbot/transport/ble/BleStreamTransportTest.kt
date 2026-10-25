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

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.sshlib.transport.TransportException
import org.junit.Test

class BleStreamTransportTest {
    @Test
    fun normalizesAddresses() {
        val expected = "AA:BB:CC:DD:EE:0F"
        listOf("aa:bb:cc:dd:ee:0f", "AABBCCDDEE0F", "aa-bb-cc-dd-ee-0f", " AA:BB:CC:DD:EE:0F ").forEach {
            assertThat(BleStreamTransport.normalizeAddress(it)).isEqualTo(expected)
        }
    }

    @Test
    fun rejectsInvalidAddresses() {
        listOf("", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:0F:11", "GG:BB:CC:DD:EE:0F", "rns-3.local").forEach {
            assertThat(BleStreamTransport.normalizeAddress(it)).isNull()
        }
    }

    @Test
    fun retriesOnlyLinkFailures() {
        assertThat(BleStreamTransport.shouldRetry(TransportException("Bluetooth connection failed (status 133)"))).isTrue()
        assertThat(BleStreamTransport.shouldRetry(null)).isTrue()
        assertThat(BleStreamTransport.shouldRetry(TransportException("Server is busy with another client"))).isFalse()
        assertThat(BleStreamTransport.shouldRetry(TransportException("AA:BB:CC:DD:EE:0F is not a ble-term server"))).isFalse()
    }

    @Test
    fun describesCloseReasons() {
        assertThat(BleStreamTransport.describeClose(BleStreamLink.CLOSE_BUSY, "another client is connected"))
            .isEqualTo("Server is busy with another client: another client is connected")
        assertThat(BleStreamTransport.describeClose(BleStreamLink.CLOSE_NORMAL, "")).isEqualTo("Server closed the session")
        // Reasons added after v1 are shown generically.
        assertThat(BleStreamTransport.describeClose(9, "x")).isEqualTo("Server closed the link: x")
    }
}
