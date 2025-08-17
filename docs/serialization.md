# Lyng serialization

Lyng has builting binary bit-effective serialization format, called Lynon for LYng Object Notation. It is typed, binary, implements caching, automatic compression, variable-length ints, one-bit Booleans an many nice features. 

It is as simple as:

    import lyng.serialization

    val text = "
        We hold these truths to be self-evident, that all men are created equal, 
        that they are endowed by their Creator with certain unalienable Rights, 
        that among these are Life, Liberty and the pursuit of Happiness.    
        "
    val encodedBits = Lynon.encode(text)

    // decode bits source:
    assertEquals( text, Lynon.decode(encodedBits) )
    
    // compression was used automatically
    assert( text.length > encodedBits.toBuffer().size )
    >>> void

Any class you create is serializable by default; lynon serializes first constructor fields, then any `var` member fields:

    import lyng.serialization

    class Point(x,y)

    val p = Lynon.decode( Lynon.encode( Point(5,6) ) )
    
    assertEquals( 5, p.x )
    assertEquals( 6, p.y )
    >>> void


just as expected.

Important is to understand that normally `Lynon.decode` wants [BitBuffer], as `Lynon.encode` produces. If you have the regular [Buffer], be sure to convert it:

    buffer.toBitInput()

this possibly creates extra zero bits at the end, as bit content could be shorter than byte-grained but for the Lynon format it does not make sense. Note that when you serialize [BitBuffer], exact number of bits is written. To convert bit buffer to bytes:

    Lynon.encode("hello").toBuffer()

(topic is incomplete and under construction)
