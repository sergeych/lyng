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
import kotlin.test.Test

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

class TestInheritance {

    @Test
    fun testInheritanceSpecification() = runTest { 
        eval("""
        // Single inheritance specification test (MI deferred)

        class Foo() {
            var a = 1
            var tag = "F"

            fun runA() {
                "ResultA:" + a
            }

            fun common() {
                "CommonA"
            }

            private fun privateInFoo() {
            }

            protected fun protectedInFoo() {
            }
        }

        class Bar() {
            var b = 3
            var tag = "B"

            fun runB() {
                "ResultB:" + b
            }

            fun common() {
                "CommonB"
            }
        }

        class FooBar : Foo() {
            fun commonFromFoo() {
                this@Foo.common()
                (this as Foo).common()
            }

            fun tagFromFoo() { this@Foo.tag }
        }

        val fb = FooBar()

        assertEquals("ResultA:1", fb.runA())
        assertEquals("CommonA", fb.common())

        assertEquals("F", fb.tag)
        fb.tag = "X"
        assertEquals("X", fb.tag)
        assertEquals("X", (fb as Foo).tag)

        class Buzz : Bar()
        val buzz = Buzz()

        assertEquals("ResultB:3", buzz.runB())

        assertEquals("ResultB:3", (buzz as? Bar)?.runB())
        assertEquals(null, (buzz as? Foo)?.runA())
        """.trimIndent())
    }

    @Test
    fun testMITypes() = runTest {
        eval("""
            import lyng.serialization
            
            class Point()
            class Color(val r, val g, val b)
            
            class ColoredPoint(val x, val y, val r, val g, val b): Point()
            
            
            val cp = ColoredPoint(1,2,30,40,50)

            // cp is Point and ColoredPoint:
            assert(cp is ColoredPoint)
            assert(cp is Point)
            assert(!(cp is Color))
            
            // Color fields must be in ColoredPoint:
            assertEquals(30, cp.r)
            assertEquals(40, cp.g)
            assertEquals(50, cp.b)

            // point fields must be available too:
            assertEquals(1, cp.x)
            assertEquals(2, cp.y)


            // cast to unrelated type should be null:
            val color = cp as? Color
            assertEquals(null, color)
            
            // converted to Point, cp fields are still available:
            val p = cp as Point
            assert(p is Point)
        """)
    }

}
