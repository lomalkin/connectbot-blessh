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
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PairingWatchTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun bondState(address: String, state: Int) {
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        context.sendBroadcast(
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, state),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun noticesAPairingRequestFromTheServer() {
        val watch = PairingWatch(context, "B8:27:EB:2D:1D:59")
        assertThat(watch.requested).isFalse()
        bondState("B8:27:EB:2D:1D:59", BluetoothDevice.BOND_BONDING)
        assertThat(watch.requested).isTrue()
        assertThat(watch.message).contains("Channels")
        watch.stop()
    }

    @Test
    fun ignoresOtherDevicesAndStates() {
        val watch = PairingWatch(context, "B8:27:EB:2D:1D:59")
        bondState("AA:BB:CC:DD:EE:FF", BluetoothDevice.BOND_BONDING)
        bondState("B8:27:EB:2D:1D:59", BluetoothDevice.BOND_NONE)
        assertThat(watch.requested).isFalse()
        watch.stop()
    }

    @Test
    fun stopsWatching() {
        val watch = PairingWatch(context, "B8:27:EB:2D:1D:59")
        watch.stop()
        watch.stop()
        bondState("B8:27:EB:2D:1D:59", BluetoothDevice.BOND_BONDING)
        assertThat(watch.requested).isFalse()
    }
}
