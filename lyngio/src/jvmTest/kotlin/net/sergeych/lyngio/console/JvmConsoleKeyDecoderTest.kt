/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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

package net.sergeych.lyngio.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmConsoleKeyDecoderTest {

    private fun decode(vararg bytes: Int): ConsoleEvent.KeyDown {
        var index = 1
        return JvmConsoleKeyDecoder.decode(bytes[0]) { _ ->
            if (index >= bytes.size) null else bytes[index++]
        }
    }

    @Test
    fun decodesArrowLeft() {
        val ev = decode(27, '['.code, 'D'.code)
        assertEquals("ArrowLeft", ev.key)
        assertFalse(ev.ctrl)
        assertFalse(ev.alt)
        assertFalse(ev.shift)
    }

    @Test
    fun decodesCtrlArrowRightModifier() {
        val ev = decode(27, '['.code, '1'.code, ';'.code, '5'.code, 'C'.code)
        assertEquals("ArrowRight", ev.key)
        assertTrue(ev.ctrl)
        assertFalse(ev.alt)
        assertFalse(ev.shift)
    }

    @Test
    fun decodesAltCharacter() {
        val ev = decode(27, 'x'.code)
        assertEquals("x", ev.key)
        assertTrue(ev.alt)
        assertFalse(ev.ctrl)
    }
}
