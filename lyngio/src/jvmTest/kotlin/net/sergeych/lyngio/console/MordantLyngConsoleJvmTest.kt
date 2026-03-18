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

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MordantLyngConsoleJvmTest {

    @Test
    fun basicCapabilitiesSmoke() = runBlocking {
        val console = getSystemConsole()
        assertNotNull(console)

        // Must be callable in any environment (interactive or redirected output).
        val tty = console.isTty()
        val ansi = console.ansiLevel()
        val geometry = console.geometry()

        if (geometry != null) {
            assertTrue(geometry.columns > 0, "columns must be positive when geometry is present")
            assertTrue(geometry.rows > 0, "rows must be positive when geometry is present")
        }

        // no-op smoke checks
        console.write("")
        console.flush()

        // Keep values live so compiler doesn't optimize away calls in future changes
        assertNotNull(ansi)
        assertTrue(tty || !tty)
    }

    @Test
    fun setRawModeContract() = runBlocking {
        val console = getSystemConsole()
        val enabledChanged = console.setRawMode(true)
        val disabledChanged = console.setRawMode(false)

        // If enabling changed state, disabling should also change it back.
        if (enabledChanged) {
            assertTrue(disabledChanged, "raw mode disable should report changed after enable")
        }
    }

    @Test
    fun eventsSourceDoesNotCrash() = runBlocking {
        val console = getSystemConsole()
        val source = console.events()
        val event = source.nextEvent(350)
        source.close()
        // Any event kind is acceptable in this smoke test; null is also valid when idle.
        if (event != null) {
            assertTrue(
                event is ConsoleEvent.Resize || event is ConsoleEvent.KeyDown || event is ConsoleEvent.KeyUp,
                "unexpected event type: ${event::class.simpleName}"
            )
        }
    }
}
