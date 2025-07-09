# Map

Map is a mutable collection of key-value pars, where keys are unique. Maps could be created with
constructor or `.toMap` methods. When constructing from a list, each list item must be a [Collection] with exactly 2 elements, for example, a [List].

Important thing is that maps can't contain `null`: it is used to return from missing elements.

Constructed map instance is of class `Map` and implements `Collection` (and therefore `Iterable`)

    val map = Map( ["foo", 1], ["bar", "buzz"] )
    assert(map is Map)
    assert(map.size == 2)
    assert(map is Iterable)
    >>> void

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

To remove item from the collection. use `remove`. It returns last removed item or null. Be careful if you 
hold nulls in the map - this is not a recommended practice when using `remove` returned value. `clear()` 
removes all.

    val map = Map( ["foo", 1], ["bar", "buzz"], [42, "answer"] )
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

[Collection](Collection.md)