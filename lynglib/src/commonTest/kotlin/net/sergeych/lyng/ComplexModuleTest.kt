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

package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ComplexModuleTest {
    @Test
    fun testComplexArithmeticAndInterop() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.complex

            assertEquals(Complex(0.0, 2.0), 2.i)
            assertEquals(Complex(2.0, 0.0), 2.re)
            assertEquals(Complex(1.0, 2.0), 1 + 2.i)
            assertEquals(Complex(1.5, 2.0), 1.5 + 2.i)
            assertEquals(Complex(3.0, 2.0), 1 + Complex(2.0, 2.0))
            val product: Complex = Complex(1.0, 2.0) * Complex(3.0, -1.0)
            assertEquals(5.0, product.re)
            assertEquals(5.0, product.im)
            val quotient: Complex = Complex(5.0, 5.0) / Complex(3.0, -1.0)
            assertEquals(1.0, quotient.re)
            assertEquals(2.0, quotient.im)
            assertEquals(Complex(1.0, -2.0), Complex(1.0, 2.0).conjugate)
            """.trimIndent()
        )
    }

    @Test
    fun testComplexMemberMathFunctions() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.complex

            val eps = 1e-9

            val eipi = Complex.imaginary(π).exp()
            assert(abs(eipi.re + 1.0) < eps)
            assert(abs(eipi.im) < eps)

            val iy: Complex = 2.i
            val sinIy = iy.sin()
            assert(abs(sinIy.re) < eps)
            assert(abs(sinIy.im - sinh(2.0)) < eps)

            val minusOne: Complex = (-1).re
            val root = minusOne.sqrt()
            assert(abs(root.re) < eps)
            assert(abs(root.im - 1.0) < eps)

            val unitLeft: Complex = cis(π)
            assert(abs(unitLeft.re + 1.0) < eps)
            assert(abs(unitLeft.im) < eps)
            """.trimIndent()
        )
    }

    @Test
    fun testInferences() = runTest {
        eval(
            $$"""
            import lyng.decimal
            import lyng.complex
            
            assert( 1.i is Complex )
            assert( 5 + 1.i is Complex )
        """.trimIndent()
        )
    }
    @Test
    fun testDecimalInferences() = runTest {
        eval(
            $$"""
            import lyng.decimal
            import lyng.complex
            
            assert( 1.d.i is Complex )
            assert( 5 + 1.d.i is Complex )
            assert( 5.d + 1.i is Complex )
            assert( 5.d + 2.d.i is Complex )
        """.trimIndent()
        )
    }

}
