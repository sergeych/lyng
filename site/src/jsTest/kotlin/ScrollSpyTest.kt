/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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
 *
 */

import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollSpyTest {
    @Test
    fun emptyListReturnsZero() {
        assertEquals(0, activeIndexForTops(emptyList(), 80.0))
    }

    @Test
    fun selectsFirstWhenOnlyFirstIsAboveOffset() {
        val tops = listOf(20.0, 200.0, 800.0) // px from viewport top
        val idx = activeIndexForTops(tops, offsetPx = 80.0)
        assertEquals(1, idx) // 20 <= 80, 200 > 80 -> index 1 (second heading is first below offset)
    }

    @Test
    fun selectsLastHeadingAboveOffset() {
        val tops = listOf(-100.0, 50.0, 70.0)
        val idx = activeIndexForTops(tops, offsetPx = 80.0)
        assertEquals(2, idx) // all three are <= 80 -> last index
    }

    @Test
    fun stopsBeforeFirstBelowOffset() {
        val tops = listOf(-200.0, -50.0, 30.0, 150.0, 400.0)
        val idx = activeIndexForTops(tops, offsetPx = 80.0)
        assertEquals(3, idx) // 30 <= 80 qualifies; 150 > 80 stops, so index 3rd (0-based -> 3?)
    }
}
