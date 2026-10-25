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

package org.connectbot.ui.screens.hosteditor

import android.os.Build
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class BleScanDialogStateTest {
    @Test
    fun startsWithWhatIsMissing() {
        assertThat(initialScanState(hasPermission = false, hasAdapter = true, enabled = true)).isEqualTo(ScanState.NeedsPermission)
        assertThat(initialScanState(hasPermission = true, hasAdapter = false, enabled = false)).isEqualTo(ScanState.Unavailable)
        assertThat(initialScanState(hasPermission = true, hasAdapter = true, enabled = false)).isEqualTo(ScanState.BluetoothOff)
        assertThat(initialScanState(hasPermission = true, hasAdapter = true, enabled = true)).isEqualTo(ScanState.Scanning)
    }

    @Test
    fun locationHintOnlyUpToAndroid11() {
        assertThat(needsLocationHint(Build.VERSION_CODES.R, locationEnabled = false)).isTrue()
        assertThat(needsLocationHint(Build.VERSION_CODES.R, locationEnabled = true)).isFalse()
        assertThat(needsLocationHint(Build.VERSION_CODES.S, locationEnabled = false)).isFalse()
    }
}
