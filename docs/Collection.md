# Collection

Is a [Iterable] with known `size`, a finite [Iterable]:

    class Collection : Iterable {
        val size
    }

`Collection` is a read/traversal contract shared by mutable and immutable collections.
Concrete collection classes:

- Mutable: [List], [Set], [Map]
- Immutable: [ImmutableList], [ImmutableSet], [ImmutableMap]

| name                   | description                                          |
|------------------------|------------------------------------------------------|

(1)
: `comparator(a,b)` should return -1 if `a < b`, +1 if `a > b` or zero.

See [List], [Set], [Iterable] and [Efficient Iterables in Kotlin Interop](EfficientIterables.md)

[Iterable]: Iterable.md
[List]: List.md
[Set]: Set.md
[Map]: Map.md
[ImmutableList]: ImmutableList.md
[ImmutableSet]: ImmutableSet.md
[ImmutableMap]: ImmutableMap.md
