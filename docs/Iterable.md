# Iterable interface

The inteface which requires iterator to be implemented:

    fun iterator(): Iterator

Iterator itself is a simple interface that should provide only to method:

    interface Iterable {
        fun hasNext(): Bool
        fun next(): Obj
    }

Just remember at this stage typed declarations are not yet supported.

Having `Iterable` in base classes allows to use it in for loop. Also, each `Iterable` has some utility functions available:

## toList()

Creates a list by iterating to the end. So, the Iterator should be finite to be used with it.

## Included in interfaces:

- Collection, Array, [List]

## Implemented in classes:

- [List], [Range]

[List]: List.md
[Range]: Range.md