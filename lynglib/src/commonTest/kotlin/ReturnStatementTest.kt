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
import net.sergeych.lyng.obj.toInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReturnStatementTest {

    @Test
    fun testBasicReturn() = runTest {
        assertEquals(10, eval("""
            fun foo() {
                return 10
                20
            }
            foo()
        """).toInt())
    }

    @Test
    fun testReturnFromIf() = runTest {
        assertEquals(5, eval("""
            fun foo(x: Int) {
                if (x > 0) return 5
                10
            }
            foo(1)
        """).toInt())
        
        assertEquals(10, eval("""
            fun foo(x: Int) {
                if (x > 0) return 5
                10
            }
            foo(-1)
        """).toInt())
    }

    @Test
    fun testReturnFromLambda() = runTest {
        assertEquals(2, eval("""
            val f = { x: Int ->
                if (x < 0) return 0
                x * 2
            }
            f(1)
        """).toInt())
        
        assertEquals(0, eval("""
            val f = { x: Int ->
                if (x < 0) return 0
                x * 2
            }
            f(-1)
        """).toInt())
    }

    @Test
    fun testNonLocalReturn() = runTest {
        assertEquals(100, eval("""
            fun outer() {
                [1, 2, 3].forEach {
                    if (it == 2) return@outer 100
                }
                0
            }
            outer()
        """).toInt())
    }

    @Test
    fun testLabeledLambdaReturn() = runTest {
        assertEquals(42, eval("""
            val f = @inner { x: Int ->
                if (x == 0) return@inner 42
                x
            }
            f(0)
        """).toInt())
        
        assertEquals(5, eval("""
            val f = @inner { x: Int ->
                if (x == 0) return@inner 42
                x
            }
            f(5)
        """).toInt())
    }

    @Test
    fun testForbidEqualReturn() = runTest {
        assertFailsWith<ScriptError> {
            eval("fun foo(x) = return x")
        }
    }

    @Test
    fun testDeepNestedReturn() = runTest {
        assertEquals(42, eval("""
            fun find() {
                val data = [[1, 2], [3, 42], [5, 6]]
                data.forEach { row ->
                    val rowList = row as List
                    rowList.forEach { item ->
                        val value = item as Int
                        if (value == 42) return@find value
                    }
                }
                0
            }
            find()
        """).toInt())
    }

    @Test
    fun testReturnFromOuterLambda() = runTest {
        assertEquals("found", eval("""
            val f_outer = @outer {
                val f_inner = {
                    return@outer "found"
                }
                f_inner()
                "not found"
            }
            f_outer()
        """).toString())
    }
}
