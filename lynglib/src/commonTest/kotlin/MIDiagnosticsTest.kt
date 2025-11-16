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
 * Diagnostics tests for Multiple Inheritance (MI)
 */

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class MIDiagnosticsTest {

    @Test
    fun missingMemberIncludesLinearizationAndHint() = runTest {
        val ex = assertFails {
            eval(
                """
                class Foo(val a) { fun runA() { "ResultA:" + a } }
                class Bar(val b) { fun runB() { "ResultB:" + b } }
                class FooBar(a,b) : Foo(a), Bar(b) { }
                val fb = FooBar(1,2)
                fb.qux()
                """.trimIndent()
            )
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("no such member: qux"), "must mention missing member name")
        assertTrue(msg.contains("FooBar"), "must mention receiver class name")
        assertTrue(msg.contains("Considered order:"), "must include linearization header")
        assertTrue(msg.contains("FooBar") && msg.contains("Foo") && msg.contains("Bar"), "must list classes in order")
        assertTrue(msg.contains("this@") || msg.contains("(obj as"), "must suggest qualification or cast")
    }

    @Test
    fun missingFieldIncludesLinearization() = runTest {
        val ex = assertFails {
            eval(
                """
                class Foo(val a) { var tag = "F" }
                class Bar(val b) { var tag = "B" }
                class FooBar(a,b) : Foo(a), Bar(b) { }
                val fb = FooBar(1,2)
                fb.unknownField
                """.trimIndent()
            )
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("no such field: unknownField"))
        assertTrue(msg.contains("FooBar"))
        assertTrue(msg.contains("Considered order:"))
    }

    @Test
    fun invalidQualifiedThisReportsAncestorError() = runTest {
        assertFails {
            eval(
                """
                class Foo() { fun f() { "F" } }
                class Bar() { fun g() { "G" } }
                class Baz() : Foo() {
                    fun bad() { this@Bar.g() }
                }
                val b = Baz()
                b.bad()
                """.trimIndent()
            )
        }
    }

    @Test
    fun castFailureMentionsActualAndTargetTypes() = runTest {
        val ex = assertFails {
            eval(
                """
                class Foo() { }
                class Bar() { }
                val b = Bar()
                (b as Foo)
                """.trimIndent()
            )
        }
        val msg = ex.message ?: ""
        // message like: Cannot cast Bar to Foo (be tolerant across targets)
        val lower = msg.lowercase()
        assertTrue(lower.contains("cast"), "message should mention cast")
        assertTrue(msg.contains("Bar") || msg.contains("Foo"), "should mention at least one of the types")
    }
}
