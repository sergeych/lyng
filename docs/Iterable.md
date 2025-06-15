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

Having `Iterable` in base classes allows to use it in for loop. Also, each `Iterable` has some utility functions available:

## Instance methods

    fun Iterable.toList(): List
    fun Iterable.toSet(): Set
    fun Iterable.indexOf(element): Int
    fun Iterable.contains(element): Bool
    fun Iterable.isEmpty(element): Bool
    fun Iterable.forEach(block: (Any?)->Void ): Void
    fun Iterable.map(block: (Any?)->Void ): List
    

## Abstract methods

    fun iterator(): Iterator

Creates a list by iterating to the end. So, the Iterator should be finite to be used with it.

## Included in interfaces:

- [Collection], Array, [List]

## Implemented in classes:

- [List], [Range]

[List]: List.md
[Range]: Range.md