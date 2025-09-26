# String

# This document is for developer notes only

--------------------------


## Interpolation proposal

"""no $iterpolation"""
    
    val inpterpolation1 = "foo"
    "${interpolation1}"
    >>> "foo"

"$interpolation2"
"no $$ interpolatino"


## Regexp vs div / ?

```EBNF
regex_literal = "/", { regchar }, "/", [ flag ]
foag = "i" | "n"....
regchar = x`x`
```
