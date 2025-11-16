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

package net.sergeych.lyng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerfProfilesTest {

    @Test
    fun apply_and_restore_presets() {
        val before = PerfProfiles.snapshot()

        try {
            // BENCH preset expectations
            val snapAfterBench = PerfProfiles.apply(PerfProfiles.Preset.BENCH)
            // Expect some key flags ON for benches
            assertTrue(PerfFlags.ARG_BUILDER)
            assertTrue(PerfFlags.SCOPE_POOL)
            assertTrue(PerfFlags.FIELD_PIC)
            assertTrue(PerfFlags.METHOD_PIC)
            assertTrue(PerfFlags.INDEX_PIC)
            assertTrue(PerfFlags.INDEX_PIC_SIZE_4)
            assertTrue(PerfFlags.PRIMITIVE_FASTOPS)
            assertTrue(PerfFlags.RVAL_FASTPATH)
            // Restore via snapshot returned by apply
            PerfProfiles.restore(snapAfterBench)

            // BOOKS preset expectations
            val snapAfterBooks = PerfProfiles.apply(PerfProfiles.Preset.BOOKS)
            // Expect simpler paths enabled/disabled accordingly
            assertEquals(false, PerfFlags.ARG_BUILDER)
            assertEquals(false, PerfFlags.SCOPE_POOL)
            assertEquals(false, PerfFlags.FIELD_PIC)
            assertEquals(false, PerfFlags.METHOD_PIC)
            assertEquals(false, PerfFlags.INDEX_PIC)
            assertEquals(false, PerfFlags.INDEX_PIC_SIZE_4)
            assertEquals(false, PerfFlags.PRIMITIVE_FASTOPS)
            assertEquals(false, PerfFlags.RVAL_FASTPATH)
            // Restore via snapshot returned by apply
            PerfProfiles.restore(snapAfterBooks)

            // BASELINE preset should match PerfDefaults
            val snapAfterBaseline = PerfProfiles.apply(PerfProfiles.Preset.BASELINE)
            assertEquals(PerfDefaults.ARG_BUILDER, PerfFlags.ARG_BUILDER)
            assertEquals(PerfDefaults.SCOPE_POOL, PerfFlags.SCOPE_POOL)
            assertEquals(PerfDefaults.FIELD_PIC, PerfFlags.FIELD_PIC)
            assertEquals(PerfDefaults.METHOD_PIC, PerfFlags.METHOD_PIC)
            assertEquals(PerfDefaults.INDEX_PIC_SIZE_4, PerfFlags.INDEX_PIC_SIZE_4)
            assertEquals(PerfDefaults.PRIMITIVE_FASTOPS, PerfFlags.PRIMITIVE_FASTOPS)
            assertEquals(PerfDefaults.RVAL_FASTPATH, PerfFlags.RVAL_FASTPATH)
            // Restore baseline snapshot
            PerfProfiles.restore(snapAfterBaseline)

        } finally {
            // Finally, restore very original snapshot
            PerfProfiles.restore(before)
        }
    }
}
