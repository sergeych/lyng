# Lyng serialization

Lyng has a built-in serialization module, `lyng.serialization`.

There are now two built-in formats with different goals:

- `Lynon`: the canonical binary format for Lyng values.
- `Json`: the canonical JSON-based round-trip format for Lyng values.

In addition, `Obj.toJson()` / `toJsonString()` remain available as a plain JSON projection for interoperability with
regular JSON tools and Kotlin `kotlinx.serialization`.

## Canonical formats

`Lynon` and `Json` are both exposed as format objects with the same surface:

- `Format.encode(value)`
- `Format.decode(encodedValue)`

For the built-in formats:

- `Lynon.encode(x)` returns `BitBuffer`
- `Lynon.decode(bitBuffer)` returns the original Lyng value
- `Json.encode(x)` returns `String`
- `Json.decode(jsonString)` returns the original Lyng value

## Lynon

Lynon is LYng Object Notation. It is typed, binary, bit-effective, implements caching, automatic compression,
variable-length integers, one-bit booleans, and preserves Lyng runtime structure well.

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
    assert( text.length > (encodedBits.toBuffer() as Buffer).size )
    >>> void

Any class you create is serializable by default; Lynon serializes first constructor fields, then any `var` member
fields.

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
- Are **omitted** from JSON output (`toJson`) and canonical `Json.encode(...)`.
- Are **ignored** during structural equality checks (`==`).
- If a transient constructor parameter has a **default value**, it will be restored to that default value during deserialization. Otherwise, it will be `null`.
- Class body fields marked as `@Transient` will keep their initial values (or values assigned in `init`) after deserialization.

## Serialization of Objects and Classes

- **Singleton Objects**: `object` declarations are serializable by name. Their state (mutable fields) is also serialized and restored, respecting `@Transient`.
- **Classes**: Class objects themselves can be serialized. They are serialized by their full qualified name. When converted to JSON, a class object includes its public static fields (excluding those marked `@Transient`).
- **Exceptions**: canonical formats preserve exception class, message, extra data, and captured stack trace.

## Plain JSON projection vs canonical Json format

There are two JSON-related APIs and they serve different purposes:

- `Obj.toJson()` / `toJsonString()`
  - produce ordinary JSON values
  - best for interop with external JSON systems
  - best for `Obj.decodeSerializable()` / `decodeSerializableWith()`
  - may be lossy for Lyng-specific structures

- `Json.encode()` / `Json.decode()`
  - produce JSON text too
  - but use Lyng-specific type tags where needed
  - intended for round-tripping Lyng values
  - intended to match Lynon semantics where JSON can carry them
  - can preserve values that plain JSON cannot represent directly, such as:
    - maps with non-string keys
    - sets
    - buffers and bit buffers
    - class instances
    - singleton objects
    - enums
    - exceptions
    - date/time objects
    - non-finite reals
    - `void`

Why this split exists:

- plain `toJson()` must remain ordinary JSON so it stays convenient for external JSON systems and Kotlin
  `kotlinx.serialization`
- canonical `Json.encode()` is for Lyng-to-Lyng transport through JSON text, so it must preserve Lyng runtime
  distinctions whenever possible
- one API cannot optimize for both goals at once: either you get too many Lyng tags for ordinary JSON interop, or you
  get lossy round-trips

Example:

    import lyng.serialization
    import lyng.time

    enum Color { Red, Green }
    class Point(x,y) { var z = 42 }

    val p = Point(1,2)
    p.z = 99
    val x = List(
        p,
        Map([1, "one"], ["two", 2]),
        Set(1,2,3),
        "hello".encodeUtf8(),
        Date(2026,4,15),
        Color.Green
    )

    assertEquals(x, Json.decode(Json.encode(x)))
    >>> void

## Adding more formats from Kotlin modules

External modules can add new formats on the Kotlin side.

The common base class is:

```kotlin
abstract class ObjSerializationFormatClass(className: String) : ObjClass(className) {
    abstract suspend fun encodeValue(scope: Scope, value: Obj): Obj
    abstract suspend fun decodeValue(scope: Scope, encoded: Obj): Obj
}
```

To export a new format from a module:

```kotlin
im.addPackage("test.formats") { module ->
    module.bindSerializationFormat(
        object : ObjSerializationFormatClass("Reverse") {
            override suspend fun encodeValue(scope: Scope, value: Obj): Obj =
                ObjString(value.toString(scope).value.reversed())

            override suspend fun decodeValue(scope: Scope, encoded: Obj): Obj =
                ObjString((encoded as ObjString).value.reversed())
        }
    )
}
```

Then from Lyng, after importing the Kotlin module above, usage looks like:

```lyng
import test.formats

assertEquals("cba", Reverse.encode("abc"))
assertEquals("abc", Reverse.decode("cba"))
```

## Notes

Important is to understand that normally `Lynon.decode` wants [BitBuffer], as `Lynon.encode` produces. If you have the regular [Buffer], be sure to convert it:

    buffer.toBitInput()

this possibly creates extra zero bits at the end, as bit content could be shorter than byte-grained but for the Lynon format it does not make sense. Note that when you serialize [BitBuffer], exact number of bits is written. To convert bit buffer to bytes:

    Lynon.encode("hello").toBuffer()
