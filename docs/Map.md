# Map

Map is a mutable collection of key-value pairs, where keys are unique. You can create maps in two ways:
- with the constructor `Map(...)` or `.toMap()` helpers; and
- with map literals using braces: `{ "key": value, id: expr, id: }`.

When constructing from a list, each list item must be a [Collection] with exactly 2 elements, for example, a [List].

Important thing is that maps can't contain `null`: it is used to return from missing elements.

Constructed map instance is of class `Map` and implements `Collection` (and therefore `Iterable`).

    val oldForm = Map( "foo" => 1, "bar" => "buzz" )
    assert(oldForm is Map)
    assert(oldForm.size == 2)
    assert(oldForm is Iterable)
    >>> void

Notice usage of the `=>` operator that creates `MapEntry`, which also implements [Collection] of
two items, first, at index zero, is a key, second, at index 1, is the value. You can use lists too.
Map keys could be any objects (hashable, e.g. with reasonable hashCode, most of standard types are). You can access elements with indexing operator:

    val map = Map( ["foo", 1], ["bar", "buzz"], [42, "answer"] )
    assert( map["bar"] == "buzz")
    assert( map[42] == "answer" )
    assertEquals( null, map["nonexisting"])
    assert( map.getOrNull(101) == null )
    assert( map.getOrPut(911) { "nine-eleven" } == "nine-eleven" )
    // now 91 entry is set:
    assert( map[911] == "nine-eleven" )
    map["foo"] = -1
    assert( map["foo"] == -1)
    >>> void

## Map literals { ... }

Lyng supports JavaScript-like map literals. Keys can be string literals or identifiers, and there is a handy identifier shorthand:

- String key: `{ "a": 1 }`
- Identifier key: `{ foo: 2 }` is the same as `{ "foo": 2 }`
- Identifier shorthand: `{ foo: }` is the same as `{ "foo": foo }`

Access uses brackets: `m["a"]`.

    val x = 10
    val y = 10
    val m = { "a": 1, x: x * 2, y: }
    assertEquals(1, m["a"])      // string-literal key
    assertEquals(20, m["x"])     // identifier key
    assertEquals(10, m["y"])     // identifier shorthand expands to y: y
    >>> void

Trailing commas are allowed for nicer diffs and multiline formatting:

    val m = {
        "a": 1,
        b: 2,
    }
    assertEquals(1, m["a"]) 
    assertEquals(2, m["b"]) 
    >>> void

Empty `{}` is reserved for blocks/lambdas; use `Map()` for an empty map.

To remove item from the collection. use `remove`. It returns last removed item or null. Be careful if you 
hold nulls in the map - this is not a recommended practice when using `remove` returned value. `clear()` 
removes all.

    val map = Map( "foo" => 1, "bar" => "buzz", [42, "answer"] )
    assertEquals( 1, map.remove("foo") )
    assert( map.getOrNull("foo") == null)
    assert( map.size == 2 )
    map.clear()
    assert( map.size == 0 )
    >>> void

Map implements [contains] method that checks _the presence of the key_ in the map:

    val map = Map( ["foo", 1], ["bar", "buzz"], [42, "answer"] )
    assert( "foo" in map )
    assert( "answer" !in map )
    >>> void

To iterate maps it is convenient to use `keys` method that returns [Set] of keys (keys are unique:

    val map = Map( ["foo", 1], ["bar", "buzz"], [42, "answer"] )
    for( k in map.keys ) println(map[k])
    >>> 1
    >>> buzz
    >>> answer
    >>> void

Or iterate its key-value pairs that are instances of [MapEntry] class:

    val map = Map( ["foo", 1], ["bar", "buzz"], [42, "answer"] )
    for( entry in map ) {
        println("map[%s] = %s"(entry.key, entry.value))
    }
    void
    >>> map[foo] = 1
    >>> map[bar] = buzz
    >>> map[42] = answer
    >>> void

There is a shortcut to use `MapEntry` to create maps: operator `=>` which creates `MapEntry`:

    val entry = "answer" => 42
    assert( entry is MapEntry )
    >>> void

And you can use it to construct maps:

    val map = Map( "foo" => 1, "bar" => 22)
    assertEquals(1, map["foo"])
    assertEquals(22, map["bar"])
    >>> void

Or use `.toMap` on anything that implements [Iterable] and which elements implements [Array] with 2 elements size, for example, `MapEntry`:

    val map = ["foo" => 1, "bar" => 22].toMap()
    assert( map is Map )
    assertEquals(1, map["foo"])
    assertEquals(22, map["bar"])
    >>> void


It is possible also to get values as [List] (values are not unique):

    val map = Map( ["foo", 1], ["bar", "buzz"], [42, "answer"] )
    assertEquals(map.values, [1, "buzz", "answer"] )
    >>> void

Map could be tested to be equal: when all it key-value pairs are equal, the map
is equal.

    val m1 = Map(["foo", 1])
    val m2 = Map(["foo", 1])
    val m3 = Map(["foo", 2])
    assert( m1 == m2 )
    // but the references are different:
    assert( m1 !== m2 )
    // different maps:
    assert( m1 != m3 )
    >>> void

## Spreads and merging

Inside map literals you can spread another map with `...` and items will be merged left-to-right; rightmost wins:

    val base = { a: 1, b: 2 }
    val m = { a: 0, ...base, b: 3, c: 4 }
    assertEquals(1, m["a"])  // base overwrites a:0
    assertEquals(3, m["b"])  // literal overwrites spread
    assertEquals(4, m["c"])  // new key
    >>> void

Maps and entries can also be merged with `+` and `+=`:

    val m1 = ("x" => 1) + ("y" => 2)
    assertEquals(1, m1["x"])
    assertEquals(2, m1["y"])

    val m2 = { "a": 10 } + ("b" => 20)
    assertEquals(10, m2["a"])
    assertEquals(20, m2["b"])

    var m3 = { a: 1 }
    m3 += ("b" => 2)
    assertEquals(1, m3["a"])
    assertEquals(2, m3["b"])
    >>> void

Notes:
- Map literals always use string keys (identifier keys are converted to strings).
- Spreads inside map literals and `+`/`+=` merges allow any objects as keys.
- When you need computed or non-string keys, use the constructor form `Map(...)`, map literals with computed keys (if supported), or build entries with `=>` and then merge.

[Collection](Collection.md)