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


# Instance members

| member          | description                | args          |
|-----------------|----------------------------|---------------|
| contains(other) | used in `in`               | Range, or Any |
| inclusiveEnd    | true for '..'              | Bool          |
| isOpen          | at any end                 | Bool          |
| isIntRange      | both start and end are Int | Bool          |
| start           |                            | Bool          |
| end             |                            | Bool          |
|                 |                            |               |
|                 |                            |               |
|                 |                            |               |
