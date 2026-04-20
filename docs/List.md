# List built-in class

Mutable list of any objects.
For immutable list values, see [ImmutableList].
For observable mutable lists and change hooks, see [ObservableList].

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

The language also allows multi-selector indexing syntax such as `value[i, j]`, but `List` itself uses a single selector only:

- `list[index]` for one element
- `list[range]` for a slice copy

Multi-selector indexing is intended for custom indexers such as `Matrix`.

## Concatenation

You can concatenate lists or iterable objects:

    assert( [4,5] + [1,2] == [4,5,1,2])
    assert( [4,5] + (1..3) == [4, 5, 1, 2, 3])
    >>> void

## Constructing lists

Besides literals, you can build a list by size using `List.fill`:

    val squares = List.fill(5) { i -> i * i }
    assertEquals([0, 1, 4, 9, 16], squares)
    >>> void

`List.fill(size) { ... }` calls the block once for each index from `0` to `size - 1` and returns a new mutable list.

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

## Destructuring

Lists can be used as L-values for destructuring assignments. This allows you to unpack list elements into multiple variables.

### Basic Destructuring
```lyng
val [a, b, c] = [1, 2, 3]
```

### With Splats (Variadic)
A single ellipsis `...` can be used to capture remaining elements into a list. It can be placed at the beginning, middle, or end of the pattern.
```lyng
val [head, rest...] = [1, 2, 3] // head=1, rest=[2, 3]
val [first, middle..., last] = [1, 2, 3, 4, 5] // first=1, middle=[2, 3, 4], last=5
```

### Nested Patterns
Destructuring patterns can be nested to unpack multi-dimensional lists.
```lyng
val [a, [b, c...], d] = [1, [2, 3, 4], 5]
```

### Reassignment
Destructuring can also be used to reassign existing variables:
```lyng
[x, y] = [y, x] // Swap values
```

## In-place sort

List could be sorted in place, just like [Collection] provide sorted copies, in a very like way:

    val l1 = [6,3,1,9]
    l1.sort()
    assertEquals( [1,3,6,9], l1)
    
    l1.sortBy { -it }
    assertEquals( [1,3,6,9].reversed(), l1)
    
    l1.sort() // 1 3 6 9
    l1.sortBy { it % 4 }
    // 1,3,6,9 gives, mod 4:
    // 1 3 2 1
    // we hope we got it also stable:
    assertEquals( [1,9,6,3], l1)
    >>> void

## Members

| name                          | meaning                                      | type        |
|-------------------------------|----------------------------------------------|-------------|
| `size`                        | current size                                 | Int         |
| `add(elements...)`            | add one or more elements to the end          | Any         |
| `insertAt(index,elements...)` | insert elements at position                  | Int, Any    |
| `removeAt(index)`             | remove element at position                   | Int         |
| `remove(from,toNonInclusive)` | remove range from (incl) to (nonincl)        | Int, Int    |
| `remove(Range)`               | remove range                                 | Range       |
| `removeLast()`                | remove last element                          |             |
| `removeLast(n)`               | remove n last elements                       | Int         |
| `contains(element)`           | check the element is in the list (1)         |             |
| `[index]`                     | get or set element at index                  | Int         |
| `[Range]`                     | get slice of the array (copy)                | Range       |
| `+=`                          | append element(s) (2)                        | List or Obj |
| `List.fill(size, block)`      | build a new list from indices `0..<size`     | Int, Callable |
| `List.fill(size,capacity,block)` | same, pre-allocating capacity slots       | Int, Int, Callable |
| `ensureCapacity(count)`       | pre-allocate storage for at least `count` elements without reallocation (5) | Int |
| `sort()`                      | in-place sort, natural order                 | void        |
| `sortBy(predicate)`           | in-place sort bu `predicate` call result (3) | void        |
| `sortWith(comparator)`        | in-place sort using `comarator` function (4) | void        |
| `shuffle()`                   | in-place shuffle contents                    |             |
| `toString()`                 | string representation like `[a,b,c]`         |             |

(1)
: optimized implementation that override `Array` one

(2)
: `+=` append either a single element, or all elements if the List or other Iterable
instance is appended. If you want to append an Iterable object itself, use `add` instead.

(3)
: predicate is called on each element, and the returned values are used to sort in natural
order, e.g. is same as `list.sortWith { a,b -> predicate(a) <=> predicate(b) }`

(4)
: comparator callable takes tho arguments and must return: negative value when first is less,
positive if first is greater, and zero if they are equal. For example, the equvalent comparator
for `sort()` will be `sort { a, b -> a <=> b }

(5)
: if the current capacity is already ≥ `count`, this is a no-op. Otherwise the internal storage
is reallocated to hold at least `count` elements. Use this before a bulk `+=` loop to avoid
repeated reallocations. `List.fill(size, capacity, block)` calls this automatically.

It inherits from [Iterable] too and thus all iterable methods are applicable to any list.

## Observable list hooks

Observable hooks are provided by module `lyng.observable` and are opt-in:

    import lyng.observable

    val src = [1,2,3]
    val xs = src.observable()
    assert(xs is ObservableList<Int>)

    var before = 0
    var after = 0
    xs.beforeChange { before++ }
    xs.onChange { after++ }

    xs += 4
    xs[0] = 100
    assertEquals([100,2,3,4], xs)
    assertEquals(2, before)
    assertEquals(2, after)
    >>> void

`beforeChange` runs before mutation commit and may reject it by throwing exception (typically `ChangeRejectionException` from the same module):

    import lyng.observable
    val xs = [1,2].observable()
    xs.beforeChange { throw ChangeRejectionException("read only") }
    assertThrows(ChangeRejectionException) { xs += 3 }
    assertEquals([1,2], xs)
    >>> void

`changes()` returns `Flow<ListChange<T>>` of committed events:

    import lyng.observable
    val xs = [10,20].observable()
    val it = xs.changes().iterator()
    xs += 30
    assert(it.hasNext())
    val e = it.next()
    assert(e is ListInsert<Int>)
    assertEquals([30], (e as ListInsert<Int>).values)
    it.cancelIteration()
    >>> void

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
[ImmutableList]: ImmutableList.md
[ObservableList]: ObservableList.md
