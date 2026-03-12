# ImmutableSet built-in class

`ImmutableSet` is an immutable set of unique elements.
It implements [Collection] and [Iterable].

## Creating

    val a = ImmutableSet(1,2,3)
    val b = Set(1,2,3).toImmutable()
    val c = [1,2,3].toImmutableSet
    >>> void

## Converting

    val i = ImmutableSet(1,2,3)
    val m = i.toMutable()
    m += 4
    assertEquals( ImmutableSet(1,2,3), i )
    assertEquals( Set(1,2,3,4), m )
    >>> void

## Members

| name          | meaning                                             |
|---------------|-----------------------------------------------------|
| `size`        | number of elements                                  |
| `contains(x)` | membership test                                     |
| `+`, `union`  | union, returns new immutable set                    |
| `-`, `subtract` | subtraction, returns new immutable set            |
| `*`, `intersect` | intersection, returns new immutable set         |
| `toMutable()` | create mutable copy                                 |

[Collection]: Collection.md
[Iterable]: Iterable.md
