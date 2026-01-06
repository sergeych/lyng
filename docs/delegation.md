# Delegation in Lyng

Delegation is a powerful pattern that allows you to outsource the logic of properties (`val`, `var`) and functions (`fun`) to another object. This enables code reuse, separation of concerns, and the implementation of common patterns like lazy initialization, observable properties, and remote procedure calls (RPC) with minimal boilerplate.

## The `by` Keyword

Delegation is triggered using the `by` keyword in a declaration. The expression following `by` is evaluated once when the member is initialized, and the resulting object becomes the **delegate**.

```lyng
val x by MyDelegate()
var y by MyDelegate()
fun f by MyDelegate()
```

## The Unified Delegate Model

A delegate object can implement any of the following methods to intercept member access. All methods receive the `thisRef` (the instance containing the member) and the `name` of the member.

```lyng
interface Delegate {
    // Called when a 'val' or 'var' is read
    fun getValue(thisRef, name)
    
    // Called when a 'var' is assigned
    fun setValue(thisRef, name, newValue)
    
    // Called when a 'fun' is invoked
    fun invoke(thisRef, name, args...)
    
    // Optional: Called once during initialization to "bind" the delegate
    // Can be used for validation or to return a different delegate instance
    fun bind(name, access, thisRef) = this
}
```

### Delegate Access Types

The `bind` method receives an `access` parameter of type `DelegateAccess`, which can be one of:
- `DelegateAccess.Val`
- `DelegateAccess.Var`
- `DelegateAccess.Callable` (for `fun`)

## Usage Cases and Examples

### 1. Lazy Initialization

The classic `lazy` pattern ensures a value is computed only when first accessed and then cached. In Lyng, `lazy` is implemented as a class that follows this pattern. While classes typically start with an uppercase letter, `lazy` is an exception to make its usage feel like a native language feature.

```lyng
class lazy(val creator) : Delegate {
    private var value = Unset

    override fun bind(name, access, thisRef) {
        if (access != DelegateAccess.Val) throw "lazy delegate can only be used with 'val'"
        this
    }

    override fun getValue(thisRef, name) {
        if (value == Unset) {
            // calculate value using thisRef as this:
            value = with(thisRef) creator()
        }
        value
    }
}

// Usage:
val expensiveData by lazy {
    println("Performing expensive computation...")
    42
}

println(expensiveData) // Computes and prints 42
println(expensiveData) // Returns 42 immediately
```

### 2. Observable Properties

Delegates can be used to react to property changes.

```lyng
class Observable(initialValue, val onChange) {
    private var value = initialValue

    fun getValue(thisRef, name) = value
    
    fun setValue(thisRef, name, newValue) {
        val oldValue = value
        value = newValue
        onChange(name, oldValue, newValue)
    }
}

class User {
    var name by Observable("Guest") { name, old, new ->
        println("Property %s changed from %s to %s"(name, old, new))
    }
}

val u = User()
u.name = "Alice" // Prints: Property name changed from Guest to Alice
```

### 3. Function Delegation (Proxies)

You can delegate an entire function to an object. This is particularly useful for implementing decorators or RPC clients.

```lyng
object LoggerDelegate {
    fun invoke(thisRef, name, args...) {
        println("Calling function: " + name + " with args: " + args)
        // Logic here...
        "Result of " + name
    }
}

fun remoteAction by LoggerDelegate

println(remoteAction(1, 2, 3))
// Prints: Calling function: remoteAction with args: [1, 2, 3]
// Prints: Result of remoteAction
```

### 4. Stateless Delegates (Shared Singletons)

Because `getValue`, `setValue`, and `invoke` receive `thisRef`, a single object can act as a delegate for multiple properties across many instances without any per-property memory overhead.

```lyng
object Constant42 {
    fun getValue(thisRef, name) = 42
}

class Foo {
    val a by Constant42
    val b by Constant42
}

val f = Foo()
assertEquals(42, f.a)
assertEquals(42, f.b)
```

### 5. Local Delegation

Delegation is not limited to class members; you can also use it for local variables inside functions.

```lyng
fun test() {
    val x by LocalProxy(123)
    println(x)
}
```

## The `bind` Hook

The `bind(name, access, thisRef)` method is called exactly once when the member is being initialized. It allows the delegate to:
1.  **Validate usage**: Throw an error if the delegate is used with the wrong member type (e.g., `lazy` on a `var`).
2.  **Initialize state**: Set up internal state based on the property name or the containing instance.
3.  **Substitute itself**: Return a different object that will act as the actual delegate.

```lyng
class ValidatedDelegate() {
    fun bind(name, access, thisRef) {
        if (access == DelegateAccess.Var) {
            throw "This delegate cannot be used with 'var'"
        }
        this
    }
    
    fun getValue(thisRef, name) = "Validated"
}
```

## Summary

Delegation in Lyng combines the elegance of Kotlin-style properties with the flexibility of dynamic function interception. By unifying `val`, `var`, and `fun` delegation into a single model, Lyng provides a consistent and powerful tool for meta-programming and code reuse.
