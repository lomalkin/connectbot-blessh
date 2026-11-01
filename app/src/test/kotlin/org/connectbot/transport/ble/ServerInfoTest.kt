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
import org.junit.Test

class ServerInfoTest {
    @Test
    fun parsesALinuxServer() {
        val info = ServerInfo.parse("proto=1;name=rns-3;mtu=247;psm=128,129;chan=1")
        assertThat(info.name).isEqualTo("rns-3")
        assertThat(info.psms).containsExactly(128, 129)
        assertThat(info.channelVersion).isEqualTo(1)
        assertThat(info.gattStream).isTrue()
        assertThat(info.busy).isFalse()
    }

    @Test
    fun parsesAMacServer() {
        val info = ServerInfo.parse("proto=1;name=mbp;psm=192;chan=1;gatt=0")
        assertThat(info.psms).containsExactly(192)
        assertThat(info.gattStream).isFalse()
    }

    @Test
    fun parsesAnOldServerAndIgnoresUnknownKeys() {
        val info = ServerInfo.parse("proto=1;name=pi;mtu=23;busy=1;future=x;novalue")
        assertThat(info.psms).isEmpty()
        assertThat(info.channelVersion).isNull()
        assertThat(info.gattStream).isTrue()
        assertThat(info.busy).isTrue()
        // A key's value is not mistaken for another key.
        assertThat(ServerInfo.parse("proto=1;name=busy=1x").busy).isFalse()
    }

    @Test
    fun usesChannelsOnlyWhereThePhoneAndTheHandshakeAllow() {
        val info = ServerInfo.parse("proto=1;name=pi;psm=128,129;chan=1")
        assertThat(info.channelsFor(29)).containsExactly(128, 129)
        assertThat(info.channelsFor(28)).isEmpty()
        // A newer handshake is negotiated down to the client's version.
        assertThat(ServerInfo.parse("psm=128;chan=2").channelsFor(34)).containsExactly(128)
        assertThat(ServerInfo.parse("psm=128").channelsFor(34)).isEmpty()
        assertThat(ServerInfo.parse("psm=128,x,0,70000;chan=1").channelsFor(34)).containsExactly(128)
    }

    @Test
    fun checksNamesByHostNameRules() {
        listOf("rns-3", "my-Pi.lan", "a", "x".repeat(29)).forEach { assertThat(ServerNames.isValid(it)).describedAs(it).isTrue() }
        listOf("", "-pi", "pi-", "a..b", "two words", "pi_3", "x".repeat(30), "AA:BB:CC:DD:EE:FF").forEach {
            assertThat(ServerNames.isValid(it)).describedAs(it).isFalse()
        }
    }

    @Test
    fun comparesNamesLikeHostNames() {
        assertThat(ServerNames.same("RNS-3", "rns-3")).isTrue()
        assertThat(ServerNames.same("rns-3.", "rns-3")).isTrue()
        assertThat(ServerNames.same("rns-3", "rns-30")).isFalse()
    }
}
