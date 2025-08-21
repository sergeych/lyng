# List built-in class

Mutable list of any objects.

It's class in Lyng is `List`:

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

There is a shortcut for the last:

    val list = [10, 20, 30]
    [list.last, list.lastIndex]
    >>> [30,2]

__Important__ negative indexes works wherever indexes are used, e.g. in insertion and removal methods too.

## Concatenation

You can concatenate lists or iterable objects:

    assert( [4,5] + [1,2] == [4,5,1,2])
    assert( [4,5] + (1..3) == [4, 5, 1, 2, 3])
    >>> void

## Appending

To append to lists, use `+=` with elements, lists and any [Iterable] instances, but beware it will
concatenate [Iterable] objects instead of appending them. To append [Iterable] instance itself, use `list.add`:

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

## Removing elements

List is mutable, so it is possible to remove its contents. To remove a single element
by index use:

    assertEquals( [1,2,3].removeAt(1), [1,3] )
    assertEquals( [1,2,3].removeAt(0), [2,3] )
    assertEquals( [1,2,3].removeLast(), [1,2] )
    >>> void

There is a way to remove a range (see [Range] for more on ranges):

    assertEquals( [1, 4], [1,2,3,4].removeRange(1..2))
    assertEquals( [1, 4], [1,2,3,4].removeRange(1..<3))
    >>> void

Open end ranges remove head and tail elements:

    assertEquals( [3, 4, 5], [1,2,3,4,5].removeRange(..1))
    assertEquals( [3, 4, 5], [1,2,3,4,5].removeRange(..<2))
    assertEquals( [1, 2], [1,2,3,4,5].removeRange( (2..) ))
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

| name                          | meaning                               | type        |
|-------------------------------|---------------------------------------|-------------|
| `size`                        | current size                          | Int         |
| `add(elements...)`            | add one or more elements to the end   | Any         |
| `insertAt(index,elements...)` | insert elements at position           | Int, Any    |
| `removeAt(index)`             | remove element at position            | Int         |
| `remove(from,toNonInclusive)` | remove range from (incl) to (nonincl) | Int, Int    |
| `remove(Range)`               | remove range                          | Range       |
| `removeLast()`                | remove last element                   |             |
| `removeLast(n)`               | remove n last elements                | Int         |
| `contains(element)`           | check the element is in the list (1)  |             |
| `[index]`                     | get or set element at index           | Int         |
| `[Range]`                     | get slice of the array (copy)         | Range       |
| `+=`                          | append element(s)                     | List or Obj |

(1)
: optimized implementation that override `Array` one

(2)
: `+=` append either a single element, or all elements if the List or other Iterable
instance is appended. If you want to append an Iterable object itself, use `add` instead.

It inherits from [Iterable] too.

## Member inherited from Array

| name             | meaning                        | type  |
|------------------|--------------------------------|-------|
| `last`           | last element (throws if empty) |       |
| `lastOrNull`     | last element or null           |       |
| `lastIndex`      |                                | Int   |
| `indices`        | range of indexes               | Range |
| `contains(item)` | test that item is in the list  |       |

(1)
: end-inclisiveness allows to use negative indexes to, for exampe, remove several last elements, like
`list.removeRangeInclusive(-2, -1)` will remove two last elements.


[Range]: Range.md
[Iterable]: Iterable.md