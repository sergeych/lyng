# List built-in class

Mutable list of any objects.

It's class in Ling is `List`:

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

    assert( [4,5] + [1,2] == [4,5,1,2])
    >>> void

## Comparisons

    assert( [1, 2] != [1, 3])
    assert( [1, 2, 3] > [1, 2])
    assert( [1, 3] > [1, 2, 3])
    assert( [1, 2, 3] == [1, 2, 3])
    // note that in the case above objects are referentially different:
    assert( [1, 2, 3] !== [1, 2, 3])
    >>> void

## Members

| name                       | meaning                                      | type     |
|----------------------------|----------------------------------------------|----------|
| `size`                     | current size                                 | Int      |
| `add(elements...)`         | add one or more elements to the end          | Any      |
| `addAt(index,elements...)` | insert elements at position                  | Int, Any |
| `removeAt(index)`          | remove element at position                   | Int      |
| `removeAt(start,end)`      | remove range, start inclusive, end exclusive | Int, Int |
|                            |                                              |          |
