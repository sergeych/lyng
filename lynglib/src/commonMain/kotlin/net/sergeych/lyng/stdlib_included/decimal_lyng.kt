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
internal val decimalLyng = """
package lyng.decimal

/**
 * Rounding policies used by [DecimalContext] and `withDecimalContext(...)`.
 *
 * These modes currently affect decimal division. They are designed to be explicit and readable in Lyng code.
 *
 * Common examples at precision `2`:
 * - `HalfEven`: `1.d / 8.d -> 0.12`
 * - `HalfAwayFromZero`: `1.d / 8.d -> 0.13`, `-1.d / 8.d -> -0.13`
 * - `HalfTowardsZero`: `1.d / 8.d -> 0.12`, `-1.d / 8.d -> -0.12`
 * - `Ceiling`: rounds toward positive infinity
 * - `Floor`: rounds toward negative infinity
 * - `AwayFromZero`: always increases magnitude when rounding is needed
 * - `TowardsZero`: always truncates toward zero
 */
enum DecimalRounding {
    HalfEven,
    HalfAwayFromZero,
    HalfTowardsZero,
    Ceiling,
    Floor,
    AwayFromZero,
    TowardsZero
}

/**
 * Dynamic decimal arithmetic settings.
 *
 * A decimal context is not attached permanently to a `Decimal` value. Instead, it is applied dynamically
 * inside `withDecimalContext(...)`, which makes the rule local to a block of code.
 *
 * Default context:
 * - precision: `34` significant digits
 * - rounding: `DecimalRounding.HalfEven`
 *
 * Example:
 *
 *     import lyng.decimal
 *
 *     (1.d / 3.d).toStringExpanded()
 *     >>> "0.3333333333333333333333333333333333"
 *
 *     withDecimalContext(10) { (1.d / 3.d).toStringExpanded() }
 *     >>> "0.3333333333"
 *
 *     withDecimalContext(2, DecimalRounding.HalfAwayFromZero) { (1.d / 8.d).toStringExpanded() }
 *     >>> "0.13"
 */
class DecimalContext(
    val precision: Int = 34,
    val rounding: DecimalRounding = DecimalRounding.HalfEven
)

/**
 * Arbitrary-precision decimal value.
 *
 * `Decimal` is intended for decimal arithmetic where binary floating-point (`Real`) is the wrong tool:
 * - money
 * - human-entered decimal values
 * - ratios that should round in decimal, not in binary
 * - reproducible decimal formatting
 *
 * Creating values:
 *
 * - `1.d` converts `Int -> Decimal`
 * - `2.2.d` converts `Real -> Decimal` by preserving the current IEEE-754 value
 * - `"2.2".d` parses exact decimal text
 * - `Decimal.fromInt(...)`, `fromReal(...)`, `fromString(...)` are explicit factory forms
 *
 * Important distinction:
 *
 * - `2.2.d` means "take the current `Real` value and convert it"
 * - `"2.2".d` means "parse this exact decimal literal text"
 *
 * Therefore:
 *
 *     import lyng.decimal
 *
 *     2.2.d.toStringExpanded()
 *     >>> "2.2"
 *
 *     (0.1 + 0.2).d.toStringExpanded()
 *     >>> "0.30000000000000004"
 *
 *     "0.3".d.toStringExpanded()
 *     >>> "0.3"
 *
 * Mixed arithmetic:
 *
 * `Decimal` defines its own operators against decimal-compatible values, and the decimal module also registers
 * interop bridges so built-in left-hand operands work naturally:
 *
 *     import lyng.decimal
 *
 *     1.d + 2
 *     >>> 3.d
 *
 *     1 + 2.d
 *     >>> 3.d
 *
 *     0.5 + 1.d
 *     >>> 1.5.d
 *
 * Precision and rounding:
 *
 * - division uses the default decimal context unless overridden
 * - use `withDecimalContext(...)` to apply a local precision/rounding policy
 *
 * Exact decimal literal style:
 *
 * If you want the source text itself to be the decimal value, use a string:
 *
 *     "2.2".d
 *
 * That is the precise form. `2.2.d` remains a `Real -> Decimal` conversion by design.
 */
extern class Decimal() {
    /** Add another decimal-compatible value. */
    extern fun plus(other: Object): Decimal
    /** Subtract another decimal-compatible value. */
    extern fun minus(other: Object): Decimal
    /** Multiply by another decimal-compatible value. */
    extern fun mul(other: Object): Decimal
    /**
     * Divide by another decimal-compatible value.
     *
     * Division uses the current decimal context:
     * - by default: `34` significant digits, `HalfEven`
     * - inside `withDecimalContext(...)`: the context active for the current block
     */
    extern fun div(other: Object): Decimal
    /** Remainder with another decimal-compatible value. */
    extern fun mod(other: Object): Decimal
    /** Compare with another decimal-compatible value. */
    extern fun compareTo(other: Object): Int
    /** Unary minus. */
    extern fun negate(): Decimal
    /** Convert to `Int` by dropping the fractional part according to backend conversion rules. */
    extern fun toInt(): Int
    /** Convert to `Real`. */
    extern fun toReal(): Real
    /** Return true if this decimal is positive or negative infinity. Always false for Decimal. */
    extern fun isInfinite(): Bool
    /** Return true if this decimal is NaN (not a number). Always false for Decimal. */
    extern fun isNaN(): Bool
    /**
     * Convert to a plain decimal string without scientific notation.
     *
     * This is the preferred representation for user-facing decimal tests and diagnostics.
     */
    extern fun toStringExpanded(): String

    /** Create a decimal from an `Int`. */
    static extern fun fromInt(value: Int): Decimal
    /**
     * Create a decimal from a `Real`.
     *
     * This preserves the current IEEE-754 value using a round-trip-safe decimal conversion.
     * It does not try to recover the original source text.
     */
    static extern fun fromReal(value: Real): Decimal
    /** Parse exact decimal text. */
    static extern fun fromString(value: String): Decimal
}

/**
 * Run [block] with the provided decimal context.
 *
 * This is the main way to control decimal division precision and rounding locally without changing global behavior.
 *
 * Example:
 *
 *     import lyng.decimal
 *
 *     withDecimalContext(10) {
 *         (1.d / 3.d).toStringExpanded()
 *     }
 *     >>> "0.3333333333"
 *
 * Contexts are dynamic and block-local. After the block finishes, the previous context is restored.
 */
extern fun withDecimalContext<T>(context: DecimalContext, block: ()->T): T

/**
 * Convenience overload for changing only precision.
 *
 * Equivalent to `withDecimalContext(DecimalContext(precision, DecimalRounding.HalfEven), block)`.
 */
extern fun withDecimalContext<T>(precision: Int, block: ()->T): T

/**
 * Convenience overload for changing precision and rounding explicitly.
 *
 * Example:
 *
 *     import lyng.decimal
 *
 *     withDecimalContext(2, DecimalRounding.HalfAwayFromZero) {
 *         (1.d / 8.d).toStringExpanded()
 *     }
 *     >>> "0.13"
 */
extern fun withDecimalContext<T>(precision: Int, rounding: DecimalRounding, block: ()->T): T
""".trimIndent()
