# Iterator interface

Iterators are representing the [Iterable] entity, to access its contents
sequentially. 

To implement the iterator you need to implement only two abstract methods:

## Abstract methods

### hasNext(): Bool

    // lets test
    // offset

Should return `true` if call to `next()` will return valid next element.

### next(): Obj

Should return next object in the iterated entity. If there is no next method,
must throw `ObjIterationFinishedError`.

## Usage

Iterators are returned when implementing [Iterable] interface.

For high-performance Kotlin-side interop and custom iterable implementation details, see [Efficient Iterables in Kotlin Interop](EfficientIterables.md).

## Implemented for classes:

- [List], [Range]

[List]: List.md
[Range]: Range.md
[Iterable]: Iterable.md