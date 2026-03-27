# Decimal (`lyng.decimal`)

`lyng.decimal` adds an arbitrary-precision decimal type to Lyng as a normal library module.

Import it when you need decimal arithmetic that should not inherit `Real`'s binary floating-point behavior:

```lyng
import lyng.decimal
```

## What `BigDecimal` Is For

Use `BigDecimal` when values are fundamentally decimal:

- money
- human-entered quantities
- exact decimal text
- predictable decimal rounding
- user-facing formatting and tests

Do not use it just because a number has a fractional part. `Real` is still the right type for ordinary double-precision numeric work.

## Creating Decimal Values

There are three supported conversions:

```lyng
import lyng.decimal

val a = 1.d
val b = 2.2.d
val c = "2.2".d

assertEquals("1", a.toStringExpanded())
assertEquals("2.2", b.toStringExpanded())
assertEquals("2.2", c.toStringExpanded())
```

The three forms mean different things:

- `1.d`: convert `Int -> BigDecimal`
- `2.2.d`: convert `Real -> BigDecimal`
- `"2.2".d`: parse exact decimal text

That distinction is intentional.

### `Real.d` vs `"..." .d`

`Real.d` preserves the current `Real` value. It does not pretend the source code was exact decimal text.

```lyng
import lyng.decimal

assertEquals("0.30000000000000004", (0.1 + 0.2).d.toStringExpanded())
assertEquals("0.3", "0.3".d.toStringExpanded())
```

This follows the "minimal confusion" rule:

- if you start from a `Real`, you get a decimal representation of that `Real`
- if you want exact decimal source text, use a `String`

## Factory Functions

The explicit factory methods are:

```lyng
import lyng.decimal

BigDecimal.fromInt(10)
BigDecimal.fromReal(2.5)
BigDecimal.fromString("12.34")
```

These are equivalent to the conversion-property forms, but sometimes clearer in APIs or generated code.

## Core Operations

`BigDecimal` supports:

- `+`
- `-`
- `*`
- `/`
- `%`
- unary `-`
- comparison operators
- equality operators

Examples:

```lyng
import lyng.decimal

assertEquals("3.75", ("1.5".d + "2.25".d).toStringExpanded())
assertEquals("1.25", ("2.5".d - "1.25".d).toStringExpanded())
assertEquals("3.0", ("1.5".d * 2.d).toStringExpanded())
assertEquals("0.5", (1.d / 2.d).toStringExpanded())
assert("2.0".d > "1.5".d)
assert("2.0".d == 2.d)
```

## Interoperability With `Int` and `Real`

The decimal module registers mixed-operand operator bridges so both sides read naturally:

```lyng
import lyng.decimal

assertEquals(3.d, 1 + 2.d)
assertEquals(3.d, 2.d + 1)
assertEquals(1.5.d, 1.d + 0.5)
assertEquals(1.5.d, 0.5 + 1.d)
assert(2 == 2.d)
assert(3 > 2.d)
```

Without this registration mechanism, only the cases directly implemented on the left-hand class would work. The bridge fills the gap for expressions such as `Int + BigDecimal` and `Real + BigDecimal`.

See [OperatorInterop.md](OperatorInterop.md) for the generic mechanism behind that.

## String Representation

Use `toStringExpanded()` when you want plain decimal output without scientific notation:

```lyng
import lyng.decimal

assertEquals("12.34", "12.34".d.toStringExpanded())
```

This is the recommended representation for:

- tests
- user-visible diagnostics
- decimal formatting checks

## Conversions Back To Built-ins

```lyng
import lyng.decimal

assertEquals(2, "2.9".d.toInt())
assertEquals(2.9, "2.9".d.toReal())
```

Use `toReal()` only when you are willing to return to binary floating-point semantics.

## Division Context

Division is the operation where precision and rounding matter most.

By default, decimal division uses:

- precision: `34` significant digits
- rounding: `HalfEven`

Example:

```lyng
import lyng.decimal

assertEquals("0.3333333333333333333333333333333333", (1.d / 3.d).toStringExpanded())
assertEquals("0.6666666666666666666666666666666667", ("2".d / 3.d).toStringExpanded())
```

## `withDecimalContext(...)`

Use `withDecimalContext(...)` to override decimal division rules inside a block:

```lyng
import lyng.decimal

assertEquals(
    "0.3333333333",
    withDecimalContext(10) { (1.d / 3.d).toStringExpanded() }
)
```

You can also pass an explicit context object:

```lyng
import lyng.decimal

val ctx = DecimalContext(6, DecimalRounding.HalfAwayFromZero)
assertEquals("0.666667", withDecimalContext(ctx) { ("2".d / 3.d).toStringExpanded() })
```

The context is dynamic and local to the block. After the block exits, the previous context is restored.

## Rounding Modes

Available rounding modes:

- `HalfEven`
- `HalfAwayFromZero`
- `HalfTowardsZero`
- `Ceiling`
- `Floor`
- `AwayFromZero`
- `TowardsZero`

Tie example at precision `2`:

```lyng
import lyng.decimal

assertEquals("0.12", withDecimalContext(2, DecimalRounding.HalfEven) { (1.d / 8.d).toStringExpanded() })
assertEquals("0.13", withDecimalContext(2, DecimalRounding.HalfAwayFromZero) { (1.d / 8.d).toStringExpanded() })
assertEquals("0.12", withDecimalContext(2, DecimalRounding.HalfTowardsZero) { (1.d / 8.d).toStringExpanded() })
assertEquals("0.13", withDecimalContext(2, DecimalRounding.Ceiling) { (1.d / 8.d).toStringExpanded() })
assertEquals("0.12", withDecimalContext(2, DecimalRounding.Floor) { (1.d / 8.d).toStringExpanded() })
```

Negative values follow the same named policy in the obvious direction:

```lyng
import lyng.decimal

assertEquals("-0.13", withDecimalContext(2, DecimalRounding.HalfAwayFromZero) { (-1.d / 8.d).toStringExpanded() })
assertEquals("-0.12", withDecimalContext(2, DecimalRounding.HalfTowardsZero) { (-1.d / 8.d).toStringExpanded() })
```

## Recommended Usage Rules

If you care about exact decimal source text:

```lyng
"12.34".d
```

If you intentionally convert an existing binary floating-point value:

```lyng
someReal.d
```

If you want local control over division:

```lyng
withDecimalContext(precision, rounding) { ... }
```

If you want custom mixed operators for your own type, follow the same pattern as Decimal and use the operator registry:

- define the operators on your own class
- choose a common class
- register mixed operand bridges

See [OperatorInterop.md](OperatorInterop.md).
