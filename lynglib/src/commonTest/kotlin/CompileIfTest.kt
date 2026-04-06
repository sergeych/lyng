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
import net.sergeych.lyng.eval
import net.sergeych.lyng.obj.toInt
import kotlin.test.Test
import kotlin.test.assertEquals

class CompileIfTest {

    @Test
    fun falseBranchSkipsMissingSymbols() = runTest {
        val result = eval(
            """
            var x = 0
            compile if (defined(NoSuchClass)) {
                x = NoSuchClass("foo")
            } else {
                x = 42
            }
            x
            """.trimIndent()
        )
        assertEquals(42, result.toInt())
    }

    @Test
    fun logicExpressionsWorkForDefinedChecks() = runTest {
        val result = eval(
            """
            var x = 0
            compile if (defined(String) && !defined(NoSuchClass)) {
                x = 7
            } else {
                x = NoSuchClass("foo")
            }
            x
            """.trimIndent()
        )
        assertEquals(7, result.toInt())
    }

    @Test
    fun packageChecksWorkInCompileIf() = runTest {
        val result = eval(
            """
            var x = 0
            compile if (defined(lyng.stdlib) && !defined(no.such.pkg)) {
                x = 1
            } else {
                x = 2
            }
            x
            """.trimIndent()
        )
        assertEquals(1, result.toInt())
    }

    @Test
    fun singleStatementBranchesWork() = runTest {
        val result = eval(
            """
            var x = 0
            compile if (defined(String))
                x = 9
            else
                x = NoSuchClass("foo")
            x
            """.trimIndent()
        )
        assertEquals(9, result.toInt())
    }

    @Test
    fun nestedElseBranchIsSkippedAsSingleStatement() = runTest {
        val result = eval(
            """
            var x = 0
            compile if (defined(NoSuchClass))
                if (true)
                    x = NoSuchClass("foo")
                else
                    x = 1
            else
                x = 5
            x
            """.trimIndent()
        )
        assertEquals(5, result.toInt())
    }
}
