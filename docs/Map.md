# Map

Map is a mutable collection of key-value pars, where keys are unique. Maps could be created with
constructor or `.toMap` methods. When constructing from a list, each list item must be a [Collection] with exactly 2 elements, for example, a [List].

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
    assertThrows { map["nonexistent"] }
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

It is possible also to get values as [List] (values are not unique):

    val map = Map( ["foo", 1], ["bar", "buzz"], [42, "answer"] )
    assertEquals(map.values, [1, "buzz", "answer"] )
    >>> void


[Collection](Collection.md)