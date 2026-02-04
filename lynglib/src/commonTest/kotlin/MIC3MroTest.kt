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
 * C3 MRO tests for Multiple Inheritance
 */

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test

class MIC3MroTest {

    @Test
    fun diamondConstructorRunsSharedAncestorOnce() = runTest {
        eval(
            """
            var topInit = 0

            class Top() {
                // increment module-scope counter in instance initializer; runs once per Top-subobject
                var tick = (topInit = topInit + 1)
            }

            class Left() : Top()
            class Right() : Top()
            class Bottom() : Left(), Right()

            val b = Bottom()
            assertEquals(1, topInit)
            """.trimIndent()
        )
    }

    @Test
    fun methodResolutionFollowsC3() = runTest {
        // For the classic diamond D(B,C), B and C derive from A; C3 should result in D -> B -> C -> A
        eval(
            """
            class A() { fun common() { "A" } }
            class B() : A() { override fun common() { "B" } }
            class C() : A() { override fun common() { "C" } }
            class D() : B(), C()

            val d = D()
            // Unqualified must pick B.common() according to C3 when direct bases are (B, C)
            assertEquals("B", d.common())
            // Qualified disambiguation via casts still works
            assertEquals("C", (d as C).common())
            assertEquals("A", (d as A).common())
            """.trimIndent()
        )
    }
}
