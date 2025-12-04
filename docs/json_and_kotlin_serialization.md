# Json support

Since 1.0.5 we start adding JSON support. 

Right now we only support basic types: maps, lists, strings, numbers, booleans. It is not yet capable of serializing classes. This functionality will be added in the 1.0.6 release.

## Serializae to kotlin string

    // in lyng
    assertEquals("{\"a\":1}", {a: 1}.toJsonString())
    void
    >>> void

From the kotln side, you can use `Obj.toJson()` and deserialization helpers:

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
suspend fun <T>Obj.decodeSerializableWith(strategy: DeserializationStrategy<T>, scope: Scope = Scope()): T =
    Json.decodeFromJsonElement(strategy,toJson(scope))

/**
 * Decodes a serializable object of type [T] using the provided decoding scope. The deserialization uses
 * [Obj.toJson] and existing Json based serialization ithout using actual string representation, thus
 * efficient.
 *
 * @param T The type of the object to be decoded. Must be a reified type.
 * @param scope The scope used during decoding. Defaults to a new instance of [Scope].
 */
suspend inline fun <reified T>Obj.decodeSerializable(scope: Scope= Scope()) =
    decodeSerializableWith<T>(serializer<T>(), scope)
```

Note that lyng-2-kotlin deserialization with `kotlinx.serialization` is working based on JsonElement as information carrier, without formatting and parsing actual Json strings. This is why we use `Json.decodeFromJsonElement` instead of `Json.decodeFromString`. Such approach gives satisfactory performance without writing and supporting custom `kotlinx.serialization` codecs.


