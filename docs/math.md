# Operators

## Precedence

Same as in C++.

|     Priority     | Operations                           |
|:----------------:|--------------------------------------|
| **Highest**<br>0 | power, not, calls, indexing, dot,... |
|        1         | `%` `*` `/`                          |
|        2         | `+` `-`                              |
|        3         | bit shifts (NI)                      |
|        4         | `<=>` (1)                            |
|        5         | `<=` `>=` `<` `>`                    |
|        6         | `==` `!=`                            |
|        7         | bitwise and `&` (NI)                 |
|        9         | bitwise or `\|` (NI)                 |
|        10        | `&&`                                 |
|  11<br/>lowest   | `\|\|`                               |

(NI) 
: not yet implemented.

(1)
: Shuttle operator: `a <=> b` returns 0 if a == b, negative Int if a < b and positive Int otherwise. It is necessary to override shuttle operator to make a class comparable. 

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

| name     | description                                            |
|----------|--------------------------------------------------------|
| floor(x) | Computes the largest integer value not greater than x  |
| ceil(x)  | Computes the least integer value value not less than x |
| round(x) | Rounds x                                               |
| abs(x)   | absolute value, Int for integer x, Real otherwise      |
|          |                                                        |

## Mathematical functions

| name      | meaning                                              |
|-----------|------------------------------------------------------|
| sin(x)    | sine                                                 |
| cos(x)    | cosine                                               |
| tan(x)    | tangent                                              |
| asin(x)   | $sin^-1(x)$                                          |
| acos(x)   | $cos^-1(x)$                                          |
| atan(x)   | $tg^-1(x)$                                           |
| sinh(x)   | hyperbolic sine                                      |
| cosh(x)   | hyperbolic cosine                                    |
| tanh(x)   | hyperbolic tangent                                   |
| asinh(x)  | $sinh^-1(x)$                                         |
| acosh(x)  | $cosh^-1(x)$                                         |
| atanh(x)  | $tgh^-1(x)$                                          |
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
    >>> void

## Scientific constant

| name                 | meaning      |
|----------------------|--------------|
| `Math.PI: Real` or π | 3.1415926... |
|                      |              |
|                      |              |
|                      |              |
