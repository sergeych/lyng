# ImmutableMap built-in class

`ImmutableMap` is an immutable map of key-value pairs.
It implements [Collection] and [Iterable] of [MapEntry].

## Creating

    val a = ImmutableMap("a" => 1, "b" => 2)
    val b = Map("a" => 1, "b" => 2).toImmutable()
    val c = ["a" => 1, "b" => 2].toImmutableMap
    >>> void

## Converting

    val i = ImmutableMap("a" => 1)
    val m = i.toMutable()
    m["a"] = 2
    assertEquals( 1, i["a"] )
    assertEquals( 2, m["a"] )
    >>> void

## Members

| name            | meaning                                  |
|-----------------|------------------------------------------|
| `size`          | number of entries                        |
| `[key]`         | get value by key, or `null` if absent    |
| `getOrNull(key)`| same as `[key]`                          |
| `keys`          | list of keys                             |
| `values`        | list of values                           |
| `+`             | merge (rightmost wins), returns new immutable map |
| `toMutable()`   | create mutable copy                      |

[Collection]: Collection.md
[Iterable]: Iterable.md
[MapEntry]: Map.md
