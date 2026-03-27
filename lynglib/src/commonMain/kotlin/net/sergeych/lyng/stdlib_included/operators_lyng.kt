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

package net.sergeych.lyng.stdlib_included

@Suppress("Unused", "MemberVisibilityCanBePrivate")
internal val operatorsLyng = """
package lyng.operators

/**
 * Binary operators that can be bridged between two different operand classes.
 *
 * Registering a pair means:
 * - the runtime can evaluate `left op right` when `left` has class `L` and `right` has class `R`
 * - both operands are first converted to a shared "common" class `C`
 * - the actual operator implementation is then looked up on `C`
 *
 * This is primarily useful when:
 * - you add a new numeric-like type in a library or in pure Lyng code
 * - your type already implements operators against itself
 * - you also want existing left-hand types such as `Int` or `Real` to work when your type is on the right
 *
 * Example:
 *
 *     import lyng.operators
 *
 *     class DecimalBox(val value: Int) {
 *         fun plus(other: DecimalBox) = DecimalBox(value + other.value)
 *         fun compareTo(other: DecimalBox) = value <=> other.value
 *     }
 *
 *     OperatorInterop.register(
 *         Int,
 *         DecimalBox,
 *         DecimalBox,
 *         [BinaryOperator.Plus, BinaryOperator.Compare, BinaryOperator.Equals],
 *         { x: Int -> DecimalBox(x) },
 *         { x: DecimalBox -> x }
 *     )
 *
 * After registration:
 * - `1 + DecimalBox(2)` works
 * - `1 < DecimalBox(2)` works
 * - `2 == DecimalBox(2)` works
 *
 * But this registration does not replace methods on the original classes. It only teaches
 * Lyng how to bridge a mixed pair into a common class for the listed operators.
 */
enum BinaryOperator {
    /** `a + b` */
    Plus,
    /** `a - b` */
    Minus,
    /** `a * b` */
    Mul,
    /** `a / b` */
    Div,
    /** `a % b` */
    Mod,
    /**
     * Ordering comparisons.
     *
     * Registering `Compare` enables `<`, `<=`, `>`, `>=`, and the shuttle operator `<=>`
     * for the mixed operand pair.
     */
    Compare,
    /**
     * Equality comparisons.
     *
     * Registering `Equals` enables `==` and `!=` for the mixed operand pair.
     */
    Equals
}

/**
 * Runtime registry for mixed-class binary operators.
 *
 * `register(L, R, C, ...)` defines how Lyng should evaluate expressions where:
 * - the left operand has class `L`
 * - the right operand has class `R`
 * - the actual operation should be executed as if both were values of class `C`
 *
 * The registry is symmetric for the converted values, but not for the original syntax.
 * Its job is specifically to fill the gap where your custom type appears on the right:
 *
 * - `myDecimal + 1` usually already works if `BigDecimal.plus(Int)` exists
 * - `1 + myDecimal` needs registration because `Int` itself is not rewritten
 *
 * Typical pattern for a custom type:
 *
 *     import lyng.operators
 *
 *     class Rational(val num: Int, val den: Int) {
 *         fun plus(other: Rational) = Rational(num * other.den + other.num * den, den * other.den)
 *         fun minus(other: Rational) = Rational(num * other.den - other.num * den, den * other.den)
 *         fun mul(other: Rational) = Rational(num * other.num, den * other.den)
 *         fun div(other: Rational) = Rational(num * other.den, den * other.num)
 *         fun compareTo(other: Rational) = (num * other.den) <=> (other.num * den)
 *
 *         static fun fromInt(value: Int) = Rational(value, 1)
 *     }
 *
 *     OperatorInterop.register(
 *         Int,
 *         Rational,
 *         Rational,
 *         [
 *             BinaryOperator.Plus,
 *             BinaryOperator.Minus,
 *             BinaryOperator.Mul,
 *             BinaryOperator.Div,
 *             BinaryOperator.Compare,
 *             BinaryOperator.Equals
 *         ],
 *         { x: Int -> Rational.fromInt(x) },
 *         { x: Rational -> x }
 *     )
 *
 * Then:
 * - `1 + Rational(1, 2)` works
 * - `3 > Rational(5, 2)` works
 * - `2 == Rational(2, 1)` works
 *
 * Decimal uses the same mechanism internally to make `Int + BigDecimal` and `Real + BigDecimal`
 * work without changing the built-in `Int` or `Real` classes.
 */
extern object OperatorInterop {
    /**
     * Register a mixed-operand operator bridge.
     *
     * @param leftClass class of the original left operand
     * @param rightClass class of the original right operand
     * @param commonClass class that will actually execute the operator methods
     * @param operators operators supported for this mixed pair
     * @param leftToCommon conversion from `L` to `C`
     * @param rightToCommon conversion from `R` to `C`
     *
     * Requirements for `commonClass`:
     * - if you register `Plus`, `C` should implement `fun plus(other: C): C` or equivalent accepted result type
     * - if you register `Minus`, `C` should implement `fun minus(other: C): ...`
     * - if you register `Mul`, `C` should implement `fun mul(other: C): ...`
     * - if you register `Div`, `C` should implement `fun div(other: C): ...`
     * - if you register `Mod`, `C` should implement `fun mod(other: C): ...`
     * - if you register `Compare`, `C` should implement `fun compareTo(other: C): Int`
     *
     * `Equals` reuses comparison/equality semantics of the promoted values.
     *
     * Registration is usually done once at module initialization time:
     *
     *     package my.rational
     *     import lyng.operators
     *
     *     class Rational(val num: Int, val den: Int) {
     *         fun plus(other: Rational) = Rational(num * other.den + other.num * den, den * other.den)
     *         fun compareTo(other: Rational) = (num * other.den) <=> (other.num * den)
     *         static fun fromInt(value: Int) = Rational(value, 1)
     *     }
     *
     *     OperatorInterop.register(
     *         Int,
     *         Rational,
     *         Rational,
     *         [BinaryOperator.Plus, BinaryOperator.Compare, BinaryOperator.Equals],
     *         { x: Int -> Rational.fromInt(x) },
     *         { x: Rational -> x }
     *     )
     */
    extern fun register<L, R, C>(
        leftClass: Class<L>,
        rightClass: Class<R>,
        commonClass: Class<C>,
        operators: List<BinaryOperator>,
        leftToCommon: (L)->C,
        rightToCommon: (R)->C
    ): Void
}
""".trimIndent()
