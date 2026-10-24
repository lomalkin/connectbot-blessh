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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class BleStreamLinkTest {
    private fun hex(s: String): ByteArray = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun pump(from: BleStreamLink, to: BleStreamLink): List<BleStreamLink.Event> {
        val events = ArrayList<BleStreamLink.Event>()
        while (true) {
            val pkt = from.pollTransmit() ?: break
            assertThat(pkt.size).isLessThanOrEqualTo(from.maxFrame)
            events += to.onPacket(pkt)
        }
        return events
    }

    private fun data(events: List<BleStreamLink.Event>): ByteArray = events.filterIsInstance<BleStreamLink.Event.Data>().fold(ByteArray(0)) { acc, e -> acc + e.bytes }

    @Test
    fun helloMatchesServerTestVector() {
        // Same vector as the server's protocol tests (docs/PROTOCOL.md).
        val link = BleStreamLink(20, 0x4000)
        assertThat(link.pollTransmit()).isEqualTo(hex("01 00 00 42 4c 54 4d 01 00 00 40 00 00"))
    }

    @Test
    fun decodesServerFrames() {
        val link = BleStreamLink(20, 1000)
        link.pollTransmit()
        assertThat(link.onPacket(hex("01 00 00 42 4c 54 4d 01 00 00 40 00 00"))).containsExactly(BleStreamLink.Event.Open(0x4000))
        val ev = link.onPacket(hex("02 01 00 61 62"))
        assertThat(data(ev)).isEqualTo("ab".toByteArray())
        assertThat(link.onPacket(hex("04 02 00 00 6f 6b"))).containsExactly(BleStreamLink.Event.Closed(0, "ok"))
    }

    @Test
    fun transfersWithCredits() {
        val a = BleStreamLink(20, 100)
        val b = BleStreamLink(64, 1000)
        pump(a, b)
        pump(b, a)
        a.send(ByteArray(300) { 7 })
        assertThat(data(pump(a, b))).isEqualTo(ByteArray(300) { 7 })
        b.send(ByteArray(250) { 1 })
        assertThat(data(pump(b, a)).size).isEqualTo(100)
        assertThat(b.pendingOut()).isEqualTo(150)
        a.consumed(100)
        pump(a, b)
        assertThat(data(pump(b, a)).size).isEqualTo(100)
    }

    @Test
    fun restoresOrder() {
        val a = BleStreamLink(20, 10_000)
        val b = BleStreamLink(20, 10_000)
        pump(a, b)
        pump(b, a)
        val payload = ByteArray(200) { it.toByte() }
        a.send(payload)
        val pkts = generateSequence { a.pollTransmit() }.toMutableList()
        pkts[0] = pkts[1].also { pkts[1] = pkts[0] }
        val out = pkts.flatMap { b.onPacket(it) }
        assertThat(data(out)).isEqualTo(payload)
    }

    @Test
    fun rejectsWindowViolation() {
        val a = BleStreamLink(20, 100)
        a.pollTransmit()
        a.onPacket(hex("01 00 00 42 4c 54 4d 01 00 00 40 00 00"))
        assertThatThrownBy { a.onPacket(byteArrayOf(2, 1, 0) + ByteArray(101)) }
            .isInstanceOf(BleStreamLink.ProtocolException::class.java)
    }
}
