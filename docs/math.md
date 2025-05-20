# Operators

## Precedence

Same as in C++.

|     Priority     | Operations                           |
|:----------------:|--------------------------------------|
| **Highest**<br>0 | power, not, calls, indexing, dot,... |
|        1         | `%` `*` `/`                          |
|        2         | `+` `-`                              |
|        3         | bit shifts (NI)                      |
|        4         | `<=>` (NI)                           |
|        5         | `<=` `>=` `<` `>`                    |
|        6         | `==` `!=`                            |
|        7         | bitwise and `&` (NI)                 |
|        9         | bitwise or `\|` (NI)                 |
|        10        | `&&`                                 |
|  11<br/>lowest   | `\|\|`                               |

- (NI) stands for not yet implemented.

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
|          |                                                        |
|          |                                                        |

## Scientific functions

| name                | meaning |
|---------------------|---------|
| `sin(x:Real): Real` | sine    |
|                     |         |
|                     |         |
|                     |         |

For example: 

    sin(π/2)
    >>> 1.0

## Scientific constant

| name                 | meaning      |
|----------------------|--------------|
| `Math.PI: Real` or π | 3.1415926... |
|                      |              |
|                      |              |
|                      |              |
