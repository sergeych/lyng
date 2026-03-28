# Decimal Math TODO

These stdlib math helpers currently accept `Decimal`, but some still use the temporary compatibility path

`Decimal -> Real -> host math -> Decimal`

instead of a native decimal implementation.

## Still Using The Temporary Real Bridge

- `sin(x)`
- `cos(x)`
- `tan(x)`
- `asin(x)`
- `acos(x)`
- `atan(x)`
- `sinh(x)`
- `cosh(x)`
- `tanh(x)`
- `asinh(x)`
- `acosh(x)`
- `atanh(x)`
- `exp(x)`
- `ln(x)`
- `log10(x)`
- `log2(x)`
- `sqrt(x)`
- `pow(x, y)` when the decimal case is not reducible to an integral exponent

## Already Native For Decimal

- `abs(x)`
- `floor(x)`
- `ceil(x)`
- `round(x)`
- `pow(x, y)` with integral exponent `y`
