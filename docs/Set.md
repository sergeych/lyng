# List built-in class

Mutable set of any objects: a group of different objects, no repetitions.
Sets are not ordered, order of appearance does not matter.

    val set = Set(1,2,3, "foo")
    assert( 1 in set )
    assert( "foo" in set)
    assert( "bar" !in set)
    >>> void

## Set is collection and therefore [Iterable]:

    assert( Set(1,2) is Set)
    assert( Set(1,2) is Iterable)
    assert( Set(1,2) is Collection)
    >>> void

So it supports all methods from [Iterable]; set is not, though, an [Array] and has
no indexing. Use [set.toList] as needed.

## Set operations

    // Union
    assertEquals( Set(1,2,3,4), Set(3, 1) + Set(2, 4))

    // intersection
    assertEquals( Set(1,4), Set(3, 1, 4).intersect(Set(2, 4, 1)) )
    // or simple
    assertEquals( Set(1,4), Set(3, 1, 4) * Set(2, 4, 1) )

    // To find collection elements not present in another collection, use the 
    // subtract() or `-`:
    assertEquals( Set( 1, 2), Set(1, 2, 4, 3) - Set(3, 4))

    >>> void

## Adding elements

    var s = Set()
    s += 1
    assertEquals( Set(1), s)
    
    s += [3, 3, 4]
    assertEquals( Set(3, 4, 1), s)
    >>> void

## Removing elements

List is mutable, so it is possible to remove its contents. To remove a single element
by index use:

    var s = Set(1,2,3)
    s.remove(2)
    assertEquals( s, Set(1,3) )

    s = Set(1,2,3)
    s.remove(2,1)
    assertEquals( s, Set(3) )
    >>> void

Note that `remove` returns true if at least one element was actually removed and false
if the set has not been changed.

## Comparisons and inclusion

Sets are only equal when contains exactly same elements, order, as was said, is not significant:

    assert( Set(1, 2) == Set(2, 1) )
    assert( Set(1, 2, 2) == Set(2, 1) )
    assert( Set(1, 3) != Set(2, 1) )
    assert( 1 in Set(5,1))
    assert( 10 !in Set(5,1))
    >>> void

## Members

| name                | meaning                              | type  |
|---------------------|--------------------------------------|-------|
| `size`              | current size                         | Int   |
| `+=`                | add one or more elements             | Any   |
| `+`, `union`         | union sets                           | Any   |
| `-`, `subtract`     | subtract sets                        | Any   |
| `*`, `intersect`    | subtract sets                        | Any   |
| `remove(items...)`  | remove one or more items             | Range |
| `contains(element)` | check the element is in the list (1) |       |

(1)
: optimized implementation that override `Iterable` one

Also, it inherits methods from [Iterable].


[Range]: Range.md