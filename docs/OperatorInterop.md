# Operator Interop Registry

`lyng.operators` provides a runtime registry for mixed-class binary operators.

Import it when you want expressions such as:

- `1 + MyType(...)`
- `2 < MyType(...)`
- `3 == MyType(...)`

to work without modifying the built-in `Int` or `Real` classes.

```lyng
import lyng.operators
```

## Why This Exists

If your class defines:

```lyng
class Amount(val value: Int) {
    fun plus(other: Amount) = Amount(value + other.value)
}
```

then:

```lyng
Amount(1) + Amount(2)
```

works, because the left operand already knows how to add another `Amount`.

But:

```lyng
1 + Amount(2)
```

does not naturally work, because `Int` has not been rewritten to know about `Amount`.

The operator interop registry solves exactly that problem.

## Mental Model

Registration describes a mixed pair:

- left class `L`
- right class `R`
- common class `C`

When Lyng sees `L op R`, it:

1. converts `L -> C`
2. converts `R -> C`
3. evaluates the operator as `C op C`

So the registry is a bridge, not a separate arithmetic engine.

## API

```lyng
OperatorInterop.register(
    leftClass,
    rightClass,
    commonClass,
    operators,
    leftToCommon,
    rightToCommon
)
```

Parameters:

- `leftClass`: original left operand class
- `rightClass`: original right operand class
- `commonClass`: class that will actually execute the operator methods
- `operators`: list of operators enabled for this pair
- `leftToCommon`: conversion from left operand to common class
- `rightToCommon`: conversion from right operand to common class

## Supported Operators

`BinaryOperator` values:

- `Plus`
- `Minus`
- `Mul`
- `Div`
- `Mod`
- `Compare`
- `Equals`

Meaning:

- `Compare` enables `<`, `<=`, `>`, `>=`, and `<=>`
- `Equals` enables `==` and `!=`

## Minimal Working Example

```lyng
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
    [
        BinaryOperator.Plus,
        BinaryOperator.Minus,
        BinaryOperator.Mul,
        BinaryOperator.Div,
        BinaryOperator.Mod,
        BinaryOperator.Compare,
        BinaryOperator.Equals
    ],
    { x: Int -> DecimalBox(x) },
    { x: DecimalBox -> x }
)
```

Then:

```lyng
import test.decimalbox

assertEquals(DecimalBox(3), 1 + DecimalBox(2))
assertEquals(DecimalBox(1), 3 - DecimalBox(2))
assertEquals(DecimalBox(8), 4 * DecimalBox(2))
assertEquals(DecimalBox(4), 8 / DecimalBox(2))
assertEquals(DecimalBox(1), 7 % DecimalBox(2))
assert(1 < DecimalBox(2))
assert(2 <= DecimalBox(2))
assert(3 > DecimalBox(2))
assert(2 == DecimalBox(2))
assert(2 != DecimalBox(3))
```

## How Decimal Uses It

`lyng.decimal` uses this same mechanism so that:

```lyng
import lyng.decimal

1 + 2.d
0.5 + 1.d
2 == 2.d
3 > 2.d
```

work naturally even though `Int` and `Real` themselves were not edited to know `BigDecimal`.

The shape is:

- `leftClass = Int` or `Real`
- `rightClass = BigDecimal`
- `commonClass = BigDecimal`
- convert built-ins into `BigDecimal`
- leave `BigDecimal` values unchanged

## Step-By-Step Pattern For Your Own Type

### 1. Pick the common class

Choose one class that will be the actual arithmetic domain.

For numeric-like types, that is usually your own class:

```lyng
class Rational(...)
```

### 2. Implement operators on that class

The common class should define the operations you plan to register.

Example:

```lyng
class Rational(val num: Int, val den: Int) {
    fun plus(other: Rational) = Rational(num * other.den + other.num * den, den * other.den)
    fun minus(other: Rational) = Rational(num * other.den - other.num * den, den * other.den)
    fun mul(other: Rational) = Rational(num * other.num, den * other.den)
    fun div(other: Rational) = Rational(num * other.den, den * other.num)
    fun compareTo(other: Rational) = (num * other.den) <=> (other.num * den)

    static fun fromInt(value: Int) = Rational(value, 1)
}
```

### 3. Register the mixed pair

```lyng
import lyng.operators

OperatorInterop.register(
    Int,
    Rational,
    Rational,
    [
        BinaryOperator.Plus,
        BinaryOperator.Minus,
        BinaryOperator.Mul,
        BinaryOperator.Div,
        BinaryOperator.Compare,
        BinaryOperator.Equals
    ],
    { x: Int -> Rational.fromInt(x) },
    { x: Rational -> x }
)
```

### 4. Use it

```lyng
assertEquals(Rational(3, 2), 1 + Rational(1, 2))
assert(Rational(3, 2) == Rational(3, 2))
assert(2 > Rational(3, 2))
```

## Registering More Than One Built-in Type

If you want both `Int + MyType` and `Real + MyType`, register both pairs explicitly:

```lyng
OperatorInterop.register(
    Int,
    MyType,
    MyType,
    [BinaryOperator.Plus, BinaryOperator.Compare, BinaryOperator.Equals],
    { x: Int -> MyType.fromInt(x) },
    { x: MyType -> x }
)

OperatorInterop.register(
    Real,
    MyType,
    MyType,
    [BinaryOperator.Plus, BinaryOperator.Compare, BinaryOperator.Equals],
    { x: Real -> MyType.fromReal(x) },
    { x: MyType -> x }
)
```

Each mixed pair is independent.

## Pure Lyng Registration

This mechanism is intentionally useful from pure Lyng code, not only from Kotlin-backed modules.

That means you can:

- declare a class in Lyng
- define its operators in Lyng
- register mixed operand bridges in Lyng

without touching compiler internals.

## Where To Register

Register once during module initialization.

Top-level module code is a good place:

```lyng
package my.rational
import lyng.operators

class Rational(...)

OperatorInterop.register(...)
```

That keeps registration close to the type declaration and makes importing the module enough to activate the interop.

## What Registration Does Not Do

The registry does not:

- invent operators your common class does not implement
- change the original `Int`, `Real`, or other built-ins
- automatically cover every class pair
- replace normal method overload resolution when the left-hand class already knows what to do

It only teaches Lyng how to bridge a specific mixed pair into a common class for the listed operators.

## Recommended Design Rules

If you want interop to feel natural:

- choose one obvious common class
- make conversions explicit and unsurprising
- implement `compareTo` if you want ordering operators
- register `Equals` whenever mixed equality should work
- keep the registered operator list minimal and accurate

For decimal-like semantics, also read [Decimal.md](Decimal.md).
