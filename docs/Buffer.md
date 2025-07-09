# Binary `Buffer`

Buffers are effective unsigned byte arrays of fixed size. Buffers content is mutable,
unlike its size. Buffers are comparable and implement [Array], thus [Collection] and [Iterable]. Buffer iterators return its contents as unsigned bytes converted to `Int`

Buffers needs to be imported with `import lyng.buffer`:

    import lyng.buffer

    assertEquals(5, Buffer("Hello").size)
    >>> void

## Constructing

There are a lo of ways to construct a buffer:

    import lyng.buffer
    
    // from string using utf8 encoding:
    assertEquals( 5, Buffer("hello").size )

    // from bytes, e.g. integers in range 0..255
    assertEquals( 255, Buffer(1,2,3,255).last() )

    // from whatever iterable that produces bytes, e.g.
    // integers in 0..255 range:
    assertEquals( 129, Buffer([1,2,129]).last() )

    // Empty buffer of fixed size:
    assertEquals(100, Buffer(100).size)
    assertEquals(0, Buffer(100)[0])

    // Note that you can use list iteral to create buffer with 1 byte:
    assertEquals(1, Buffer([100]).size)
    assertEquals(100, Buffer([100])[0])

    >>> void

## Accessing an modifying

Buffer implement [Array] and therefore can be accessed and modified with indexing:

    import lyng.buffer
    val b1 = Buffer( 1, 2, 3)
    assertEquals( 2, b1[1] )
    b1[0] = 199
    assertEquals(199, b1[0])
    >>> void

Buffer provides concatenation with another Buffer:

    import lyng.buffer
    val b = Buffer(101, 102)
    assertEquals( Buffer(101, 102, 1, 2), b + [1,2])
    >>> void

## Comparing

Buffers are comparable with other buffers:

    import lyng.buffer
    val b1 = Buffer(1, 2, 3)
    val b2 = Buffer(1, 2, 3)
    val b3 = Buffer(b2)
    
    b3[0] = 101

    assert( b3 > b1 )
    assert( b2 == b1 )
    // longer string with same prefix is considered bigger:
    assert( b2 + "!".characters() > b1 )
    // note that characters() provide Iterable of characters that 
    // can be concatenated to Buffer

    >>> void

## Slicing

As with [List], it is possible to use ranges as indexes to slice a Buffer:

    import lyng.buffer

    val a = Buffer( 100, 101, 102, 103, 104, 105 )
    assertEquals( a[ 0..1 ], Buffer(100, 101) )
    assertEquals( a[ 0 ..< 2 ], Buffer(100, 101) )
    assertEquals( a[  ..< 2 ], Buffer(100, 101) )
    assertEquals( a[  4.. ], Buffer(104, 105) )
    assertEquals( a[  2..3 ], Buffer(102, 103) )

    >>> void

## Members

| name                | meaning                              | type  |
|---------------------|--------------------------------------|-------|
| `size`              | size                         | Int   |
| `+=`                | add one or more elements             | Any   |
| `+`, `union`         | union sets                           | Any   |
| `-`, `subtract`     | subtract sets                        | Any   |
| `*`, `intersect`    | subtract sets                        | Any   |
| `remove(items...)`  | remove one or more items             | Range |
| `contains(element)` | check the element is in the list (1) |       |

(1)
: optimized implementation that override `Iterable` one

Also, it inherits methods from [Iterable].


[Range]: Range.md