/**
 * Sample .lyng.d file for IDE support.
 * Demonstrates declarations and doc comments.
 */

/** Simple function with default and named parameters. */
extern fun connect(url: String, timeoutMs: Int = 5000): Client

/** Type alias with generics. */
type NameMap = Map<String, String>

/** Multiple inheritance via interfaces. */
interface A { abstract fun a(): Int }
interface B { abstract fun b(): Int }

/** A concrete class implementing both. */
class Multi(name: String) : A, B {
    /** Public field. */
    val id: Int = 0

    /** Mutable property with accessors. */
    var size: Int
        get() = 0
        set(v) { }

    /** Instance method. */
    fun a(): Int = 1
    fun b(): Int = 2
}

/** Nullable and dynamic types. */
extern val dynValue: dynamic
extern var dynVar: dynamic?

/** Delegated property provider. */
class LazyBox(val create) {
    fun getValue(thisRef, name) = create()
}

/** Delegated property using provider. */
val cached by LazyBox { 42 }

/** Delegated function. */
object RpcDelegate {
    fun invoke(thisRef, name, args...) = Unset
}

/** Remote function proxy. */
fun remoteCall by RpcDelegate

/** Singleton object. */
object Settings {
    /** Version string. */
    val version: String = "1.0"
}

/**
 * Client API entry.
 * @param name user name
 * @return greeting string
 */
class Client {
    /** Returns a greeting. */
    fun greet(name: String): String = "hi " + name
}
