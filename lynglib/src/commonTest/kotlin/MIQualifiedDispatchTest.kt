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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Ignore
import kotlin.test.Test

@Ignore("TODO(bytecode-only): uses fallback")
class MIQualifiedDispatchTest {

    @Test
    fun testQualifiedMethodResolution() = runTest {
        eval(
            """
            class Foo(val a) {
                fun common() { "A" }
                fun runA() { "ResultA:" + a }
            }

            class Bar(val b) {
                fun common() { "B" }
                fun runB() { "ResultB:" + b }
            }

            class FooBar(a,b) : Foo(a), Bar(b) { }

            val fb = FooBar(1,2)

            // unqualified picks leftmost base
            assertEquals("A", fb.common())

            // cast-based disambiguation
            assertEquals("B", (fb as Bar).common())
            assertEquals("A", (fb as Foo).common())

            // Note: wrappers using this@Type inside FooBar body will be validated later
            // when class-body method registration is finalized.
            """.trimIndent()
        )
    }

    @Test
    fun testQualifiedFieldReadWrite() = runTest {
        eval(
            """
            class Foo(val a) { var tag = "F" }
            class Bar(val b) { var tag = "B" }
            class FooBar(a,b) : Foo(a), Bar(b) { }

            val fb = FooBar(1,2)
            // unqualified resolves to leftmost base
            assertEquals("F", fb.tag)
            // qualified reads via casts
            assertEquals("F", (fb as Foo).tag)
            assertEquals("B", (fb as Bar).tag)

            // unqualified write updates leftmost base
            fb.tag = "X"
            assertEquals("X", fb.tag)
            assertEquals("X", (fb as Foo).tag)
            assertEquals("B", (fb as Bar).tag)

            // qualified write via cast targets Bar
            (fb as Bar).tag = "Y"
            assertEquals("X", (fb as Foo).tag)
            assertEquals("Y", (fb as Bar).tag)
            """.trimIndent()
        )
    }

    @Test
    fun testCastsAndSafeCall() = runTest {
        eval(
            """
            class Foo(val a) { fun runA() { "ResultA:" + a } }
            class Bar(val b) { fun runB() { "ResultB:" + b } }
            class Buzz : Bar(3)
            val buzz = Buzz()
            assertEquals("ResultB:3", buzz.runB())
            assertEquals("ResultB:3", (buzz as? Bar)?.runB())
            assertEquals(null, (buzz as? Foo)?.runA())
            """.trimIndent()
        )
    }
}
