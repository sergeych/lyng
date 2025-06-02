# Iterator interface

Iterators are representing the [Iterable] entity, to access its contents
sequentially. 

To implement the iterator you need to implement only two abstract methods:

## Abstract methods

### hasNext(): Bool

Should return `true` if call to `next()` will return valid next element.

### next(): Obj

Should return next object in the iterated entity. If there is no next method,
must throw `ObjIterationFinishedError`.

## Usage

Iterators are returned when implementing [Iterable] interface.

## Implemented for classes:

- [List], [Range]

[List]: List.md
[Range]: Range.md
[Iterable]: Iterable.md