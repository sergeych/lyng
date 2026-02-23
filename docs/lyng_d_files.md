# `.lyng.d` Definition Files

`.lyng.d` files declare Lyng symbols for tooling without shipping runtime implementations. The IntelliJ IDEA plugin merges
all `*.lyng.d` files from the current directory and its parent directories into the active file’s analysis, enabling:

- completion
- navigation
- error checking for declared symbols
- Quick Docs for declarations defined in `.lyng.d`

Place `*.lyng.d` files next to the code they describe (or in a parent folder). The plugin will pick them up automatically.

## Writing `.lyng.d` Files

You can declare any language-level symbol in a `.lyng.d` file. Use doc comments before declarations to make Quick Docs
work in the IDE. The doc parser accepts standard comments (`/** ... */` or `// ...`) and supports tags like `@param`.

### Full Example

```lyng
/** Library entry point */
extern fun connect(url: String, timeoutMs: Int = 5000): Client

/** Type alias with generics */
type NameMap = Map<String, String>

/** Multiple inheritance via interfaces */
interface A { abstract fun a(): Int }
interface B { abstract fun b(): Int }

/** A concrete class implementing both */
class Multi(name: String) : A, B {
    /** Public field */
    val id: Int = 0
    
    /** Mutable property with accessors */
    var size: Int
        get() = 0
        set(v) { }
    
    /** Instance method */
    fun a(): Int = 1
    fun b(): Int = 2
}

/** Nullable and dynamic types */
extern val dynValue: dynamic
extern var dynVar: dynamic?

/** Delegated property */
class LazyBox(val create) {
    fun getValue(thisRef, name) = create()
}

val cached by LazyBox { 42 }

/** Delegated function */
object RpcDelegate {
    fun invoke(thisRef, name, args...) = Unset
}

fun remoteCall by RpcDelegate

/** Singleton object */
object Settings {
    val version: String = "1.0"
}

/** Class with documented members */
class Client {
    /** Returns a greeting. */
    fun greet(name: String): String = "hi " + name
}
```

See a runnable sample file in `docs/samples/definitions.lyng.d`.

Notes:
- Use real bodies if the declaration is not `extern` or `abstract`.
- If you need purely declarative stubs, prefer `extern` members (see `embedding.md`).

## Doc Comment Format

Doc comments are picked up when they immediately precede a declaration.

```lyng
/**
 * A sample function.
 * @param name user name
 * @return greeting string
 */
fun greet(name: String): String = "hi " + name
```

## Generating `.lyng.d` Files

You can generate `.lyng.d` as part of your build. A common approach is to write a Gradle task that emits a file from a
template or a Kotlin data model.

Example (pseudo-code):

```kotlin
tasks.register("generateLyngDefs") {
    doLast {
        val out = file("src/main/lyng/api.lyng.d")
        out.writeText(
            """
            /** Generated API */
            fun ping(): Int
            """.trimIndent()
        )
    }
}
```

Place the generated file in your source tree, and the IDE will load it automatically.
