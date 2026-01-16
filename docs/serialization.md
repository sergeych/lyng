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

Any class you create is serializable by default; lynon serializes first constructor fields, then any `var` member fields.

## Transient Fields

Sometimes you have fields that should not be serialized, for example, temporary caches, secret data, or derived values that are recomputed in `init` blocks. You can mark such fields with the `@Transient` attribute:

```lyng
class MyData(@Transient val tempSecret, val publicData) {
    @Transient var cachedValue = 0
    var persistentValue = 42
    
    init {
        // cachedValue can be recomputed here upon deserialization
        cachedValue = computeCache(publicData)
    }
}
```

Transient fields:
- Are **omitted** from Lynon binary streams.
- Are **omitted** from JSON output (via `toJson`).
- Are **ignored** during structural equality checks (`==`).
- If a transient constructor parameter has a **default value**, it will be restored to that default value during deserialization. Otherwise, it will be `null`.
- Class body fields marked as `@Transient` will keep their initial values (or values assigned in `init`) after deserialization.

## Serialization of Objects and Classes

- **Singleton Objects**: `object` declarations are serializable by name. Their state (mutable fields) is also serialized and restored, respecting `@Transient`.
- **Classes**: Class objects themselves can be serialized. They are serialized by their full qualified name. When converted to JSON, a class object includes its public static fields (excluding those marked `@Transient`).

## Custom Serialization

Important is to understand that normally `Lynon.decode` wants [BitBuffer], as `Lynon.encode` produces. If you have the regular [Buffer], be sure to convert it:

    buffer.toBitInput()

this possibly creates extra zero bits at the end, as bit content could be shorter than byte-grained but for the Lynon format it does not make sense. Note that when you serialize [BitBuffer], exact number of bits is written. To convert bit buffer to bytes:

    Lynon.encode("hello").toBuffer()

(topic is incomplete and under construction)
