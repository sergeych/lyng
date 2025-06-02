# List built-in class

Mutable list of any objects.

It's class in Lying is `List`:

    [1,2,3]::class
    >>> List

you can use it's class to ensure type:

    []::class == List
    >>> true

## Indexing

indexing is zero-based, as in C/C++/Java/Kotlin, etc.

    val list = [10, 20, 30]
    list[1]
    >>> 20

Using negative indexes has a special meaning: _offset from the end of the list_:

    val list = [10, 20, 30]
    list[-1]
    >>> 30

__Important__ negative indexes works wherever indexes are used, e.g. in insertion and removal methods too.

## Concatenation

You can concatenate lists or iterable objects:

    assert( [4,5] + [1,2] == [4,5,1,2])
    assert( [4,5] + (1..3) == [4, 5, 1, 2, 3])
    >>> void

## Appending

To append to lists, use `+=` with elements, lists and any [Iterable] instances, but beware it will concatenate [Iterable] objects instead of appending them. To append [Iterable] instance itself, use `list.add`:

    var list = [1, 2]
    val other = [3, 4]

    // appending lists is clear:
    list += other
    assert( list == [1, 2, 3, 4] )
    
    // but appending other Iterables could be confusing:
    list += (10..12)
    assert( list == [1, 2, 3, 4, 10, 11, 12])

    // now adding list as sublist:
    list.add(other)
    assert( list == [1, 2, 3, 4, 10, 11, 12, [3,4]])

    >>> void



## Comparisons

    assert( [1, 2] != [1, 3])
    assert( [1, 2, 3] > [1, 2])
    assert( [1, 3] > [1, 2, 3])
    assert( [1, 2, 3] == [1, 2, 3])
    assert( [1, 2, 3] != [1, 2, "three"])
    // note that in the case above objects are referentially different:
    assert( [1, 2, 3] !== [1, 2, 3])
    >>> void

## Members

| name                              | meaning                             | type     |
|-----------------------------------|-------------------------------------|----------|
| `size`                            | current size                        | Int      |
| `add(elements...)`                | add one or more elements to the end | Any      |
| `addAt(index,elements...)`        | insert elements at position         | Int, Any |
| `removeAt(index)`                 | remove element at position          | Int      |
| `removeRangeInclusive(start,end)` | remove range, inclusive (1)         | Int, Int |
|                                   |                                     |          |

(1)
: end-inclisiveness allows to use negative indexes to, for exampe, remove several last elements, like `list.removeRangeInclusive(-2, -1)` will remove two last elements.


# Notes

Could be rewritten using array as a class but List as the interface
