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

class BigDecimalModuleTest {
    @Test
    fun testDecimalModuleFactoriesAndConversions() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.decimal

            assertEquals("12.34", BigDecimal.fromString("12.34").toStringExpanded())
            assertEquals("1", BigDecimal.fromInt(1).toStringExpanded())
            assertEquals("2.5", "2.5".d.toStringExpanded())
            assertEquals("1", 1.d.toStringExpanded())
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
}
