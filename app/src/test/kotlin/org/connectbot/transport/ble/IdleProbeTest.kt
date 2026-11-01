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
import org.junit.Test

class IdleProbeTest {
    @Test
    fun probesOnlyAfterSilence() {
        val p = IdleProbe(20_000, now = 0)
        assertThat(p.due(19_999)).isFalse()
        assertThat(p.due(20_000)).isTrue()
        // The next probe comes one interval after the previous one.
        assertThat(p.due(30_000)).isFalse()
        assertThat(p.due(40_000)).isTrue()
    }

    @Test
    fun trafficPostponesTheProbe() {
        val p = IdleProbe(20_000, now = 0)
        p.received(15_000)
        assertThat(p.due(20_000)).isFalse()
        assertThat(p.due(35_000)).isTrue()
    }
}
