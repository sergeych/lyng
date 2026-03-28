# Array

It's an interface if the [Collection] that provides indexing access, like `array[3] = 0`.
Array therefore implements [Iterable] too. Well known implementations of `Array` are
[List] and [ImmutableList].

The language-level bracket syntax supports one or more selectors:

- `value[i]`
- `value[i, j]`

Concrete array-like types decide what selectors they accept. Built-in list-like arrays use one selector at a time; custom types such as matrices may interpret multiple selectors.

Array adds the following methods:

## Binary search

When applied to sorted arrays, binary search allow to quicly find an index of the element in the array, or where to insert it to keep order:

    val coll = [1,2,3,4,5]
    assertEquals( 2, coll.binarySearch(3) )
    assertEquals( 0, coll.binarySearch(1) )
    assertEquals( 4, coll.binarySearch(5) )

    val src = (1..50).toList().shuffled()
    val result = []
    for( x in src ) {
        val i = result.binarySearch(x)
        assert( i < 0 )
        result.insertAt(-i-1, x)
    }
    assertEquals( src.sorted(), result )
    >>> void

So `binarySearch(x)` returns:

- index of `x`, a non-negative number
- negative: `x` not found, but if inserted at position `-returnedValue-1` will leave array sorted.

To pre-sort and array use `Iterable.sorted*` or in-place `List.sort*` families, see [List] and [Iterable] docs.

[Collection]: Collection.md
[Iterable]: Iterable.md
[List]: List.md
[ImmutableList]: ImmutableList.md
