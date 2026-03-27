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

class OperatorInteropTest {
    @Test
    fun testPureLyngOperatorInteropRegistration() = runTest {
        val im = Script.defaultImportManager.copy()
        im.addPackage("test.decimalbox") { scope ->
            scope.eval(
                """
                package test.decimalbox
                import lyng.operators

                class DecimalBox(val value: Int) {
                    fun plus(other: DecimalBox) = DecimalBox(value + other.value)
                    fun minus(other: DecimalBox) = DecimalBox(value - other.value)
                    fun mul(other: DecimalBox) = DecimalBox(value * other.value)
                    fun div(other: DecimalBox) = DecimalBox(value / other.value)
                    fun mod(other: DecimalBox) = DecimalBox(value % other.value)
                    fun compareTo(other: DecimalBox) = value <=> other.value
                }

                OperatorInterop.register(
                    Int,
                    DecimalBox,
                    DecimalBox,
                    [BinaryOperator.Plus, BinaryOperator.Minus, BinaryOperator.Mul, BinaryOperator.Div, BinaryOperator.Mod, BinaryOperator.Compare, BinaryOperator.Equals],
                    { x: Int -> DecimalBox(x) },
                    { x: DecimalBox -> x }
                )
                """.trimIndent()
            )
        }

        val scope = im.newStdScope()
        scope.eval(
            """
            import test.decimalbox

            assertEquals(DecimalBox(3), 1 + DecimalBox(2))
            assertEquals(DecimalBox(1), 3 - DecimalBox(2))
            assertEquals(DecimalBox(8), 4 * DecimalBox(2))
            assertEquals(DecimalBox(4), 8 / DecimalBox(2))
            assertEquals(DecimalBox(1), 7 % DecimalBox(2))
            assert(1 < DecimalBox(2))
            assert(2 <= DecimalBox(2))
            assert(3 > DecimalBox(2))
            assert(2 >= DecimalBox(2))
            assert(2 == DecimalBox(2))
            assert(2 != DecimalBox(3))
            """.trimIndent()
        )
    }

    @Test
    fun testRealInteropRegistrationUsesTopLevelModuleCode() = runTest {
        val im = Script.defaultImportManager.copy()
        im.addPackage("test.realbox") { scope ->
            scope.eval(
                """
                package test.realbox
                import lyng.operators

                class RealBox(val value: Real) {
                    fun plus(other: RealBox) = RealBox(value + other.value)
                    fun compareTo(other: RealBox) = value <=> other.value
                }

                OperatorInterop.register(
                    Real,
                    RealBox,
                    RealBox,
                    [BinaryOperator.Plus, BinaryOperator.Compare, BinaryOperator.Equals],
                    { x: Real -> RealBox(x) },
                    { x: RealBox -> x }
                )
                """.trimIndent()
            )
        }

        val scope = im.newStdScope()
        scope.eval(
            """
            import test.realbox

            assertEquals(RealBox(1.75), 0.5 + RealBox(1.25))
            assert(1.5 < RealBox(2.0))
            assert(2.0 == RealBox(2.0))
            """.trimIndent()
        )
    }
}
