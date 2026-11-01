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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class ChannelHandshakeTest {
    private fun hex(s: String): ByteArray = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /** The test vectors of blessh docs/protocol.md, "Channel handshake". */
    @Test
    fun matchesTheProtocolTestVectors() {
        assertThat(ChannelHandshake.hello("abcd".toByteArray())).isEqualTo(hex("42 4c 53 48 01 00 04 00 00 00 61 62 63 64"))
        val accepted = ChannelHandshake.parseHeader(hex("42 4c 53 48 01 00 04 00 00 00"))
        assertThat(accepted).isEqualTo(ChannelHandshake.Header(version = 1, status = 0, probeLen = 4, optionsLen = 0))
        val refused = hex("42 4c 53 48 01 01 00 00 06 00 01 04 62 75 73 79")
        val header = ChannelHandshake.parseHeader(refused)
        assertThat(header.status).isEqualTo(ChannelHandshake.STATUS_REFUSED)
        assertThat(ChannelHandshake.reason(refused.copyOfRange(10, 10 + header.optionsLen))).isEqualTo("busy")
    }

    @Test
    fun skipsUnknownOptionsAndRejectsTruncatedOnes() {
        assertThat(ChannelHandshake.reason(hex("09 01 ff 01 02 6f 6b"))).isEqualTo("ok")
        assertThat(ChannelHandshake.reason(ByteArray(0))).isNull()
        assertThatThrownBy { ChannelHandshake.reason(hex("01 05 61")) }.isInstanceOf(ChannelHandshake.ProtocolException::class.java)
    }

    @Test
    fun rejectsOtherProtocolsAndNewerVersions() {
        assertThatThrownBy { ChannelHandshake.parseHeader("SSH-2.0-Op".toByteArray()) }.isInstanceOf(ChannelHandshake.ProtocolException::class.java)
        assertThatThrownBy { ChannelHandshake.parseHeader(hex("42 4c 53 48 02 00 04 00 00 00")) }
            .isInstanceOf(ChannelHandshake.ProtocolException::class.java)
    }

    @Test
    fun acceptsAChannelThatEchoesTheProbe() = runBlocking<Unit> {
        val out = ByteArrayOutputStream()
        val probe = ChannelHandshake.probe(64)
        val reply = ChannelHandshake.header(1, ChannelHandshake.STATUS_ACCEPTED, 64, ByteArray(0)) + probe
        val result = ChannelHandshake.run(reply.inputStream(), out, {}, Dispatchers.IO, probeLen = 64)
        assertThat(result).isEqualTo(ChannelHandshake.Result.Accepted(1))
        assertThat(out.toByteArray()).isEqualTo(ChannelHandshake.hello(probe))
    }

    @Test
    fun reportsARefusalWithItsReason() = runBlocking<Unit> {
        val reason = "busy (4 sessions)".toByteArray()
        val reply = ChannelHandshake.header(1, ChannelHandshake.STATUS_REFUSED, 0, byteArrayOf(1, reason.size.toByte()) + reason)
        val result = ChannelHandshake.run(reply.inputStream(), ByteArrayOutputStream(), {}, Dispatchers.IO, probeLen = 64)
        assertThat(result).isEqualTo(ChannelHandshake.Result.Refused("busy (4 sessions)"))
    }

    @Test
    fun rejectsAWrongEcho() {
        val reply = ChannelHandshake.header(1, ChannelHandshake.STATUS_ACCEPTED, 4, ByteArray(0)) + "abcd".toByteArray()
        assertThatThrownBy { runBlocking { ChannelHandshake.run(reply.inputStream(), ByteArrayOutputStream(), {}, Dispatchers.IO, probeLen = 4) } }
            .isInstanceOf(ChannelHandshake.ProtocolException::class.java)
    }

    /** Delivers [data], then blocks until closed, as a channel that stopped. */
    private class StallingInput(private val data: ByteArray) : InputStream() {
        private var pos = 0
        private val lock = Object()

        @Volatile
        private var closed = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos < data.size) {
                val n = minOf(len, data.size - pos)
                System.arraycopy(data, pos, b, off, n)
                pos += n
                return n
            }
            synchronized(lock) {
                while (!closed) lock.wait()
            }
            throw IOException("closed")
        }

        override fun close() {
            closed = true
            synchronized(lock) { lock.notifyAll() }
        }
    }

    @Test
    fun findsAStalledChannel() = runBlocking<Unit> {
        val probe = ChannelHandshake.probe(64)
        val partial = ChannelHandshake.header(1, ChannelHandshake.STATUS_ACCEPTED, 64, ByteArray(0)) + probe.copyOf(20)
        val input = StallingInput(partial)
        val result = ChannelHandshake.run(input, ByteArrayOutputStream(), { input.close() }, Dispatchers.IO, probeLen = 64, stallMs = 200)
        assertThat(result).isEqualTo(ChannelHandshake.Result.Stalled(echoed = 20, expected = 64))
    }
}
