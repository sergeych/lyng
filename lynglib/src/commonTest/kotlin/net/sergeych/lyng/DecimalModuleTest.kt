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

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.obj.ObjException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecimalModuleTest {
    @Test
    fun testDecimalModuleFactoriesAndConversions() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.decimal

            assertEquals("12.34", Decimal.fromString("12.34").toStringExpanded())
            assertEquals("1", Decimal.fromInt(1).toStringExpanded())
            assertEquals("42", Decimal.fromInt(42).toStringExpanded())
            assertEquals("2.5", "2.5".d.toStringExpanded())
            assertEquals("1", 1.d.toStringExpanded())
            assertEquals("42", 42.d.toStringExpanded())
            assertEquals("2.2", 2.2.d.toStringExpanded())
            assertEquals("3", (1 + 2).d.toStringExpanded())
            assertEquals("1.5", (1 + 0.5).d.toStringExpanded())
            assertEquals("3", (1 + 2.d).toStringExpanded())
            assertEquals("3", (2.d + 1).toStringExpanded())
            assertEquals(2.5, "2.5".d.toReal())
            assertEquals(2, "2.5".d.toInt())
            assertEquals(2.2, 2.2.d.toReal())
            assertEquals("0.30000000000000004", (0.1 + 0.2).d.toStringExpanded())
            """.trimIndent()
        )
    }

    @Test
    fun testDecimalModuleMixedIntOperators() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.decimal

            assertEquals(3.d, 1 + 2.d)
            assertEquals(3.d, 2.d + 1)
            assertEquals(1.d, 3 - 2.d)
            assertEquals(1.d, 3.d - 2)
            assertEquals(8.d, 4 * 2.d)
            assertEquals(8.d, 4.d * 2)
            assertEquals(4.d, 8 / 2.d)
            assertEquals(4.d, 8.d / 2)
            assertEquals(1.d, 7 % 2.d)
            assertEquals(1.d, 7.d % 2)
            assert(1 < 2.d)
            assert(2 <= 2.d)
            assert(3 > 2.d)
            assert(3.d > 2)
            assert(2 == 2.d)
            assert(2.d == 2)
            """.trimIndent()
        )
    }

    @Test
    fun testDecimalModuleMixedRealOperators() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.decimal

            assertEquals(3.5.d, 1.5 + 2.d)
            assertEquals(3.5.d, 2.d + 1.5)
            assertEquals(1.5.d, 3.5 - 2.d)
            assertEquals(1.5.d, 3.5.d - 2.0)
            assertEquals(7.d, 3.5 * 2.d)
            assertEquals(7.d, 3.5.d * 2.0)
            assertEquals(1.75.d, 3.5 / 2.d)
            assertEquals(1.75.d, 3.5.d / 2.0)
            assert(1.5 < 2.d)
            assert(2.5.d > 2.0)
            assert(2.5 == 2.5.d)
            assert(2.5.d == 2.5)
            """.trimIndent()
        )
    }

    @Test
    fun testMixedRealDecimalNonFiniteResultsStayReal() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.decimal

            val inf1 = 1.0 / 0.d
            val inf2 = 1.d / 0.0
            val inf3 = (1.0 / 0.0) * 2.d
            val negInf = -1.d / 0.0
            val nan1 = 0.0 / 0.d
            val nan2 = (0.0 / 0.0) + 1.d

            assert(inf1 is Real)
            assert(inf2 is Real)
            assert(inf3 is Real)
            assert(negInf is Real)
            assert(nan1 is Real)
            assert(nan2 is Real)

            assertEquals("Infinity", inf1.toString())
            assertEquals("Infinity", inf2.toString())
            assertEquals("Infinity", inf3.toString())
            assertEquals("-Infinity", negInf.toString())
            assertEquals("NaN", nan1.toString())
            assertEquals("NaN", nan2.toString())

            assert(inf1 > 999999999.d)
            assert(negInf < -999999999.d)
            assert(!(nan1 == 1.d))
            """.trimIndent()
        )
    }

    @Test
    fun testDecimalRejectsExplicitNonFiniteRealConversions() = runTest {
        val scope = Script.newScope()
        val ex1 = assertFailsWith<ExecutionError> {
            scope.eval(
                """
                import lyng.decimal
                (1.0 / 0.0).d
                """.trimIndent()
            )
        }
        val ex2 = assertFailsWith<ExecutionError> {
            scope.eval(
                """
                import lyng.decimal
                Decimal.fromReal(0.0 / 0.0)
                """.trimIndent()
            )
        }

        assertEquals(
            "cannot convert non-finite Real to Decimal: Infinity",
            (ex1.errorObject as ObjException).message.value
        )
        assertEquals(
            "cannot convert non-finite Real to Decimal: NaN",
            (ex2.errorObject as ObjException).message.value
        )
    }

    @Test
    fun testDecimalDivisionUsesDefaultContext() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.decimal

            assertEquals("0.125", (1.d / 8.d).toStringExpanded())
            assertEquals("0.3333333333333333333333333333333333", (1.d / 3.d).toStringExpanded())
            assertEquals("0.6666666666666666666666666666666667", ("2".d / 3.d).toStringExpanded())
            """.trimIndent()
        )
    }

    @Test
    fun testWithDecimalContextOverridesDivisionContext() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.decimal

            assertEquals("0.3333333333333333333333333333333333", (1.d / 3.d).toStringExpanded())
            assertEquals("0.3333333333", withDecimalContext(10) { (1.d / 3.d).toStringExpanded() })
            assertEquals("0.666667", withDecimalContext(6) { ("2".d / 3.d).toStringExpanded() })
            assertEquals("0.666667", withDecimalContext(DecimalContext(6)) { ("2".d / 3.d).toStringExpanded() })
            assertEquals("0.12", withDecimalContext(2) { (1.d / 8.d).toStringExpanded() })
            assertEquals("0.13", withDecimalContext(2, DecimalRounding.HalfAwayFromZero) { (1.d / 8.d).toStringExpanded() })
            assertEquals("0.13", withDecimalContext(DecimalContext(2, DecimalRounding.HalfAwayFromZero)) { (1.d / 8.d).toStringExpanded() })
            assertEquals("0.3333333333333333333333333333333333", (1.d / 3.d).toStringExpanded())
            """.trimIndent()
        )
    }

    @Test
    fun testDecimalDivisionRoundingMatrix() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.decimal

            assertEquals("0.12", withDecimalContext(2, DecimalRounding.HalfEven) { (1.d / 8.d).toStringExpanded() })
            assertEquals("-0.12", withDecimalContext(2, DecimalRounding.HalfEven) { (-1.d / 8.d).toStringExpanded() })

            assertEquals("0.13", withDecimalContext(2, DecimalRounding.HalfAwayFromZero) { (1.d / 8.d).toStringExpanded() })
            assertEquals("-0.13", withDecimalContext(2, DecimalRounding.HalfAwayFromZero) { (-1.d / 8.d).toStringExpanded() })

            assertEquals("0.12", withDecimalContext(2, DecimalRounding.HalfTowardsZero) { (1.d / 8.d).toStringExpanded() })
            assertEquals("-0.12", withDecimalContext(2, DecimalRounding.HalfTowardsZero) { (-1.d / 8.d).toStringExpanded() })

            assertEquals("0.13", withDecimalContext(2, DecimalRounding.Ceiling) { (1.d / 8.d).toStringExpanded() })
            assertEquals("-0.12", withDecimalContext(2, DecimalRounding.Ceiling) { (-1.d / 8.d).toStringExpanded() })

            assertEquals("0.12", withDecimalContext(2, DecimalRounding.Floor) { (1.d / 8.d).toStringExpanded() })
            assertEquals("-0.13", withDecimalContext(2, DecimalRounding.Floor) { (-1.d / 8.d).toStringExpanded() })

            assertEquals("0.13", withDecimalContext(2, DecimalRounding.AwayFromZero) { (1.d / 8.d).toStringExpanded() })
            assertEquals("-0.13", withDecimalContext(2, DecimalRounding.AwayFromZero) { (-1.d / 8.d).toStringExpanded() })

            assertEquals("0.12", withDecimalContext(2, DecimalRounding.TowardsZero) { (1.d / 8.d).toStringExpanded() })
            assertEquals("-0.12", withDecimalContext(2, DecimalRounding.TowardsZero) { (-1.d / 8.d).toStringExpanded() })
            """.trimIndent()
        )
    }

    @Test
    fun testDefaultToString() = runTest {
        eval("""
            import lyng.decimal
            
            var s0 = "0.1".d + "0.1".d
            assertEquals("0.2", s0.toStringExpanded())
            assertEquals("0.2", s0.toString())
        """.trimIndent())
    }

    @Test
    fun testDecimalMathHelpersUseExactImplementationsWhenAvailable() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.decimal

            val absValue = abs("-2.5".d) as Decimal
            val floorPos = floor("2.9".d) as Decimal
            val floorNeg = floor("-2.1".d) as Decimal
            val ceilPos = ceil("2.1".d) as Decimal
            val ceilNeg = ceil("-2.1".d) as Decimal
            val roundPos = round("2.5".d) as Decimal
            val roundNeg = round("-2.5".d) as Decimal
            val powInt = pow("1.5".d, 2) as Decimal

            assertEquals("2.5", absValue.toStringExpanded())
            assertEquals("2", floorPos.toStringExpanded())
            assertEquals("-3", floorNeg.toStringExpanded())
            assertEquals("3", ceilPos.toStringExpanded())
            assertEquals("-2", ceilNeg.toStringExpanded())
            assertEquals("3", roundPos.toStringExpanded())
            assertEquals("-2", roundNeg.toStringExpanded())
            assertEquals("2.25", powInt.toStringExpanded())
            """.trimIndent()
        )
    }

    @Test
    fun testDecimalMathHelpersFallbackThroughRealTemporarily() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.decimal

            val sinDecimal = sin("0.5".d) as Decimal
            val expDecimal = exp("1.25".d) as Decimal
            val sqrtDecimal = sqrt("2".d) as Decimal
            val lnDecimal = ln("2".d) as Decimal
            val log10Decimal = log10("2".d) as Decimal
            val log2Decimal = log2("2".d) as Decimal
            val powDecimal = pow("2".d, "0.5".d) as Decimal

            assertEquals((sin(0.5) as Real).d.toStringExpanded(), sinDecimal.toStringExpanded())
            assertEquals((exp(1.25) as Real).d.toStringExpanded(), expDecimal.toStringExpanded())
            assertEquals((sqrt(2.0) as Real).d.toStringExpanded(), sqrtDecimal.toStringExpanded())
            assertEquals((ln(2.0) as Real).d.toStringExpanded(), lnDecimal.toStringExpanded())
            assertEquals((log10(2.0) as Real).d.toStringExpanded(), log10Decimal.toStringExpanded())
            assertEquals((log2(2.0) as Real).d.toStringExpanded(), log2Decimal.toStringExpanded())
            assertEquals((pow(2.0, 0.5) as Real).d.toStringExpanded(), powDecimal.toStringExpanded())
            """.trimIndent()
        )
    }

    @Test
    fun decimalMustBeObj() = runTest {
        eval("""
            import lyng.decimal

            val decimal = 42.d
            val context = DecimalContext(12)

            assert(decimal is Decimal)
            assertEquals(Decimal, decimal::class)

            assert(context is DecimalContext)
            assertEquals(DecimalContext, context::class)
        """.trimIndent())
    }

    @Test
    fun testFromRealLife1() = runTest {
        eval("""
            import lyng.decimal
            var X = 42.d
            X += 11
            assertEquals(53.d, X)
        """)
    }

    @Test
    fun decimalPropertyWorksInsideFunctionBody() = runTest {
        eval("""
            import lyng.decimal

            fun main() {
                val x = 42.d
                assertEquals(42.d, x)
                assertEquals(53.d, x + 11)
            }

            main()
        """.trimIndent())
    }

    @Test
    fun kotlinHelperCanWrapIonBigDecimal() = runTest {
        val scope = Script.newScope()
        val decimal = scope.asFacade().newDecimal(BigDecimal.parseStringWithMode("12.34"))

        assertEquals("Decimal", decimal.objClass.className)
        assertEquals("12.34", decimal.toString(scope).value)
        assertEquals("12.34", decimal.invokeInstanceMethod(scope, "toStringExpanded").cast<net.sergeych.lyng.obj.ObjString>(scope).value)
    }

    @Test
    fun testDecimalComparisons() = runTest {
        eval("""
            import lyng.decimal
            val X = 42.d
            assert(X < 43.d)
            assert(X < 43)
            assert(X == 42)
        """.trimIndent())
    }

    @Test
    fun testDecimalStringInterpolation() = runTest {
        eval(
            $$"""
            import lyng.decimal
            var X = "2".d
            var re = 50.d
            var im = X
            val s = "$re + ${im}i"
            assertEquals("50 + 2i", s)
        """.trimIndent())
    }

    @Test
    fun testDecimalInterpolationSyntaxErrorKeepsOriginalSourcePosition() = runTest {
        val ex = assertFailsWith<ScriptError> {
            eval(
                $$"""
                import lyng.decimal
                var re = 50.d
                val s = "${re + }"
            """.trimIndent()
            )
        }

        assertEquals(2, ex.pos.line)
    }

    @Test
    fun testOverloading() = runTest {
        eval("""
            import lyng.decimal
            assert( 1.d is Decimal )
            assertEquals(Decimal, abs(1.d)::class)
            // here the inference must work:
            val t = abs(-10.d)
            assert(t is Decimal)
            assertEquals(10,t)
        """.trimIndent())
    }

}
