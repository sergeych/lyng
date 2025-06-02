# Range

Range is diapason between two values. Open range has at least one end open, e.g. ±∞, closed range has both ends open.

## Closed ranges

The syntax is intuitive and adopted from Kotlin:

    // end inclusive:
    val r = 1..5
    assert(5 in r)
    assert(6 !in r)
    assert(0 !in r)
    assert(2 in r)
    >>> void

Exclusive end ranges are adopted from kotlin either:

    // end inclusive:
    val r = 1..<5
    assert(5 !in r)
    assert(6 !in r)
    assert(0 !in r)
    assert(2 in r)
    assert(4 in r)
    >>> void

In any case, we can test an object to belong to using `in` and `!in` and
access limits:
    
    val r = 0..5
    (r.end - r.start)/2
    >>> 2

Notice, start and end are ints, so midpoint here is int too. 

It is possible to test that one range is included in another range too,
one range is defined as _contained_ in another ifm and only if, it begin and end
are equal or within another, taking into account the end-inclusiveness: 

    assert( (1..3) in (1..3) )
    assert( (0..3) !in (1..3) )
    assert( (1..2) in (1..<3) )
    assert( (1..<2) in (1..<3) )
    assert( (1..<3) in (1..3) )
    >>> void

## Finite Ranges are iterable

So given a range with both ends, you can assume it is [Iterable]. This automatically let
use finite ranges in loops and convert it to lists:

    assert( [-2, -1, 0, 1] == (-2..1).toList() )
    >>> void

In spite of this you can use ranges in for loops:

    for( i in 1..3 ) 
        println(i)
    >>> 1
    >>> 2
    >>> 3
    >>> void

but

    for( i in 1..<3 ) 
        println(i)
    >>> 1
    >>> 2
    >>> void

## Character ranges

You can use Char as both ends of the closed range:

    val r = 'a' .. 'c'
    assert( 'b' in r)
    assert( 'e' !in r)
    for( ch in r )
        println(ch)
    >>> a
    >>> b
    >>> c
    >>> void

Exclusive end char ranges are supported too:

    ('a'..<'c').toList 
    >>> ['a', 'b']


# Instance members

| member          | description                  | args          |
|-----------------|------------------------------|---------------|
| contains(other) | used in `in`                 | Range, or Any |
| isEndInclusive  | true for '..'                | Bool          |
| isOpen          | at any end                   | Bool          |
| isIntRange      | both start and end are Int   | Bool          |
| start           |                              | Bool          |
| end             |                              | Bool          |
| size            | for finite ranges, see above | Long          |
| []              | see above                    |               |
|                 |                              |               |

[Iterable]: Iterable.md