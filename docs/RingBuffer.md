# RingBuffer

This is a fixed size buffer that allow to store N last elements with _O(1)_ effectiveness (no data shifting).

Here is the sample:

    val r = RingBuffer(3)
    assert( r is RingBuffer )
    assertEquals(0, r.size)
    assertEquals(3, r.capacity)
    
    r += 10
    assertEquals(1, r.size)
    assertEquals(10, r.first)
    
    r += 20
    assertEquals(2, r.size)
    assertEquals( [10, 20], r.toList() )
    
    r += 30
    assertEquals(3, r.size)
    assertEquals( [10, 20, 30], r.toList() )
    
    // now first value is lost:
    r += 40
    assertEquals(3, r.size)
    assertEquals( [20, 30, 40], r.toList() )
    assertEquals(3, r.capacity)

    >>> void

Ring buffer implements [Iterable], so any of its methods are available for `RingBuffer`, e.g. `first`, `last`, `toList`,
`take`, `drop`, `takelast`, `dropLast`, etc.

## Constructor

    RinbBuffer(capacity: Int)

## Instance methods

| method      | description            | remarks |
|-------------|------------------------|---------|
| capacity    | max size of the buffer |         |
| size        | current size           | (1)     |
| operator += | add new item           | (1)     |
| add(item)   | add new item           | (1)     |
| iterator()  | return iterator        | (1)     |

(1)
: Ringbuffer is not threadsafe, protect it with a mutex to avoid RC where necessary.

[Iterable]: Iterable.md