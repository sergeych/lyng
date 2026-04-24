# Json support

Lyng now has two distinct JSON-facing layers:

- plain JSON projection:
  - `Obj.toJson()`
  - `Obj.toJsonString()`
- canonical JSON round-trip format:
  - `Json.encode(value)`
  - `Json.decode(text)`

Use the first when you need ordinary JSON for interop.

Use the second when you need Lyng value round-trip semantics through JSON text.

This distinction is intentional:

- plain JSON projection is optimized for compatibility with ordinary JSON tooling
- canonical `Json.encode()` is optimized for semantic fidelity to Lyng and Lynon
- these goals conflict for values such as sets, exceptions, singleton objects, buffers, and maps with non-string keys

## Plain JSON projection in Lyng

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

Note that mutable members are serialized by default. You can exclude any member (including constructor parameters) from
JSON serialization using the `@Transient` attribute:

    import lyng.serialization
    
    class Point2(@Transient val foo, val bar) {
        @Transient var reason = 42
        var visible = 100
    }
    assertEquals( "{\"bar\":2,\"visible\":100}", Point2(1,2).toJsonString() )
    >>> void

Note that if you override plain JSON serialization:

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

Custom serialization of user classes is possible by overriding `toJsonObject`. It must return an object which is
serializable to JSON. Most often it is a map, but any object is accepted:

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

## Canonical Json round-trip format

`Json.encode()` and `Json.decode()` are now the JSON equivalents of `Lynon.encode()` and `Lynon.decode()`.

They still use JSON text, but they add Lyng-specific type tags where plain JSON would otherwise lose information.

Example:

```lyng
import lyng.serialization
import lyng.time

enum Color { Red, Green }
class Point(x,y) { var z = 42 }

val p = Point(1,2)
p.z = 99

val value = List(
    p,
    Map([1, "one"], ["two", 2]),
    Set(1,2,3),
    "hello".encodeUtf8(),
    Date(2026,4,15),
    Color.Green
)

assertEquals(value, Json.decode(Json.encode(value)))
```

The canonical `Json` format is intended for Lyng-to-Lyng transfer through JSON text.

The plain `toJson()` projection is intended for ordinary JSON interop.

Canonical `Json.encode()` should be read as the JSON analogue of `Lynon.encode()`: when Lynon already preserves a
Lyng distinction, canonical JSON tries to preserve it too, using tags only where ordinary JSON is insufficient.

## Kotlin side interfaces

The "Batteries included" principle is also applied to serialization.

- `Obj.toJson()` provides Kotlin `JsonElement` for the plain JSON projection
- `Obj.toJsonString()` provides plain JSON string representation
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

Note that Lyng-to-Kotlin deserialization with `kotlinx.serialization` is based on the plain JSON projection,
not the canonical `Json.encode()` format. It uses `JsonElement` as the information carrier without formatting and
parsing actual JSON strings. This is why we use `Json.decodeFromJsonElement` instead of `Json.decodeFromString`.

### Pitfall: JSON objects and Map<String, Any?>

Kotlin serialization does not support `Map<String, Any?>` as a serializable type, more general, it can't serialize `Any`. This in particular means that you can deserialize Kotlin `Map<String, T>` as long as `T` is `@Serializable` in Kotlin: 

```kotlin
@Serializable
data class TestJson2(
    val value: Int,
    val inner: Map<String,Int>
)

@Test
fun deserializeMapWithJsonTest() = runTest {
    val session = EvalSession()
    val x = session.eval("""
        import lyng.serialization
        { value: 1, inner: { "foo": 1, "bar": 2 }}
    """.trimIndent()).decodeSerializable<TestJson2>()
    // That works perfectly well:
    assertEquals(TestJson2(1, mapOf("foo" to 1, "bar" to 2)), x)
}
```

But what if your map has objects of different types? The approach of using polymorphism is partially applicable, but what to do with `{ one: 1, two: "two" }`?

The answer is simple: use `JsonObject` in your deserializable object. This class is capable of holding any JSON types
and structures:

~~~kotlin
@Serializable
data class TestJson3(
    val value: Int,
    val inner: JsonObject
)
@Test
fun deserializeAnyMapWithJsonTest() = runTest {
    val session = EvalSession()
    val x = session.eval("""
        import lyng.serialization
        { value: 12, inner: { "foo": 1, "bar": "two" }}
    """.trimIndent()).decodeSerializable<TestJson3>()
    assertEquals(TestJson3(12, JsonObject(mapOf("foo" to JsonPrimitive(1), "bar" to Json.encodeToJsonElement("two")))), x)
}
~~~


## Supported shapes

### Plain JSON projection

| Lyng type | JSON type | notes       |
|-----------|-----------|-------------|
| `Int`     | number    |             |
| `Real`    | number    | finite values only as plain numbers |
| `String`  | string    |             |
| `Bool`    | boolean   |             |
| `null`    | null      |             |
| `Instant` | string    | ISO8601 (1) |
| `List`    | array     | (2)         |
| `Map`     | object    | string keys only |
| simple class instance | object | constructor fields + mutable vars |
| enum      | string    | entry name |

### Canonical `Json.encode`

This format can also round-trip:

- maps with non-string keys
- sets
- immutable collections
- buffers and bit buffers
- class instances
- singleton objects
- enums
- exceptions
- `Date`, `Instant`, `DateTime`
- non-finite reals
- `void`

It does so by adding Lyng-specific type tags only when necessary.

## Kotlin-side extension point for more formats

Additional formats can be exported from Kotlin modules by subclassing `ObjSerializationFormatClass` and registering the
format in module scope with `bindSerializationFormat(...)`.

```kotlin
module.bindSerializationFormat(
    object : ObjSerializationFormatClass("MyFormat") {
        override suspend fun encodeValue(scope: Scope, value: Obj): Obj = ...
        override suspend fun decodeValue(scope: Scope, encoded: Obj): Obj = ...
    }
)
```

This makes `MyFormat.encode(...)` and `MyFormat.decode(...)` available from Lyng after importing the module.

(1)
: ISO8601 flavor `1970-05-06T06:00:00.000Z` is used; number of fractional digits depends on truncation on
`Instant`, see `Instant.truncateTo...` functions.

(2)
: Lists may contain any values serializable by the selected JSON layer.
