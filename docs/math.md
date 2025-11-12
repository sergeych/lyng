# Operators

## Precedence

Same as in C++.

|     Priority     | Operations                           |
|:----------------:|--------------------------------------|
| **Highest**<br>0 | power, not, calls, indexing, dot,... |
|        1         | `%` `*` `/`                          |
|        2         | `+` `-`                              |
|        3         | bit shifts `<<` `>>`                 |
|        4         | `<=>` (1)                            |
|        5         | `<=` `>=` `<` `>`                    |
|        6         | `==` `!=`                            |
|        7         | bitwise and `&`                      |
|        8         | bitwise xor `^`                      |
|        9         | bitwise or `\|`                      |
|        10        | `&&`                                 |
|  11<br/>lowest   | `\|\|`                               |

Bitwise operators
: available only for `Int` values. For mixed `Int`/`Real` numeric expressions, bitwise operators are not defined.

Bitwise NOT `~x`
: unary operator that inverts all bits of a 64‑bit signed integer (`Int`). It follows two's‑complement rules, so
  `~x` is numerically equal to `-(x + 1)`. Examples: `~0 == -1`, `~1 == -2`, `~(-1) == 0`.

Examples:

```
5 & 3    // -> 1
5 | 3    // -> 7
5 ^ 3    // -> 6
~0       // -> -1
1 << 3   // -> 8
8 >> 3   // -> 1
```

Notes:
- Shifts operate on 64-bit signed integers (`Int` is 64-bit). Right shift `>>` is arithmetic (sign-propagating).
- Shift count is masked to the range 0..63, similar to the JVM/Kotlin behavior (e.g., `1 << 65` equals `1 << 1`).

(1)
: Shuttle operator: `a <=> b` returns 0 if a == b, negative Int if a < b and positive Int otherwise. It is necessary to
override shuttle operator to make a class comparable.

## Operators

`+ - * / % `: if both operand is `Int`, calculates as int. Otherwise, as real:

    // integer division:
    3 / 2
    >>> 1

but:

    3 / 2.0
    >>> 1.5

## Round and range

The following functions return its argument if it is `Int`,
or transformed `Real` otherwise.

| name           | description                                            |
|----------------|--------------------------------------------------------|
| floor(x)       | Computes the largest integer value not greater than x  |
| ceil(x)        | Computes the least integer value value not less than x |
| round(x)       | Rounds x                                               |
| x.roundToInt() | shortcut to `round(x).toInt()`                         |

## Lyng math functions

| name      | meaning                                              |
|-----------|------------------------------------------------------|
| sin(x)    | sine                                                 |
| cos(x)    | cosine                                               |
| tan(x)    | tangent                                              |
| asin(x)   | $sin^{-1}(x)$                                          |
| acos(x)   | $cos^{-1}(x)$                                          |
| atan(x)   | $tg^{-1}(x)$                                           |
| sinh(x)   | hyperbolic sine                                      |
| cosh(x)   | hyperbolic cosine                                    |
| tanh(x)   | hyperbolic tangent                                   |
| asinh(x)  | $sinh^{-1}(x)$                                         |
| acosh(x)  | $cosh^{-1}(x)$                                         |
| atanh(x)  | $tgh^{-1}(x)$                                          |
| ln(x)     | $ln(x)$, $ log_e(x) $                                |
| exp(x)    | $e^x$                                                |
| log10(x)  | $log_{10}(x)$                                        |
| pow(x, y) | ${x^y}$                                              |
| sqrt(x)   | $ \sqrt {x}$                                         |
| abs(x)    | absolute value of x. Int if x is Int, Real otherwise |

For example:

    assert( sin(π/2) == 1.0)
    assert( cos(π/2) < 0.000001)
    assert( abs(ln(exp(1))) - 1 < 0.00001)

    // abs() keeps the argument type:
    assert( abs(-1) is Int)
    assert( abs(-2.21) == 2.21 )
    >>> void

## Scientific constant

| name                 | meaning      |
|----------------------|--------------|
| `Math.PI: Real` or π | 3.1415926... |
|                      |              |
|                      |              |
|                      |              |
