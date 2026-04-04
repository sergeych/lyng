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

Descending finite ranges are explicit too:

    val r = 5 downTo 1
    assert(r.isDescending)
    assert(r.toList() == [5,4,3,2,1])
    >>> void

Use `downUntil` when the lower bound should be excluded:

    val r = 5 downUntil 1
    assert(r.toList() == [5,4,3,2])
    assert(1 !in r)
    >>> void

This is explicit by design: `5..1` is not treated as a reverse range. It is an
ordinary ascending range with no values in it when iterated.

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

## Ranges are iterable

Finite ranges are [Iterable] and can be used in loops and list conversions.
Open-ended ranges are iterable only with an explicit `step`, and open-start
ranges are never iterable.

    assert( [-2, -1, 0, 1] == (-2..1).toList() )
    >>> void

In spite of this you can use ranges in for loops:

    for( i in 1..3 ) 
        println(i)
    >>> 1
    >>> 2
    >>> 3
    >>> void

The loop variable is read-only inside the loop body (behaves like a `val`).

but

    for( i in 1..<3 ) 
        println(i)
    >>> 1
    >>> 2
    >>> void

Descending ranges work in `for` loops exactly the same way:

    for( i in 3 downTo 1 )
        println(i)
    >>> 3
    >>> 2
    >>> 1
    >>> void

And with an exclusive lower bound:

    for( i in 3 downUntil 1 )
        println(i)
    >>> 3
    >>> 2
    >>> void

### Stepped ranges

Use `step` to change the iteration increment. The range bounds still define membership,
so iteration ends when the next value is no longer in the range.

    assert( [1,3,5] == (1..5 step 2).toList() )
    assert( [1,3] == (1..<5 step 2).toList() )
    assert( [5,3,1] == (5 downTo 1 step 2).toList() )
    assert( ['a','c','e'] == ('a'..'e' step 2).toList() )
    >>> void

Descending ranges still use a positive `step`; the direction comes from
`downTo` / `downUntil`:

    assert( ['e','c','a'] == ('e' downTo 'a' step 2).toList() )
    >>> void

A negative step with `downTo` / `downUntil` is invalid.

Real ranges require an explicit step:

    assert( [0,0.25,0.5,0.75,1.0] == (0.0..1.0 step 0.25).toList() )
    >>> void

Open-ended ranges require an explicit step to iterate:

    (0.. step 1).take(3).toList()
    >>> [0,1,2]

## Character ranges

You can use Char as both ends of the closed range:

    val r = 'a'..'c'
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
    >>> [a,b]


# Instance members

| member          | description                  | args          |
|-----------------|------------------------------|---------------|
| contains(other) | used in `in`                 | Range, or Any |
| isEndInclusive  | true for '..'                | Bool          |
| isDescending    | true for `downTo`/`downUntil`| Bool          |
| isOpen          | at any end                   | Bool          |
| isIntRange      | both start and end are Int   | Bool          |
| step            | explicit iteration step      | Any?          |
| start           |                              | Any?          |
| end             |                              | Any?          |
| size            | for finite ranges, see above | Long          |
| []              | see above                    |               |

Ranges are also used with the `clamp(value, range)` function and the `value.clamp(range)` extension method to limit values within boundaries.

[Iterable]: Iterable.md
