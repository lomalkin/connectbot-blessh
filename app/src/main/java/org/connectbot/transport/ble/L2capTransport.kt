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

import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportException
import timber.log.Timber
import java.io.IOException

/** A transport to a blessh server that can say why its link ended. */
interface BlesshTransport : Transport {
    /** Why the link failed, for the terminal; null if it was simply closed here. */
    val failureMessage: String?
}

/**
 * sshlib [Transport] over an L2CAP connection-oriented channel to a blessh
 * server, after the channel handshake: the channel carries the SSH
 * connection as it is (blessh docs/protocol.md, "L2CAP channel"). The GATT
 * connection that read INFO stays open with it and is closed together.
 */
class L2capTransport internal constructor(
    private val socket: BluetoothSocket,
    private val gatt: BleStreamTransport.GattClient,
    private val io: CoroutineDispatcher,
) : BlesshTransport {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val readLock = Mutex()
    private val writeLock = Mutex()

    @Volatile
    private var closed = false

    @Volatile
    private var failure: String? = null

    override val isConnected: Boolean
        get() = !closed && failure == null

    override val failureMessage: String?
        get() = failure

    private fun lost(e: IOException): TransportException {
        if (!closed && failure == null) failure = "Bluetooth connection lost (${e.message})"
        return TransportException(failure ?: "Bluetooth LE channel closed", e)
    }

    override suspend fun read(count: Int): ByteArray = readLock.withLock {
        if (closed) throw TransportException("Bluetooth LE channel closed")
        try {
            runInterruptible(io) {
                val b = ByteArray(count)
                var off = 0
                while (off < count) {
                    val n = input.read(b, off, count - off)
                    if (n < 0) throw IOException("the server closed the channel")
                    off += n
                }
                b
            }
        } catch (e: IOException) {
            throw lost(e)
        }
    }

    override suspend fun write(data: ByteArray) = writeLock.withLock {
        if (closed) throw TransportException("Bluetooth LE channel closed")
        try {
            runInterruptible(io) {
                output.write(data)
                output.flush()
            }
        } catch (e: IOException) {
            throw lost(e)
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            socket.close()
        } catch (e: IOException) {
            Timber.d(e, "Closing the L2CAP channel failed")
        }
        gatt.close()
    }
}
