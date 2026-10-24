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

import java.util.ArrayDeque
import java.util.UUID

/**
 * BleStream v1 framing (see ble-term docs/protocol.md): one reliable byte
 * stream per direction over GATT, with credit based flow control and frame
 * reordering. Not thread safe; callers serialize access.
 */
class BleStreamLink(maxFrame: Int, private val recvWindow: Int) {
    sealed class Event {
        data class Open(val window: Long) : Event()

        class Data(val bytes: ByteArray) : Event()

        data class Closed(val reason: Int, val message: String) : Event()
    }

    class ProtocolException(message: String) : Exception(message)

    val maxFrame: Int = maxFrame.coerceIn(MIN_FRAME, MAX_FRAME)

    private var txSeq = 0
    private var rxSeq = 0
    private val reorder = HashMap<Int, ByteArray>()
    private var sendCredit = 0L
    private var recvAllowance = recvWindow.toLong()
    private var consumed = 0L
    private val control = ArrayDeque<ByteArray>()
    private val outChunks = ArrayDeque<ByteArray>()
    private var outHeadOffset = 0
    private var outSize = 0

    var isOpen = false
        private set
    var isClosing = false
        private set

    init {
        control.add(helloPayload(recvWindow))
    }

    /** Bytes queued for sending but not yet framed. */
    fun pendingOut(): Int = outSize

    fun send(data: ByteArray) {
        if (isClosing || data.isEmpty()) return
        outChunks.add(data.copyOf())
        outSize += data.size
    }

    fun close(reason: Int, message: String) {
        if (isClosing) return
        isClosing = true
        outChunks.clear()
        outHeadOffset = 0
        outSize = 0
        control.add(byteArrayOf(T_CLOSE.toByte(), reason.toByte()) + truncateUtf8(message, maxFrame - HEADER_LEN - 1))
    }

    /** Reports that [n] received bytes were handed to the application. */
    fun consumed(n: Int) {
        consumed += n
        val threshold = maxOf(recvWindow / 4, 1)
        if (consumed >= threshold && !isClosing) {
            val credit = consumed
            consumed = 0
            recvAllowance += credit
            control.add(byteArrayOf(T_CREDIT.toByte()) + le32(credit))
        }
    }

    /** Returns the next frame to transmit, or null if nothing can be sent now. */
    fun pollTransmit(): ByteArray? {
        val body: ByteArray = control.pollFirst() ?: run {
            val pending = pendingOut()
            if (!isOpen || isClosing || pending == 0 || sendCredit <= 0) return null
            val n = minOf(pending, this.maxFrame - HEADER_LEN, sendCredit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            sendCredit -= n
            byteArrayOf(T_DATA.toByte()) + takeOutgoing(n)
        }
        val seq = txSeq
        txSeq = (txSeq + 1) and 0xffff
        val frame = ByteArray(body.size + 2)
        frame[0] = body[0]
        frame[1] = (seq and 0xff).toByte()
        frame[2] = (seq ushr 8).toByte()
        System.arraycopy(body, 1, frame, 3, body.size - 1)
        return frame
    }

    /** Processes one received packet and returns the resulting events in stream order. */
    fun onPacket(pkt: ByteArray): List<Event> {
        if (pkt.size < HEADER_LEN) throw ProtocolException("frame shorter than header")
        val seq = (pkt[1].toInt() and 0xff) or ((pkt[2].toInt() and 0xff) shl 8)
        val events = ArrayList<Event>()
        if (seq != rxSeq) {
            val ahead = (seq - rxSeq) and 0xffff
            if (ahead == 0 || ahead > REORDER_LIMIT || reorder.containsKey(seq)) {
                throw ProtocolException("sequence error: expected $rxSeq, got $seq")
            }
            reorder[seq] = pkt.copyOf()
            return events
        }
        rxSeq = (rxSeq + 1) and 0xffff
        handle(pkt, events)
        while (true) {
            val next = reorder.remove(rxSeq) ?: break
            rxSeq = (rxSeq + 1) and 0xffff
            handle(next, events)
        }
        return events
    }

    private fun handle(pkt: ByteArray, events: MutableList<Event>) {
        val type = pkt[0].toInt() and 0xff
        val p = pkt.copyOfRange(HEADER_LEN, pkt.size)
        when (type) {
            T_HELLO -> {
                if (p.size < 10 || !p.copyOfRange(0, 4).contentEquals(MAGIC)) throw ProtocolException("malformed HELLO")
                if (isOpen) throw ProtocolException("duplicate HELLO")
                val version = p[4].toInt() and 0xff
                if (version != VERSION) throw ProtocolException("unsupported protocol version $version")
                val window = readLe32(p, 6)
                isOpen = true
                sendCredit = window
                events.add(Event.Open(window))
            }

            T_CLOSE -> {
                val reason = if (p.isNotEmpty()) p[0].toInt() and 0xff else CLOSE_NORMAL
                val message = if (p.size > 1) String(p, 1, p.size - 1, Charsets.UTF_8) else ""
                events.add(Event.Closed(reason, message))
            }

            T_DATA -> {
                if (!isOpen) throw ProtocolException("frame received before HELLO")
                if (p.size > recvAllowance) throw ProtocolException("peer exceeded the receive window")
                recvAllowance -= p.size
                if (p.isNotEmpty()) events.add(Event.Data(p))
            }

            T_CREDIT -> {
                if (!isOpen) throw ProtocolException("frame received before HELLO")
                if (p.size != 4) throw ProtocolException("malformed CREDIT")
                sendCredit += readLe32(p, 0)
            }

            else -> throw ProtocolException("unknown frame type $type")
        }
    }

    /** UTF-8 bytes of [text], cut at a character boundary to at most [max] bytes. */
    private fun truncateUtf8(text: String, max: Int): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= max) return bytes
        var end = max
        while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return bytes.copyOf(end)
    }

    private fun takeOutgoing(n: Int): ByteArray {
        val out = ByteArray(n)
        var filled = 0
        while (filled < n) {
            val head = outChunks.first
            val k = minOf(head.size - outHeadOffset, n - filled)
            System.arraycopy(head, outHeadOffset, out, filled, k)
            filled += k
            outHeadOffset += k
            if (outHeadOffset == head.size) {
                outChunks.removeFirst()
                outHeadOffset = 0
            }
        }
        outSize -= n
        return out
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("5b1e7e00-6d2c-4f4a-9a3e-2c1b0e8f4a10")
        val INFO_UUID: UUID = UUID.fromString("5b1e7e01-6d2c-4f4a-9a3e-2c1b0e8f4a10")
        val RX_UUID: UUID = UUID.fromString("5b1e7e02-6d2c-4f4a-9a3e-2c1b0e8f4a10")
        val TX_UUID: UUID = UUID.fromString("5b1e7e03-6d2c-4f4a-9a3e-2c1b0e8f4a10")

        const val VERSION = 1
        const val HEADER_LEN = 3
        const val MIN_FRAME = 20
        const val MAX_FRAME = 500
        const val REORDER_LIMIT = 64

        const val T_HELLO = 0x01
        const val T_DATA = 0x02
        const val T_CREDIT = 0x03
        const val T_CLOSE = 0x04

        const val CLOSE_NORMAL = 0
        const val CLOSE_BUSY = 1
        const val CLOSE_PROTOCOL_ERROR = 2
        const val CLOSE_SESSION_UNAVAILABLE = 3
        const val CLOSE_TIMEOUT = 4

        private val MAGIC = "BLTM".toByteArray(Charsets.US_ASCII)

        private fun helloPayload(window: Int): ByteArray = byteArrayOf(T_HELLO.toByte()) + MAGIC + byteArrayOf(VERSION.toByte(), 0) + le32(window.toLong())

        private fun le32(v: Long): ByteArray = byteArrayOf(
            (v and 0xff).toByte(),
            ((v ushr 8) and 0xff).toByte(),
            ((v ushr 16) and 0xff).toByte(),
            ((v ushr 24) and 0xff).toByte(),
        )

        private fun readLe32(b: ByteArray, off: Int): Long = (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)
    }
}
