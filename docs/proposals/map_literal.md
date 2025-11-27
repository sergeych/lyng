Note for AI. please ignore this file until this remark is removed.

# Map literals proposal

Note to the AI: please ignore this file until this note is removed.

Implement javascript-like literals for maps. It uses the similar syntax already adopted for named arguments in function calls so it should be organic and familiar, as the purpose is similar.

String literal keys

```lyng
    val m = { a: "foo", b: "bar" }
    assertEqual(m.a, "foo")
    assertEqual(m.b, "bar")
```

The map literal is a map with keys that are string literals only. This is important. In a relatively rare case when keys are calculated, or extravagant but still possible case when keys are of different types, literal could be combined with "=>":

```lyng
    val k1 = "bar"
    val m = { "foo": 123 } + k1 => "buzz"
    // this is same as Map("foo" => 123) + Map("bar" => k2) but can be optimized by compiler
    assertEqual(m["foo"], 123)
    assertEqual(m["bar"], "buzz")
```

The lambda syntax is different, it can start with the `map_lteral_start` above, it should produce compile time error, so we can add map literals of this sort.

Also, we will allow splats in map literals:

```
    val m = { foo: "bar", ...{bar: "buzz"} }
    assertEquals("bar",m["foo"])
    assertEquals("buzz", m["bar"])
```

When the literal argument and splats are used together, they must be evaluated left-to-right with allowed overwriting
between named elements and splats, allowing any combination and multiple splats:

```
    val m = { foo: "bar", ...{bar: "buzz"}, ...{foo: "foobar"}, bar: "bar" }
    assertEquals("foobar",m["foo"])
    assertEquals("bar", m["bar"])
```

Still we disallow duplicating _string literals_:

```
    // this is an compile-time exception:
    { foo: 1, bar: 2, foo: 3 }
```

Special syntax allows to insert key-value pair from the variable which name should be the key, and content is value:

```
    val foo = "bar"
    val bar = "buzz"
    assertEquals( {foo: "bar", bar: "buzz"}, { foo, bar } )
```


So, summarizing, overwriting/duplication rules are:

- string literals can't duplicate 
- splats add or update content, effectively overwrite preceding content,
- string literals overwrite content received from preceding splats (as no duplication string literal keys allowed)
- the priority and order is left-to-right, rightmost wins.
- var inclusion is treated as form of the literal

This approach resolves the ambiguity from lambda syntax, as

```ebnf
    ws = zero or more whitespace characters including newline
    map_literal start = "{", ws, (s1 | s2 | s3)
    s1 = string_literal, ws, ":", ws, expression
    s2 = "...", string_literal
    s3 = string_literal, ("," | "}")
```
is not a valid lambda beginning.

