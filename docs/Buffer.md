# Binary `Buffer`

Buffers are effective unsigned byte arrays of fixed size. Buffers content is mutable,
unlike its size. Buffers are comparable and implement [Array], thus [Collection] and [Iterable]. Buffer iterators return
its contents as unsigned bytes converted to `Int`

Buffers needs to be imported with `import lyng.buffer`:

    import lyng.buffer

    assertEquals(5, Buffer("Hello").size)
    >>> void

Buffer is _immutable_, there is a `MutableBuffer` with same interface but mutable.

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

## Accessing and modifying

Buffer implement [Array] and therefore can be accessed, and `MutableBuffers` also modified:

    import lyng.buffer
    val b1 = Buffer( 1, 2, 3)
    assertEquals( 2, b1[1] )

    val b2 = b1.toMutable()
    assertEquals( 2, b1[1] )
    b2[1]++
    b2[0] = 100
    assertEquals( Buffer(100, 3, 3), b2)

    // b2 is a mutable copy so b1 has not been changed:
    assertEquals( Buffer(1, 2, 3), b1)

    >>> void

Buffer provides concatenation with another Buffer:

    import lyng.buffer
    val b = Buffer(101, 102)
    assertEquals( Buffer(101, 102, 1, 2), b + [1,2])
    >>> void

Please note that indexed bytes are _readonly projection_, e.g. you can't modify these with

## Comparing

Buffers are comparable with other buffers (and notice there are _mutable_ buffers, bu default buffers ar _immutable_):

    import lyng.buffer
    val b1 = Buffer(1, 2, 3)
    val b2 = Buffer(1, 2, 3)
    val b3 = MutableBuffer(b2)
    
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

## Encoding

You can encode `String` to buffer using buffer constructor, as was shown. Also, buffer supports out of the box base64 (
which is used in `toString`) and hex encoding:

    import lyng.buffer
    
    // to UTF8 and back:
    val b = Buffer("hello")
    assertEquals( "hello", b.decodeUtf8() )
    
    // to base64 and back:
    assertEquals( b, Buffer.decodeBase64(b.base64) )
    assertEquals( b, Buffer.decodeHex(b.hex) )
    >>> void

## Members

| name                      | meaning                           | type          |
|---------------------------|-----------------------------------|---------------|
| `size`                    | size                              | Int           |
| `decodeUtf8`              | decode to String using UTF8 rules | Any           |
| `+`                       | buffer concatenation              | Any           |
| `toMutable()`             | create a mutable copy             | MutableBuffer |
| `hex`                     | encode to hex strign              | String        |
| `Buffer.decodeHex(hexStr) | decode hex string                 | Buffer        |
| `base64`                  | encode to base64 (url flavor) (2) | String        |
| `Buffer.decodeBase64`     | decode base64 to new Buffer (2)   | Buffer        |

(1)
: optimized implementation that override `Iterable` one

(2)
: base64url alphabet is used without trailing '=', which allows string to be used in URI without escaping. Note that
decoding supports both traditional and URL alphabets automatically, and ignores filling `=` characters. Base64URL is
well known and mentioned in the internet, for example, [here](https://base64.guru/standards/base64url).

Also, it inherits methods from [Iterable] and [Array].


[Range]: Range.md

[Iterable]: Iterable.md