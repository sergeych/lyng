# What's New in Lyng

This document highlights the latest additions and improvements to the Lyng language and its ecosystem.

## Language Features

### Class Properties with Accessors
Classes now support properties with custom `get()` and `set()` accessors. Properties in Lyng do **not** have automatic backing fields; they are pure accessors.

```lyng
class Person(private var _age: Int) {
    // Read-only property
    val ageCategory get() = if (_age < 18) "Minor" else "Adult"

    // Read-write property
    var age: Int
        get() = _age
        set(v) {
            if (v >= 0) _age = v
        }
}
```

### Private and Protected Setters
You can now restrict the visibility of a property's or field's setter using `private set` or `protected set`. This allows members to be publicly readable but only writable from within the declaring class or its subclasses.

```lyng
class Counter {
    var count = 0
        private set  // Field with private setter
        
    fun increment() { count++ }
}

class AdvancedCounter : Counter {
    var totalOperations = 0
        protected set // Settable here and in further subclasses
}

let c = Counter()
c.increment() // OK
// c.count = 10 // Error: setter is private
```

### Late-initialized `val` Fields
`val` fields in classes can be declared without an immediate initializer, provided they are assigned exactly once. If accessed before initialization, they hold the special `Unset` singleton.

```lyng
class Service {
    val logger
    
    fun check() {
        if (logger == Unset) println("Not initialized yet")
    }

    init {
        logger = Logger("Service")
    }
}
```

### Named Arguments and Named Splats
Function calls now support named arguments using the `name: value` syntax. If the variable name matches the parameter name, you can use the `name:` shorthand.

```lyng
fun greet(name, greeting = "Hello") {
    println("$greeting, $name!")
}

val name = "Alice"
greet(name:)                  // Shorthand for greet(name: name)
greet(greeting: "Hi", name: "Bob")

let params = { name: "Charlie", greeting: "Hey")
greet(...params)              // Named splat expansion
```

### Multiple Inheritance (MI)
Lyng now supports multiple inheritance using the C3 Method Resolution Order (MRO). Use `this@Type` or casts for disambiguation.

```lyng
class A { fun foo() = "A" }
class B { fun foo() = "B" }

class Derived : A, B {
    fun test() {
        println(foo())              // Resolves to A.foo (leftmost)
        println(this@B.foo())       // Qualified dispatch to B.foo
    }
}

let d = Derived()
println((d as B).foo())             // Disambiguation via cast
```

### Singleton Objects
Singleton objects are declared using the `object` keyword. They provide a convenient way to define a class and its single instance in one go.

```lyng
object Config {
    val version = "1.2.3"
    fun show() = println("Config version: " + version)
}

Config.show()
```

### Object Expressions
You can now create anonymous objects that inherit from classes or interfaces using the `object : Base { ... }` syntax. These expressions capture their lexical scope and support multiple inheritance.

```lyng
val worker = object : Runnable {
    override fun run() = println("Working...")
}

val x = object : Base(arg1), Interface1 {
    val property = 42
    override fun method() = this@object.property * 2
}
```

Use `this@object` to refer to the innermost anonymous object instance when `this` is rebound.

### Unified Delegation Model
A powerful new delegation system allows `val`, `var`, and `fun` members to delegate their logic to other objects using the `by` keyword.

```lyng
// Property delegation
val lazyValue by lazy { "expensive" }

// Function delegation
fun remoteAction by myProxy

// Observable properties
var name by Observable("initial") { n, old, new -> 
    println("Changed!") 
}
```

The system features a unified interface (`getValue`, `setValue`, `invoke`) and a `bind` hook for initialization-time validation and configuration. See the [Delegation Guide](delegation.md) for more.

### User-Defined Exception Classes
You can now create custom exception types by inheriting from the built-in `Exception` class. Custom exceptions are real classes that can have their own fields and methods, and they work seamlessly with `throw` and `try-catch` blocks.

```lyng
class ValidationException(val field, m) : Exception(m)

try {
    throw ValidationException("email", "Invalid format")
}
catch(e: ValidationException) {
    println("Error in " + e.field + ": " + e.message)
}
```

### Assign-if-null Operator (`?=`)
The new `?=` operator provides a concise way to assign a value only if the target is `null`. It is especially useful for setting default values or lazy initialization.

```lyng
var x = null
x ?= 42          // x is now 42
x ?= 100         // x remains 42 (not null)

// Works with properties and index access
config.port ?= 8080
settings["theme"] ?= "dark"
```

The operator returns the final value of the receiver (the original value if it was not `null`, or the new value if the assignment occurred).

## Tooling and Infrastructure

### CLI: Formatting Command
A new `fmt` subcommand has been added to the Lyng CLI.

```bash
lyng fmt MyFile.lyng             # Print formatted code to stdout
lyng fmt --in-place MyFile.lyng  # Format file in-place
lyng fmt --check MyFile.lyng     # Check if file needs formatting
```

### IDEA Plugin: Autocompletion
Experimental lightweight autocompletion is now available in the IntelliJ plugin. It features type-aware member suggestions and inheritance-aware completion.

You can enable it in **Settings | Lyng Formatter | Enable Lyng autocompletion**.
