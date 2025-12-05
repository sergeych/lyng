# Json support

Since 1.0.5 we start adding JSON support. Versions 1,0,6* support serialization of the basic types, including lists and
maps, and simple classes. Multiple inheritance may produce incorrect results, it is work in progress.

## Serialization in Lyng

    // in lyng
    assertEquals("{\"a\":1}", {a: 1}.toJsonString())
    void
    >>> void

Simple classes serialization is supported:

    import lyng.serialization
    class Point(foo,bar) {
        val t = 42
    }
    // val is not serialized
    assertEquals( "{\"foo\":1,\"bar\":2}", Point(1,2).toJsonString() )
    >>> void

Note that mutable members are serialized:

    import lyng.serialization
    
    class Point2(foo,bar) {
        var reason = 42
        // but we override json serialization:
        fun toJsonObject() {
            { "custom": true }
        }
    }
    // var is serialized instead
    assertEquals( "{\"custom\":true}", Point2(1,2).toJsonString() )
    >>> void

Custom serialization of user classes is possible by overriding `toJsonObject` method. It must return an object which is
serializable to Json. Most often it is a map, but any object is accepted, that makes it very flexible:

    import lyng.serialization
    
    class Point2(foo,bar) {
        var reason = 42
        // but we override json serialization:
        fun toJsonObject() {
            { "custom": true }
        }
    }
    class Custom {
        fun toJsonObject() {
            "full freedom"
        }
    }
    // var is serialized instead
    assertEquals( "\"full freedom\"", Custom().toJsonString() )
    >>> void

Please note that `toJsonString` should be used to get serialized string representation of the object. Don't call
`toJsonObject` directly, it is not intended to be used outside the serialization library.

## Kotlin side interfaces

The "Batteries included" principle is also applied to serialization.

- `Obj.toJson()` provides Kotlin `JsonElement`
- `Obj.toJsonString()` provides Json string representation
- `Obj.decodeSerializableWith()` and `Obj.decodeSerializable()` allows to decode Lyng classes as Kotlin objects using
  `kotlinx.serialization`:

```kotlin
/**
 * Decodes the current object into a deserialized form using the provided deserialization strategy.
 * It is based on [Obj.toJson] and uses existing Kotlin Json serialization, without string representation
 * (only `JsonElement` to carry information between Kotlin and Lyng serialization worlds), thus efficient.
 *
 * @param strategy The deserialization strategy that defines how the object should be decoded.
 * @param scope An optional scope used during deserialization to define the context. Defaults to a new instance of Scope.
 * @return The deserialized object of type T.
 */
suspend fun <T> Obj.decodeSerializableWith(strategy: DeserializationStrategy<T>, scope: Scope = Scope()): T =
    Json.decodeFromJsonElement(strategy, toJson(scope))

/**
 * Decodes a serializable object of type [T] using the provided decoding scope. The deserialization uses
 * [Obj.toJson] and existing Json based serialization ithout using actual string representation, thus
 * efficient.
 *
 * @param T The type of the object to be decoded. Must be a reified type.
 * @param scope The scope used during decoding. Defaults to a new instance of [Scope].
 */
suspend inline fun <reified T> Obj.decodeSerializable(scope: Scope = Scope()) =
    decodeSerializableWith<T>(serializer<T>(), scope)
```

Note that lyng-2-kotlin deserialization with `kotlinx.serialization` uses JsonElement as information carrier without
formatting and parsing actual Json strings. This is why we use `Json.decodeFromJsonElement` instead of
`Json.decodeFromString`. Such an approach gives satisfactory performance without writing and supporting custom
`kotlinx.serialization` codecs.

# List of supported types

| Lyng type | JSON type | notes       |
|-----------|-----------|-------------|
| `Int`     | number    |             |
| `Real`    | number    |             |
| `String`  | string    |             |
| `Bool`    | boolean   |             |
| `null`    | null      |             |
| `Instant` | string    | ISO8601 (1) |
| `List`    | array     | (2)         |
| `Map`     | object    | (2)         |


(1)
: ISO8601 flavor 1970-05-06T06:00:00.000Z in used; number of fractional digits depends on the truncation
on [Instant](time.md), see `Instant.truncateTo...` functions.

(2)
: List may contain any objects serializable to Json.

(3)
: Map keys must be strings, map values may be any objects serializable to Json.

