# ImmutableList built-in class

`ImmutableList` is an immutable, indexable list value.
It implements [Array], therefore [Collection] and [Iterable].

Use it when API contracts require a list that cannot be mutated through aliases.

## Creating

    val a = ImmutableList(1,2,3)
    val b = [1,2,3].toImmutable()
    val c = (1..3).toImmutableList()
    >>> void

## Converting

    val i = ImmutableList(1,2,3)
    val m = i.toMutable()
    m += 4
    assertEquals( ImmutableList(1,2,3), i )
    assertEquals( [1,2,3,4], m )
    >>> void

## Members

| name          | meaning                                 |
|---------------|-----------------------------------------|
| `size`        | number of elements                      |
| `[index]`     | element access by index                 |
| `[Range]`     | immutable slice                         |
| `+`           | append element(s), returns new immutable list |
| `-`           | remove element(s), returns new immutable list |
| `toMutable()` | create mutable copy                     |

[Array]: Array.md
[Collection]: Collection.md
[Iterable]: Iterable.md
