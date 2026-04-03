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

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxPosixLyngConsoleTest {

    private fun decode(vararg bytes: Int): ConsoleEvent.KeyDown {
        var i = 1
        return LinuxConsoleKeyDecoder.decode(bytes[0]) { _ ->
            if (i >= bytes.size) null else bytes[i++]
        }
    }

    @Test
    fun decodesArrowLeft() {
        val ev = decode(27, '['.code, 'D'.code)
        assertEquals("ArrowLeft", ev.key)
        assertFalse(ev.ctrl)
    }

    @Test
    fun decodesArrowRightCtrlModifier() {
        val ev = decode(27, '['.code, '1'.code, ';'.code, '5'.code, 'C'.code)
        assertEquals("ArrowRight", ev.key)
        assertTrue(ev.ctrl)
    }

    @Test
    fun decodesEscape() {
        val ev = decode(27)
        assertEquals("Escape", ev.key)
    }

    @Test
    fun decodesCtrlC() {
        val ev = decode(3)
        assertEquals("c", ev.key)
        assertTrue(ev.ctrl)
    }

    @Test
    fun decodesUppercaseShift() {
        val ev = decode('A'.code)
        assertEquals("A", ev.key)
        assertTrue(ev.shift)
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun configuresRawModeReadSemantics() = memScoped {
        val attrs = alloc<termios>()
        attrs.c_iflag =
            BRKINT.convert<UInt>() or
                ICRNL.convert<UInt>() or
                INPCK.convert<UInt>() or
                ISTRIP.convert<UInt>() or
                IXON.convert<UInt>()
        attrs.c_oflag = OPOST.convert<UInt>()
        attrs.c_cflag = 0u
        attrs.c_lflag =
            ECHO.convert<UInt>() or
                ICANON.convert<UInt>() or
                IEXTEN.convert<UInt>() or
                ISIG.convert<UInt>()
        attrs.c_cc[VMIN] = 9u
        attrs.c_cc[VTIME] = 9u

        configureRawInput(attrs)

        assertEquals(0u, attrs.c_iflag and BRKINT.convert<UInt>())
        assertEquals(0u, attrs.c_iflag and ICRNL.convert<UInt>())
        assertEquals(0u, attrs.c_iflag and INPCK.convert<UInt>())
        assertEquals(0u, attrs.c_iflag and ISTRIP.convert<UInt>())
        assertEquals(0u, attrs.c_iflag and IXON.convert<UInt>())
        assertEquals(0u, attrs.c_oflag and OPOST.convert<UInt>())
        assertEquals(CS8.convert<UInt>(), attrs.c_cflag and CS8.convert<UInt>())
        assertEquals(0u, attrs.c_lflag and ECHO.convert<UInt>())
        assertEquals(0u, attrs.c_lflag and ICANON.convert<UInt>())
        assertEquals(0u, attrs.c_lflag and IEXTEN.convert<UInt>())
        assertEquals(0u, attrs.c_lflag and ISIG.convert<UInt>())
        assertEquals(0u, attrs.c_cc[VMIN].toUInt())
        assertEquals(1u, attrs.c_cc[VTIME].toUInt())
    }
}
