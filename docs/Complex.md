# Complex Numbers (`lyng.complex`)

`lyng.complex` adds a pure-Lyng `Complex` type backed by `Real` components.

Import it when you want ordinary complex arithmetic:

```lyng
import lyng.complex
```

## Construction

Use any of these:

```lyng
import lyng.complex

val a = Complex(1.0, 2.0)
val b = complex(1.0, 2.0)
val c = 2.i
val d = 3.re

assertEquals(Complex(1.0, 2.0), 1 + 2.i)
```

Convenience extensions:

- `Int.re`, `Real.re`: embed a real value into the complex plane
- `Int.i`, `Real.i`: create a pure imaginary value
- `cis(angle)`: shorthand for `cos(angle) + i sin(angle)`

## Core Operations

`Complex` supports:

- `+`
- `-`
- `*`
- `/`
- unary `-`
- `conjugate`
- `magnitude`
- `phase`

Mixed arithmetic with `Int` and `Real` is enabled through `lyng.operators`, so both sides work naturally:

```lyng
import lyng.complex

assertEquals(Complex(1.0, 2.0), 1 + 2.i)
assertEquals(Complex(1.5, 2.0), 1.5 + 2.i)
assertEquals(Complex(2.0, 2.0), 2.i + 2)
```

Mixed equality with built-in numeric types is intentionally not promised yet. Keep equality checks in the `Complex` domain for now.

## Transcendental Functions

For now, use member-style calls:

```lyng
import lyng.complex

val z = 1 + π.i
val w = z.exp()
val s = z.sin()
val r = z.sqrt()
```

This is deliberate. Lyng already has built-in top-level real-valued functions such as `exp(x)` and `sin(x)`, and imported modules do not currently replace those root bindings. So plain `exp(z)` is not yet the right extension mechanism for complex math.

## Design Scope

This module intentionally uses `Complex` with `Real` parts, not `Complex<T>`.

Reasons:

- the existing math runtime is `Real`-centric
- the operator interop registry works with concrete runtime classes
- transcendental functions (`exp`, `sin`, `ln`, `sqrt`) are defined over the `Real` math backend here

If Lyng later gets a more general numeric-trait or callable-overload registry, a generic algebraic `Complex<T>` can be revisited on firmer ground.
