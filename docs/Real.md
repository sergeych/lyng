# Real built-in class

Class that supports double-precision math. Woks much like double in other languages. We honor no floats, so it is '
Real'.

It's class in Lyng is `Real`:

    (π/2)::class
    >>> Real

you can use it's class to ensure type:

    1.71::class == Real
    >>> true

## Member functions

| name            | meaning                                                     | type |
|-----------------|-------------------------------------------------------------|------|
| `.roundToInt()` | round to nearest int like round(x)                          | Int  |
| `.toInt()`      | convert integer part of real to `Int` dropping decimal part | Int  |
| `.isInfinite()` | true when the value is `Infinity` or `-Infinity`            | Bool |
| `.isNaN()`      | true when the value is `NaN`                                | Bool |
| `.clamp(range)` | clamp value within range boundaries                         | Real |
|                 |                                                             |      |
|                 |                                                             |      |
|                 |                                                             |      |
|                 |                                                             |      |
