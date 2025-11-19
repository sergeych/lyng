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

/*
 * Verify that Source/Pos → absolute offset mapping is consistent and preserves
 * exact characters, including trailing spaces and Windows CRLF endings.
 */
package net.sergeych.lyng.highlight

import net.sergeych.lyng.Pos
import net.sergeych.lyng.Source
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceOffsetTest {

    @Test
    fun preservesTrailingSpacesInColumns() {
        val txt = "abc  \nxyz" // two trailing spaces before the newline
        val src = Source("snippet", txt)
        // line 0: "abc  " length 5
        val p = Pos(src, 0, 4) // column at the last space
        val off = src.offsetOf(p)
        assertEquals(4, off)
        // Take substring from start to this pos, it must include two spaces
        assertEquals("abc ", txt.substring(0, off))
    }

    @Test
    fun crlfLineEndingsDoNotBreakOffsets() {
        val txt = "a\r\nb\r\nc" // three lines, split by \n; \r remain at line ends
        val src = Source("snippet", txt)
        // Position at start of line 2 ('c')
        val p = Pos(src, 2, 0)
        val off = src.offsetOf(p)
        // Offsets: line0 len=2 ("a\r"), plus one for \n, line1 len=2 ("b\r"), plus one for \n => 2+1+2+1=6
        assertEquals(6, off)
        assertEquals('c', txt[off])
    }
}
