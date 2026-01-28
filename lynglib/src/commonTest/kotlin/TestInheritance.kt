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

@Ignore("TODO(bytecode-only): uses fallback")
class TestInheritance {

    @Test
    fun testInheritanceSpecification() = runTest { 
        eval("""
        // Multiple inheritance specification test (spec only, parser/interpreter TBD)

        // Parent A: exposes a val and a var, and a method with a name that collides with Bar.common()
        class Foo(val a) {
    var tag = "F"

    fun runA() {
        "ResultA:" + a
    }

    fun common() {
        "CommonA"
    }

    // this can only be called from Foo (not from subclasses):
    private fun privateInFoo() {
    }

    // this can be called from Foo and any subclass (including MI subclasses):
    protected fun protectedInFoo() {
    }
}

// Parent B: also exposes a val and a var with the same name to test field inheritance and conflict rules
class Bar(val b) {
    var tag = "B"

    fun runB() {
        "ResultB:" + b
    }

    fun common() {
        "CommonB"
    }
}

// With multiple inheritance, base constructors are called in the order of declaration,
// and each ancestor is initialized at most once (diamonds are de-duplicated):
class FooBar(a, b) : Foo(a), Bar(b) {

    // Ambiguous method name "common" can be disambiguated:
    fun commonFromFoo() {
        // explicit qualification by ancestor type:
        this@Foo.common()
        // or by cast:
        (this as Foo).common()
    }

    fun commonFromBar() {
        this@Bar.common()
        (this as Bar).common()
    }

    // Accessing inherited fields (val/var) respects the same resolution rules:
    fun tagFromFoo() { this@Foo.tag }
    fun tagFromBar() { this@Bar.tag }
}

val fb = FooBar(1, 2)

// Methods with distinct names from different bases work:
assertEquals("ResultA:1", fb.runA())
assertEquals("ResultB:2", fb.runB())

// If we call an ambiguous method unqualified, the first in MRO (leftmost base) is used:
assertEquals("CommonA", fb.common())

// We can call a specific one via explicit qualification or cast:
assertEquals("CommonB", (fb as Bar).common())
assertEquals("CommonA", (fb as Foo).common())

// Or again via explicit casts (wrappers may be validated separately):
assertEquals("CommonB", (fb as Bar).common())
assertEquals("CommonA", (fb as Foo).common())

// Inheriting val/var:
// - Reading an ambiguous var/val selects the first along MRO (Foo.tag initially):
assertEquals("F", fb.tag)
// - Qualified access returns the chosen ancestor’s member:
assertEquals("F", (fb as Foo).tag)
assertEquals("B", (fb as Bar).tag)

// - Writing an ambiguous var writes to the same selected member (first in MRO):
fb.tag = "X"
assertEquals("X", fb.tag)              // unqualified resolves to Foo.tag
assertEquals("X", (fb as Foo).tag)     // Foo.tag updated
assertEquals("B", (fb as Bar).tag)     // Bar.tag unchanged

// - Qualified write via cast updates the specific ancestor’s storage:
(fb as Bar).tag = "Y"
assertEquals("X", (fb as Foo).tag)
assertEquals("Y", (fb as Bar).tag)

// A simple single-inheritance subclass still works:
class Buzz : Bar(3)
val buzz = Buzz()

assertEquals("ResultB:3", buzz.runB())

// Optional cast returns null if cast is not possible; use safe-call with it:
assertEquals("ResultB:3", (buzz as? Bar)?.runB())
assertEquals(null, (buzz as? Foo)?.runA())

// Visibility (spec only):
// - Foo.privateInFoo() is accessible only inside Foo body; even FooBar cannot call it,
//   including with this@Foo or casts. Attempting to do so must be a compile-time error.
// - Foo.protectedInFoo() is accessible inside Foo and any subclass bodies (including FooBar),
//   but not from unrelated classes/instances.
        """.trimIndent())
    }

    @Test
    fun testMITypes() = runTest {
        eval("""
            import lyng.serialization
            
            class Point(x,y)
            class Color(r,g,b)
            
            class ColoredPoint(x, y, r, g, b): Point(x,y), Color(r,g,b)
            
            
            val cp = ColoredPoint(1,2,30,40,50)

            // cp is Color, Point and ColoredPoint:
            assert(cp is ColoredPoint)
            assert(cp is Point)
            assert(cp is Color)
            
            // Color fields must be in ColoredPoint: 
            assertEquals(30, cp.r)
            assertEquals(40, cp.g)
            assertEquals(50, cp.b)

            // point fields must be available too:
            assertEquals(1, cp.x)
            assertEquals(2, cp.y)


            // if we convert type to color, the fields should be available also:
            val color = cp as Color
            assert(color is Color)
            assertEquals(30, color.r)
            assertEquals(40, color.g)
            assertEquals(50, color.b)
            
            // converted to Point, cp fields are still available:
            val p = cp as Point
            assert(p is Point)
            assertEquals(1, p.x)
            assertEquals(2, p.y)
        """)
    }

}
