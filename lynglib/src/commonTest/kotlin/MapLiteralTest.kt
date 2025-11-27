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
 * Map literal and merging tests
 */

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.eval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MapLiteralTest {

    @Test
    fun basicStringAndIdKeysAndShorthand() = runTest {
        eval(
            """
            val x = 2
            val y = 2
            val m = { "a": 1, x: x*10, y: }
            assertEquals(1, m["a"])
            assertEquals(20, m["x"])
            assertEquals(2, m["y"])
            """.trimIndent()
        )
    }

    @Test
    fun spreadsAndOverwriteOrder() = runTest {
        eval(
            """
            val base = { a: 1, b: 2 }
            val m = { a: 0, ...base, b: 3, c: 4 }
            assertEquals(1, m["a"])  // base overwrites a:0
            assertEquals(3, m["b"])  // literal overwrites spread
            assertEquals(4, m["c"])  // new key
            """.trimIndent()
        )
    }

    @Test
    fun trailingCommaAccepted() = runTest {
        eval(
            """
            val m = { "a": 1, b: 2, }
            assertEquals(1, m["a"])
            assertEquals(2, m["b"])
            """.trimIndent()
        )
    }

    @Test
    fun duplicateLiteralKeysAreCompileTimeError() = runTest {
        assertFailsWith<ScriptError> {
            eval("""{ foo: 1, " + '"' + "foo" + '"' + ": 2 }""".trimIndent())
        }
        assertFailsWith<ScriptError> {
            eval("""{ foo:, foo: 2 }""".trimIndent())
        }
    }

    @Test
    fun lambdaDisambiguationWithTypedArgs() = runTest {
        eval(
            """
            val f = { x: Int, y: Int -> x + y }
            assertEquals(3, f(1,2))
            """.trimIndent()
        )
    }

    @Test
    fun plusMergingAndPlusAssign() = runTest {
        eval(
            """
            val m1 = ("1" => 10) + ("2" => 20)
            assertEquals(10, m1["1"])
            assertEquals(20, m1["2"])

            val m2 = { "1": 10 } + ("2" => 20)
            assertEquals(10, m2["1"])
            assertEquals(20, m2["2"])

            val m3 = { "1": 10 } + { "2": 20 }
            assertEquals(10, m3["1"])
            assertEquals(20, m3["2"])

            var m = { "a": 1 }
            m += ("b" => 2)
            assertEquals(1, m["a"])
            assertEquals(2, m["b"])
            """.trimIndent()
        )
    }

    @Test
    fun spreadNonMapIsRuntimeError() = runTest {
        assertFailsWith<ExecutionError> {
            eval("""{ ...[1,2,3] }""")
        }
    }

    @Test
    fun spreadNonStringKeysIsRuntimeError() = runTest {
        assertFailsWith<ExecutionError> {
            eval("""{ ...Map(1 => "x") }""")
        }
    }

    @Test
    fun mergeNonStringKeyIsRuntimeError() = runTest {
        assertFailsWith<ExecutionError> {
            eval("""
                val e = (1 => "x")
                { "a": 1 } + e
            """)
        }
    }

    @Test
    fun shorthandUndefinedIdIsError() = runTest {
        assertFailsWith<ExecutionError> {
            eval("""{ z: }""")
        }
    }
}
