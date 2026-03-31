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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.eval
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CalculusDiagnosticRegressionTest {

    @Test
    fun undefinedHInCalculusSnippetReportsPreciseDiagnostic() = runTest {
        val ex = assertFailsWith<ScriptError> {
            eval(
                """
                var x = 7.0
                // глубина по звуку падения
                val m = 1 // kg
                val d = 0.06 // 6 cm
                val c = 340 // скор. звука
                val g = 9.82
                var cnt = 0
                var t = x
                var message = ""
                val hinv = 1/h
                var h = c*c/g*(1 + g*t/c -sqrt(1+2*g*t/c))
                assert(h is Real)
                assert(!h.isNaN())
                """.trimIndent()
            )
        }

        assertEquals(9, ex.pos.line)
        assertEquals(13, ex.pos.column)
        assertContains(ex.errorMessage, "symbol 'h' is not defined")
        assertContains(ex.message ?: "", "val hinv = 1/h")
    }
}
