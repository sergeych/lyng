# Iterable interface

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

Having `Iterable` in base classes allows to use it in for loop. Also, each `Iterable` has some utility functions available, for example

    val r = 1..10  // Range is Iterable!  
    assertEquals( [9,10] r.takeLast(2) )
    assertEquals( [1,2,3] r.take(3) )
    assertEquals( [9,10] r.drop(8) )
    assertEquals( [1,2] r.dropLast(8) )
    >>> void

## Instance methods

| fun/method      | description                                                                     |
|-----------------|---------------------------------------------------------------------------------|
| toList()        | create a list from iterable                                                     |
| toSet()         | create a set from iterable                                                      |
| contains(i)     | check that iterable contains `i`                                                |
| `i in iterator` | same as `contains(i)`                                                           |
| isEmpty()       | check iterable is empty                                                         |
| forEach(f)      | call f for each element                                                         |
| toMap()         | create a map from list of key-value pairs (arrays of 2 items or like)           |
| map(f)          | create a list of values returned by `f` called for each element of the iterable |
| indexOf(i)      | return index if the first encounter of i or a negative value if not found       |
| associateBy(kf) | create a map where keys are returned by kf that will be called for each element |
| first           | first element (1)                                                               |
| last            | last element (1)                                                                |
| take(n)         | return [Iterable] of up to n first elements                                     |
| taleLast(n)     | return [Iterable] of up to n last elements                                      |
| drop(n)         | return new [Iterable] without first n elements                                  |
| dropLast(n)     | return new [Iterable] without last n elements                                   |

(1)
: throws `NoSuchElementException` if there is no such element 

    fun Iterable.toList(): List
    fun Iterable.toSet(): Set
    fun Iterable.indexOf(element): Int
    fun Iterable.contains(element): Bool
    fun Iterable.isEmpty(element): Bool
    fun Iterable.forEach(block: (Any?)->Void ): Void
    fun Iterable.map(block: (Any?)->Void ): List
    fun Iterable.associateBy( keyMaker: (Any?)->Any): Map
    

## Abstract methods

    fun iterator(): Iterator

Creates a list by iterating to the end. So, the Iterator should be finite to be used with it.

## Included in interfaces:

- [Collection], Array, [List]

## Implemented in classes:

- [List], [Range]

[List]: List.md
[Range]: Range.md