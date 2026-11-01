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

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * The client side of the blessh channel handshake, version 1 (blessh
 * docs/protocol.md, "Channel handshake"). The client sends a hello with a
 * probe; the server replies and echoes the probe. A channel that stops
 * carrying data either way is found here, before SSH starts.
 *
 * ```
 * hello  = "BLSH" version:u8 flags:u8 probe_len:u16 options_len:u16 options probe
 * reply  = "BLSH" version:u8 status:u8 probe_len:u16 options_len:u16 options echo
 * ```
 */
object ChannelHandshake {
    const val VERSION = 1
    const val HEADER_LEN = 10
    const val DEFAULT_PROBE = 2048
    const val MAX_PROBE = 8192
    const val MAX_OPTIONS = 1024
    const val STATUS_ACCEPTED = 0
    const val STATUS_REFUSED = 1
    const val OPT_REASON = 1
    private val MAGIC = byteArrayOf(0x42, 0x4c, 0x53, 0x48) // "BLSH"

    /** Writes and reads go in pieces this size, so progress is visible. */
    private const val STEP = 256

    class ProtocolException(message: String) : IOException(message)

    sealed class Result {
        data class Accepted(val version: Int) : Result()

        /** The server refused the session; [reason] is text for people. */
        data class Refused(val reason: String) : Result()

        /** The channel stopped carrying data for the stall time. */
        data class Stalled(val echoed: Int, val expected: Int) : Result()
    }

    /** The reply's fixed part. */
    data class Header(val version: Int, val status: Int, val probeLen: Int, val optionsLen: Int)

    fun hello(probe: ByteArray): ByteArray = header(VERSION, 0, probe.size, ByteArray(0)) + probe

    fun header(version: Int, flagsOrStatus: Int, probeLen: Int, options: ByteArray): ByteArray = MAGIC + byteArrayOf(
        version.toByte(),
        flagsOrStatus.toByte(),
        probeLen.toByte(),
        (probeLen shr 8).toByte(),
        options.size.toByte(),
        (options.size shr 8).toByte(),
    ) + options

    fun parseHeader(b: ByteArray): Header {
        if (b.size < HEADER_LEN || !b.copyOfRange(0, 4).contentEquals(MAGIC)) throw ProtocolException("not a blessh channel")
        val h = Header(
            version = b[4].toInt() and 0xFF,
            status = b[5].toInt() and 0xFF,
            probeLen = (b[6].toInt() and 0xFF) or ((b[7].toInt() and 0xFF) shl 8),
            optionsLen = (b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8),
        )
        if (h.version == 0 || h.version > VERSION) throw ProtocolException("unsupported handshake version ${h.version}")
        if (h.probeLen > MAX_PROBE || h.optionsLen > MAX_OPTIONS) throw ProtocolException("handshake reply too long")
        return h
    }

    /** The reason option of a reply, if any. Unknown option types are skipped. */
    fun reason(options: ByteArray): String? {
        var i = 0
        var reason: String? = null
        while (i < options.size) {
            if (i + 2 > options.size) throw ProtocolException("truncated handshake option")
            val type = options[i].toInt() and 0xFF
            val len = options[i + 1].toInt() and 0xFF
            if (i + 2 + len > options.size) throw ProtocolException("truncated handshake option")
            if (type == OPT_REASON) reason = options.copyOfRange(i + 2, i + 2 + len).decodeToString()
            i += 2 + len
        }
        return reason
    }

    /** Probe content: any bytes do; these are not all alike, as SSH's are not. */
    fun probe(len: Int): ByteArray {
        var x = 0x2545F491
        return ByteArray(len) {
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            x.toByte()
        }
    }

    /**
     * Runs the handshake on a freshly connected channel. Blocking stream
     * calls run in [io]; if neither a write nor a read makes progress for
     * [stallMs], [abort] is called (closing the channel, which ends the
     * blocked call) and the result is [Result.Stalled].
     */
    suspend fun run(
        input: InputStream,
        output: OutputStream,
        abort: () -> Unit,
        io: CoroutineContext,
        probeLen: Int = DEFAULT_PROBE,
        stallMs: Long = 3_000,
    ): Result = coroutineScope {
        val probe = probe(probeLen)
        // Shared with the watchdog, which runs on another thread.
        val lastProgress = AtomicLong(System.nanoTime())
        val echoed = AtomicInteger(0)
        val stalled = AtomicBoolean(false)
        val progress = { lastProgress.set(System.nanoTime()) }
        val watchdog = launch {
            while (isActive) {
                delay(minOf(100L, stallMs))
                if (System.nanoTime() - lastProgress.get() > stallMs * 1_000_000) {
                    stalled.set(true)
                    abort()
                    break
                }
            }
        }
        try {
            runInterruptible(io) {
                val hello = hello(probe)
                var off = 0
                while (off < hello.size) {
                    val n = minOf(STEP, hello.size - off)
                    output.write(hello, off, n)
                    off += n
                    progress()
                }
                output.flush()
                val header = parseHeader(readFully(input, HEADER_LEN, progress))
                val options = readFully(input, header.optionsLen, progress)
                if (header.status != STATUS_ACCEPTED) {
                    return@runInterruptible Result.Refused(reason(options) ?: "refused")
                }
                reason(options)
                if (header.probeLen != probe.size) throw ProtocolException("server echoes ${header.probeLen} bytes, sent ${probe.size}")
                val echo = ByteArray(probe.size)
                while (echoed.get() < echo.size) {
                    val at = echoed.get()
                    val n = input.read(echo, at, minOf(STEP, echo.size - at))
                    if (n < 0) throw IOException("channel closed during the handshake")
                    echoed.addAndGet(n)
                    progress()
                }
                if (!echo.contentEquals(probe)) throw ProtocolException("the echo does not match the probe")
                Result.Accepted(header.version)
            }
        } catch (e: IOException) {
            if (stalled.get()) Result.Stalled(echoed.get(), probe.size) else throw e
        } finally {
            watchdog.cancel()
        }
    }

    private fun readFully(input: InputStream, len: Int, progress: () -> Unit): ByteArray {
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(b, off, len - off)
            if (n < 0) throw IOException("channel closed during the handshake")
            off += n
            progress()
        }
        return b
    }
}
