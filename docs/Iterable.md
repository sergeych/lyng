# Iterable interface

The interface for anything that can be iterated, e.g. finite or infinite ordered set of data that can be accessed
sequentially. Almost any data container in `Lyng` implements it: `List`, `Set`, `Buffer`, `RingBuffer`, `BitBuffer`,
`Range` and many others are `Iterable`, also `Collection` and `Array` interfaces inherit it.

`Map` and `String` have `Iterable` members to access its contents too.

Please see also [Collection] interface: many iterables are also collections, and it adds important features.

## Definition:

Iterable is a class that provides function that creates _the iterator_:

    class Iterable {
        abstract fun iterator()
    }

Note that each call of `iterator()` must provide an independent iterator.

Iterator itself is a simple interface that should provide only to method:

    class Iterator {
        abstract fun hasNext(): Bool
        fun next(): Obj
    }

Just remember at this stage typed declarations are not yet supported.

Having `Iterable` in base classes allows to use it in for loop. Also, each `Iterable` has some utility functions
available, for example

    val r = 1..10  // Range is Iterable!  
    assertEquals( [9,10], r.takeLast(2).toList() )
    assertEquals( [1,2,3], r.take(3).toList() )
    assertEquals( [9,10], r.drop(8).toList() )
    assertEquals( [1,2], r.dropLast(8).toList() )
    >>> void

## joinToString

This methods convert any iterable to a string joining string representation of each element, optionally transforming it
and joining using specified separator.

    Iterable.joinToString(separator=' ', transformer=null)

- if `Iterable` `isEmpty`, the empty string `""` is returned.
- `separator` is inserted between items when there are more than one.
- `transformer` of specified is applied to each element, otherwise its `toString()` method is used.

Here is the sample:

    assertEquals( (1..3).joinToString(), "1 2 3")
    assertEquals( (1..3).joinToString(":"), "1:2:3")
    assertEquals( (1..3).joinToString { it * 10 }, "10 20 30")
    >>> void

## `sum` and `sumBy`

These, again, does the thing:

    assertEquals( 6, [1,2,3].sum() )
    assertEquals( 12, [1,2,3].sumOf { it*2 } )

    // sum of empty collections is null:
    assertEquals( null, [].sum() )
    assertEquals( null, [].sumOf { 2*it } )

    >>> void

## map and mapNotNull

Used to transform either the whole iterable stream or also skipping som elements from it:

    val source = [1,2,3,4]
    // transform every element to string or null:
    assertEquals(["n1", "n2", null, "n4"], source.map { if( it == 3 ) null else "n"+it } )
    
    // transform every element to stirng, skipping 3:
    assertEquals(["n1", "n2", "n4"], source.mapNotNull { if( it == 3 ) null else "n"+it } )
    
    >>> void

## findFirst and findFirstOrNull

Search for the first element that satisfies the given predicate:

    val source = [1, 2, 3, 4]
    assertEquals( 2, source.findFirst { it % 2 == 0 } )
    assertEquals( 2, source.findFirstOrNull { it % 2 == 0 } )
    
    // findFirst throws if not found:
    assertThrows( NoSuchElementException ) { source.findFirst { it > 10 } }
    
    // findFirstOrNull returns null if not found:
    assertEquals( null, source.findFirstOrNull { it > 10 } )
    
    >>> void

## Instance methods:

| fun/method             | description                                                                     |
|------------------------|---------------------------------------------------------------------------------|
| toList()               | create a list from iterable                                                     |
| toSet()                | create a set from iterable                                                      |
| contains(i)            | check that iterable contains `i`                                                |
| `i in iterable`        | same as `contains(i)`                                                           |
| isEmpty()              | check iterable is empty                                                         |
| forEach(f)             | call f for each element                                                         |
| toMap()                | create a map from list of key-value pairs (arrays of 2 items or like)           |
| map(f)                 | create a list of values returned by `f` called for each element of the iterable |
| indexOf(i)             | return index if the first encounter of i or a negative value if not found       |
| associateBy(kf)        | create a map where keys are returned by kf that will be called for each element |
| findFirst(p)           | return first element matching predicate `p` or throw (1)                         |
| findFirstOrNull(p)     | return first element matching predicate `p` or `null`                            |
| first                  | first element (1)                                                               |
| last                   | last element (1)                                                                |
| take(n)                | return [Iterable] of up to n first elements                                     |
| takeLast(n)            | return [Iterable] of up to n last elements                                      |
| drop(n)                | return new [Iterable] without first n elements                                  |
| dropLast(n)            | return new [Iterable] without last n elements                                   |
| sum()                  | return sum of the collection applying `+` to its elements (3)                   |
| sumOf(predicate)       | sum of the modified collection items (3)                                        |
| sorted()               | return [List] with collection items sorted naturally                            |
| sortedWith(comparator) | sort using a comparator that compares elements (1)                              |
| sortedBy(predicate)    | sort by comparing results of the predicate function                             |
| joinToString(s,t)      | convert iterable to string, see (2)                                             |
| reversed()             | create a list containing items from this in reverse order                       |
| shuffled()             | create a listof shiffled elements                                               |

(1)
:: throws `NoSuchElementException` if there is no such element

(2)
:: `joinToString(separator=" ", transformer=null)`: separator is inserted between items if there are more than one, transformer is
optional function applied to each item that must return result string for an item, otherwise `item.toString()` is used.

(3)
:: sum of empty collection is `null`

## Abstract methods:

    fun iterator(): Iterator

## Included in interfaces:

- [Collection], Array, [List]

## Implemented in classes:

- [List], [Range], [Buffer](Buffer.md), [BitBuffer], [Buffer], [Set], [RingBuffer]

[Collection]: Collection.md

[List]: List.md

[Range]: Range.md

[Set]: Set.md

[RingBuffer]: RingBuffer.md