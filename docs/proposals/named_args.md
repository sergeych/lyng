# Named arguments proposal

Extend function/method calls to allow setting arguments by name using colon syntax at call sites. This is especially convenient with many parameters and default values.

Examples:

```lyng
    fun test(a="foo", b="bar", c="bazz") { [a, b, c] }

    assertEquals(test(b: "b"), ["foo", "b", "bazz"])
    assertEquals(test("a", c: "c"), ["a", "bar", "c"])
```

Rules:

- Named arguments are optional. If named arguments are present, their order is not important.
- Named arguments must follow positional arguments; positional arguments cannot follow named ones (the only exception is the syntactic trailing block outside parentheses, see below).
- A named argument cannot reassign a parameter already set positionally.
- If the last parameter is already assigned by a named argument (or named splat), the trailing-lambda rule must NOT apply: a following `{ ... }` after the call is an error.

Rationale for using `:` instead of `=` in calls: in Lyng, assignment `=` is an expression; using `:` avoids ambiguity and keeps declarations (`name: Type`) distinct from call sites, where casting uses `as` / `as?`.

Migration note: earlier drafts/examples used `name = value`. The final syntax is `name: value` at call sites.

## Extended call argument splats: named splats

With named arguments, splats (`...`) are extended to support maps as named splats. When a splat evaluates to a Map, its entries provide name→value assignments:

```lyng
    fun test(a="a", b="b", c="c", d="d") { [a, b, c, d] }

    assertEquals(test("A?", ...Map("d" => "D!", "b" => "B!")), ["A?", "B!", "c", "D!"])
```

Constraints for named splats:

- Only string keys are allowed in map splats; otherwise, a clean error is thrown.
- Named splats cannot reassign parameters already set (positionally or by earlier named arguments/splats).
- Named splats follow the same ordering as named arguments: they must appear after all positional arguments and positional splats.

## Trailing-lambda interaction

Lyng supports a syntactic trailing block after a call: `f(args) { ... }`. With named args/splats, if the last parameter is already assigned by name, the trailing block must not apply and the call is an error:

```lyng
    fun f(x, onDone) { onDone(x) }
    f(x: 1) { 42 }    // ERROR: last parameter already assigned by name
    f(1) { 42 }       // OK
```

## Errors (non-exhaustive)

- Positional argument after any named argument inside parentheses: error.
- Positional splat after any named argument: error.
- Duplicate named assignment (directly or via map splats): error.
- Unknown parameter name in a named argument/splat: error.
- Map splat with non-string keys: error.
- Attempt to target the ellipsis parameter by name: error.

## Notes

- Declarations continue to use `:` for types, while call sites use `:` for named arguments and `as` / `as?` for type casts/checks.





