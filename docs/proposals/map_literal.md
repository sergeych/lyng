
# Map literals — refined proposal

[//]: # (excludeFromIndex)

Implement JavaScript-like literals for maps. The syntax and semantics align with named arguments in function calls, but map literals are expressions that construct `Map` values.

Keys can be either:
- string literals: `{ "some key": value }`, or
- identifiers: `{ name: expr }`, where the key becomes the string `"name"`.

Identifier shorthand inside map literals is supported:
- `{ name: }` desugars to `{ "name": name }`.

Property access sugar is not provided for maps: use bracket access only, e.g. `m["a"]`, not `m.a`.

Examples:

```lyng
val x = 2
val m = { "a": 1, x: x*10, y: }
assertEquals(1, m["a"])      // string-literal key
assertEquals(20, m["x"])     // identifier key
assertEquals(2, m["y"])      // identifier shorthand
```

Spreads (splats) in map literals are allowed and merged left-to-right with “rightmost wins” semantics:

```lyng
val base = { a: 1, b: 2 }
val m = { a: 0, ...base, b: 3, c: 4 }
assertEquals(1, m["a"])  // base overwrites a:0
assertEquals(3, m["b"])  // literal overwrites spread
assertEquals(4, m["c"])  // new key
```

Trailing commas are allowed (optional):

```lyng
val m = {
  "a": 1,
  b: 2,
  ...other,
}
```

Duplicate keys among literal entries (including identifier shorthand) are a compile-time error:

```lyng
{ foo: 1, "foo": 2 }   // error: duplicate key "foo"
{ foo:, foo: 2 }        // error: duplicate key "foo"
```

Spreads are evaluated at runtime. Overlaps from spreads are resolved by last write wins. If a spread is not a map, or yields a map with non-string keys, it’s a runtime error.

Merging with `+`/`+=` and entries:

```lyng
("1" => 10) + ("2" => 20)     // Map("1"=>10, "2"=>20)
{ "1": 10 } + ("2" => 20)     // same
{ "1": 10 } + { "2": 20 }    // same

var m = { "a": 1 }
m += ("b" => 2)                 // m = { "a":1, "b":2 }
```

Rightmost wins on duplicates consistently across spreads and merges. All map merging operations require string keys; encountering a non-string key during merge is a runtime error.

Empty map literal `{}` is not supported to avoid ambiguity with blocks/lambdas. Use `Map()` for an empty map.

Lambda disambiguation
- A `{ ... }` with typed lambda parameters must have a top-level `->` in its header. The compiler disambiguates by looking for a top-level `->`. If none is found, it attempts to parse a map literal; if that fails, it is parsed as a lambda or block.

Grammar (EBNF)

```
ws                    = zero or more whitespace (incl. newline/comments)
map_literal           = '{' ws map_entries ws '}'
map_entries           = map_entry ( ws ',' ws map_entry )* ( ws ',' )?
map_entry             = map_key ws ':' ws map_value_opt
                      | '...' ws expression
map_key               = string_literal | ID
map_value_opt         = expression | ε   // ε allowed only if map_key is ID
```

Notes:
- Identifier shorthand (`id:`) is allowed only for identifiers, not string-literal keys.
- Spreads accept any expression; at runtime it must yield a `Map` with string keys.
- Duplicate keys are detected at compile time among literal keys; spreads are merged at runtime with last-wins.

Rationale
- The `{ name: value }` style is familiar and ergonomic.
- Disambiguation with lambdas leverages the required `->` in typed lambda headers.
- Avoiding `m.a` sidesteps method/field shadowing and keeps semantics clear.

