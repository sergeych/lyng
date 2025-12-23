# Efficient Iterables in Kotlin Interop

Lyng provides high-performance iteration mechanisms that allow Kotlin-side code to interact with Lyng iterables efficiently and vice versa.

## 1. Enumerating Lyng Objects from Kotlin

To iterate over a Lyng object (like a `List`, `Set`, or `Range`) from Kotlin code, use the virtual `enumerate` method:

```kotlin
val lyngList: Obj = ... 
lyngList.enumerate(scope) { item ->
    println("Processing $item")
    true // return true to continue, false to break
}
```

### Why it's efficient:
- **Zero allocation**: Unlike traditional iterators, it doesn't create a `LyngIterator` object or any intermediate wrappers.
- **Direct access**: Subclasses like `ObjList` override `enumerate` to iterate directly over their internal Kotlin collections.
- **Reduced overhead**: It avoids multiple `invokeInstanceMethod` calls for `hasNext()` and `next()` on every step, which would normally involve dynamic dispatch and scope overhead.

## 2. Reactive Enumeration with Flow

If you prefer a reactive approach or need to integrate with Kotlin Coroutines flows, use `toFlow()`:

```kotlin
lyngList.toFlow(scope).collect { item ->
    // ...
}
```
*Note: `toFlow()` internally uses the Lyng iterator protocol (`iterator()`, `hasNext()`, `next()`), so it's slightly less efficient than `enumerate()` for performance-critical loops, but more idiomatic for flow-based processing.*

## 3. Creating Efficient Iterables for Lyng in Kotlin

When implementing a custom object in Kotlin that should be iterable in Lyng (e.g., usable in `for (x in myObj) { ... }`), follow these steps to ensure maximum performance.

### A. Inherit from `Obj` and use `ObjIterable`
Ensure your object's class has `ObjIterable` as a parent so the Lyng compiler recognizes it as an iterable.

```kotlin
class MyCollection(val items: List<Obj>) : Obj() {
    override val objClass = MyCollection.type

    companion object {
        val type = ObjClass("MyCollection", ObjIterable).apply {
            // Provide a Lyng-side iterator for compatibility with 
            // manual iterator usage in Lyng scripts.
            // Using ObjKotlinObjIterator if items are already Obj instances:
            addFn("iterator") { 
                ObjKotlinObjIterator(thisAs<MyCollection>().items.iterator()) 
            }
        }
    }
}
```

### B. Override `enumerate` for Maximum Performance
The Lyng compiler's `for` loops use the `enumerate` method. By overriding it in your Kotlin class, you provide a "fast path" for iteration.

```kotlin
class MyCollection(val items: List<Obj>) : Obj() {
    // ...
    override suspend fun enumerate(scope: Scope, callback: suspend (Obj) -> Boolean) {
        for (item in items) {
            // If callback returns false, it means 'break' was called in Lyng
            if (!callback(item)) break
        }
    }
}
```

### C. Use `ObjInt.of()` for Numeric Data
If your iterable contains integers, always use `ObjInt.of(Long)` instead of the `ObjInt(Long)` constructor. Lyng maintains a cache for small integers (-128 to 127), which significantly reduces object allocations and GC pressure during tight loops.

```kotlin
// Efficiently creating an integer object
val obj = ObjInt.of(42L) 

// Or using extension methods which also use the cache:
val obj2 = 42.toObj()
val obj3 = 42L.toObj()
```

#### Note on `toObj()` extensions:
While `<reified T> T.toObj()` is convenient, using specific extensions like `Int.toObj()` or `Long.toObj()` is slightly more efficient as they use the `ObjInt` cache.

## 4. Summary of Best Practices

- **To Consume**: Use `enumerate(scope) { item -> ... true }`.
- **To Implement**: Override `enumerate` in your `Obj` subclass.
- **To Register**: Use `ObjIterable` (or `ObjCollection`) as a parent class in your `ObjClass` definition.
- **To Optimize**: Use `ObjInt.of()` (or `.toObj()`) for all integer object allocations.
