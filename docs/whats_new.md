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
