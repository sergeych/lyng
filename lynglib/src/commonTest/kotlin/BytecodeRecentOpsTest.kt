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
import kotlin.test.Test

class BytecodeRecentOpsTest {

    @Test
    fun listLiteralWithSpread() = runTest {
        eval(
            """
            val a = [1, 2, 3]
            val b = [0, ...a, 4]
            assertEquals(5, b.size)
            assertEquals(0, b[0])
            assertEquals(1, b[1])
            assertEquals(4, b[4])
            """.trimIndent()
        )
    }

    @Test
    fun valueFnRefViaClassOperator() = runTest {
        eval(
            """
            val c = 1::class
            assertEquals("Int", c.className)
            """.trimIndent()
        )
    }

    @Test
    fun implicitThisCompoundAssign() = runTest {
        eval(
            """
            class C {
                var x: Int = 1
                fun add(n: Int) { x += n }
                fun calc() { add(2); x }
            }
            val c = C()
            assertEquals(3, c.calc())
            """.trimIndent()
        )
    }

    @Test
    fun optionalCompoundAssignEvaluatesRhsOnce() = runTest {
        eval(
            """
            var count = 0
            fun inc() { count = count + 1; return 3 }
            class Box(var v)
            var b = Box(1)
            b?.v += inc()
            assertEquals(4, b.v)
            assertEquals(1, count)
            """.trimIndent()
        )
    }

    @Test
    fun optionalIndexCompoundAssignEvaluatesRhsOnce() = runTest {
        eval(
            """
            var count = 0
            fun inc() { count = count + 1; return 2 }
            var a = [1, 2, 3]
            a?[1] += inc()
            assertEquals(4, a[1])
            assertEquals(1, count)
            """.trimIndent()
        )
    }

    @Test
    fun optionalIndexIncDecSkipsOnNullReceiver() = runTest {
        eval(
            """
            var count = 0
            fun idx() { count = count + 1; return 1 }
            var a: List<Int>? = null
            val r = a?[idx()]++
            assertEquals(null, r)
            assertEquals(0, count)
            """.trimIndent()
        )
    }

    @Test
    fun optionalIndexIncDecUpdatesOnNonNullReceiver() = runTest {
        eval(
            """
            var a = [1, 2, 3]
            val r = a?[1]++
            assertEquals(2, r)
            assertEquals(3, a[1])
            """.trimIndent()
        )
    }

    @Test
    fun optionalIndexPreIncSkipsOnNullReceiver() = runTest {
        eval(
            """
            var count = 0
            fun idx() { count = count + 1; return 1 }
            var a: List<Int>? = null
            val r = ++a?[idx()]
            assertEquals(null, r)
            assertEquals(0, count)
            """.trimIndent()
        )
    }

    @Test
    fun optionalClassScopeIncDec() = runTest {
        eval(
            """
            class C { static var x = 1 }
            val r = C?.x++
            assertEquals(1, r)
            assertEquals(2, C.x)
            """.trimIndent()
        )
    }
}
