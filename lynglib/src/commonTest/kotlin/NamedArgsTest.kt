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
 * Named arguments and named splats test suite
 */

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.eval
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertFailsWith

@Ignore("TODO(bytecode-only): uses fallback")
class NamedArgsTest {

    @Test
    fun basicNamedArgsAndDefaults() = runTest {
        eval(
            """
            fun test(a="foo", b="bar", c="bazz") { [a, b, c] }
            assertEquals( ["foo", "b", "bazz"], test(b: "b") )
            assertEquals( ["a", "bar", "c"], test("a", c: "c") )
            """.trimIndent()
        )
    }

    @Test
    fun positionalAfterNamedIsError() = runTest {
        assertFailsWith<ExecutionError> {
            eval(
                """
                fun f(a, b) { [a,b] }
                f(a: 1, 2)
                """.trimIndent()
            )
        }
    }

    @Test
    fun namedSplatsBasic() = runTest {
        eval(
            """
            fun test(a="a", b="b", c="c", d="d") { [a, b, c, d] }
            val r = test("A?", ...Map("d" => "D!", "b" => "B!"))
            assertEquals(["A?","B!","c","D!"], r)
            """.trimIndent()
        )
    }

    @Test
    fun namedSplatsNonStringKeysError() = runTest {
        assertFailsWith<ExecutionError> {
            eval(
                """
                fun test(a,b) {}
                test(1, ...Map(1 => "x"))
                """.trimIndent()
            )
        }
    }

    @Test
    fun trailingBlockConflictWhenLastNamed() = runTest {
        // Error: last parameter already assigned by a named argument; trailing block must be rejected
        assertFailsWith<ExecutionError> {
            eval(
                """
                fun f(x, onDone) { onDone(x) }
                // Name the last parameter inside parentheses, then try to pass a trailing block
                f(1, onDone: { it }) { 42 }
                """.trimIndent()
            )
        }
        // Normal case still works when last parameter is not assigned by name
        eval(
            """
            fun f(x, onDone) { onDone(x) }
            var res = 0
            f(1) { it -> res = it }
            assertEquals(1, res)
            """.trimIndent()
        )
    }

    @Test
    fun duplicateNamedIsError() = runTest {
        assertFailsWith<ExecutionError> {
            eval(
                """
                fun f(a,b,c) {}
                f(a: 1, a: 2)
                """.trimIndent()
            )
        }
        assertFailsWith<ExecutionError> {
            eval(
                """
                fun f(a,b,c) {}
                f(a: 1, ...Map("a" => 2))
                """.trimIndent()
            )
        }
    }

    @Test
    fun unknownParameterIsError() = runTest {
        assertFailsWith<ExecutionError> {
            eval(
                """
                fun f(a,b) {}
                f(z: 1)
                """.trimIndent()
            )
        }
    }

    @Test
    fun ellipsisCannotBeNamed() = runTest {
        assertFailsWith<ExecutionError> {
            eval(
                """
                fun g(args..., tail) {}
                g(args: [1], tail: 2)
                """.trimIndent()
            )
        }
    }

    @Test
    fun positionalSplatAfterNamedIsError() = runTest {
        assertFailsWith<ExecutionError> {
            eval(
                """
                fun f(a,b,c) {}
                f(a: 1, ...[2,3])
                """.trimIndent()
            )
        }
    }
}
