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

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import org.connectbot.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notices when the server at [address] asks the phone to pair. ble-term
 * never pairs; a server whose Bluetooth has EATT on asks for it, during
 * connection setup or later in the session, and the phone then drops the
 * link. Watches until [stop].
 */
internal class PairingWatch(private val context: Context, private val address: String) {
    private val seen = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            if (device?.address == address && state == BluetoothDevice.BOND_BONDING) seen.set(true)
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }

    val requested: Boolean
        get() = seen.get()

    /** Error text for a link that dropped after a pairing request. */
    val message: String
        get() = context.getString(R.string.ble_error_pairing_requested)

    fun stop() {
        if (stopped.compareAndSet(false, true)) context.unregisterReceiver(receiver)
    }
}
