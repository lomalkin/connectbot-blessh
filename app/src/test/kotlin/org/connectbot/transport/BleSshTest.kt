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

package org.connectbot.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BleSshTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun parsesUserAtAddress() {
        val uri = BleSsh.getUri("pi@b8:27:eb:2d:1d:59")!!
        assertThat(uri.scheme).isEqualTo("ble")
        val host = BleSsh().createHost(uri)
        assertThat(host.protocol).isEqualTo("ble")
        assertThat(host.username).isEqualTo("pi")
        assertThat(host.hostname).isEqualTo("B8:27:EB:2D:1D:59")
        assertThat(host.port).isEqualTo(0)
    }

    @Test
    fun acceptsAddressesWithoutSeparators() {
        val host = BleSsh().createHost(BleSsh.getUri("pi@B827EB2D1D59")!!)
        assertThat(host.hostname).isEqualTo("B8:27:EB:2D:1D:59")
    }

    @Test
    fun rejectsNonBluetoothHosts() {
        assertThat(BleSsh.getUri("pi@rns-3.local")).isNull()
        assertThat(BleSsh.getUri("B8:27:EB:2D:1D:59")).isNull()
    }

    @Test
    fun selectsHostsByProtocolAndAddress() {
        val selection = HashMap<String, String>()
        BleSsh().getSelectionArgs(BleSsh.getUri("pi@b8-27-eb-2d-1d-59")!!, selection)
        assertThat(selection).containsEntry("protocol", "ble").containsEntry("hostname", "B8:27:EB:2D:1D:59").containsEntry("username", "pi")
    }
}
