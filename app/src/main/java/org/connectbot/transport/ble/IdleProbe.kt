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

/**
 * Decides when to check that a quiet BleStream session is still alive.
 *
 * A server whose daemon went away may leave the Bluetooth link up, and
 * writes without response cannot fail, so silence alone proves nothing.
 * After [intervalMs] without anything received (or since the last probe),
 * the transport writes a probe with response; an error or a timeout of that
 * write means the session is gone.
 */
class IdleProbe(private val intervalMs: Long, now: Long) {
    private var last = now

    /** Something arrived from the server. */
    fun received(now: Long) {
        last = now
    }

    /** Returns true if a probe is due now, and counts it as sent. */
    fun due(now: Long): Boolean {
        if (now - last < intervalMs) return false
        last = now
        return true
    }
}
