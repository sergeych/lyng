# What's New in Lyng 1.5 (vs 1.3.* / master)

This document summarizes the significant changes and new features introduced in the 1.5 development cycle.

## Principal changes

### JIT compiler and compile-time types and symbols.

This major improvement gives the following big advantages:

- **Blazing Fast execution**: several times faster! (three to six times speedup in different scenarios).
- **Better IDE support**: autocompletion, early error detection, types check.
- **Error safety**: all symbols and types are checked at bound at compile-time. Many errors are detected earlier. Also, no risk that external or caller code would shadow some internally used symbols (especially in closures and inheritance).

In particular, it means no slow and flaky runtime lookups. Once compiled, code guarantees that it will always call the symbol known at compile-time; runtime name lookup though does not guarantee it and can be source of hard to trace bugs. 

### New stable API to create Kotlin extensions

The API is fixed and will be kept with further Lyng core changes. It is now the recommended way to write Lyng extensions in Kotlin. It is much simpler and more elegant than the internal one. See [Kotlin Bridge Binding](../notes/kotlin_bridge_binding.md).

Extern declaration clarification:
- `extern class` / `extern object` are pure extern surfaces.
- Members inside them must be explicitly marked `extern`.
- Lyng method/property bodies for these declarations should be implemented as extensions instead.

### Smart types system

- **Deep inference**: The compiler analyzes types of symbols along the execution path and in many cases eliminates unnecessary casts or type specifications.
- **Union and intersection types**: `A & B`, `A | B`.
- **Generics**: Generic types are first-class citizens with support for [bounds and variance](generics.md). Type params are erased by default and are reified only when needed (e.g., `T::class`, `T is ...`, `as T`, or in extern-facing APIs), which enables checks like `A in T` when `T` is reified.
- **Inner classes and enums**: Full support for nested declarations, including [Enums with lifting](OOP.md#lifted-enum-entries).

## Other highlights

- **The `return` Statement**: Added support for local and non-local returns using labels.
- **Abstract Classes and Interfaces**: Full support for `abstract` members and the `interface` keyword.
- **Singleton Objects**: Define singletons using the `object` keyword or use anonymous object expressions.
- **Multiple Inheritance**: Enhanced multi-inheritance with predictable [C3 MRO resolution](OOP.md#multiple-inheritance-and-mro).
- **Unified Delegation**: Powerful delegation model for `val`, `var`, and `fun` members. See [Delegation](delegation.md).
- **Class Properties with Accessors**: Define `val` and `var` properties with custom `get()` and `set()`.
- **Restricted Setter Visibility**: Use `private set` and `protected set` on fields and properties.
- **Late-initialized `val`**: Support for `val` fields that are initialized in `init` blocks or class bodies.
- **Transient Members**: Use `@Transient` to exclude members from serialization and equality checks.
- **Named Arguments and Splats**: Improved call-site readability with `name: value` and map-based splats.
- **Refined Visibility**: Improved `protected` access and `closed` modifier for better encapsulation.

## Language Features

### The `return` Statement
You can now exit from the innermost enclosing callable (function or lambda) using `return`. Lyng also supports non-local returns to outer scopes using labels. See [Return Statement](return_statement.md).

```lyng
fun findFirst<T>(list: Iterable<T>, predicate: (T)->Bool): T? {
    list.forEach {
        if (predicate(it)) return@findFirst it
    }
    null
}
```

### Abstract Classes and Interfaces
Lyng now supports the `abstract` modifier for classes and their members. `interface` is introduced as a synonym for `abstract class`, allowing for rich multi-inheritance patterns.

```lyng
interface Shape {
    abstract val area: Real
    fun describe() = "Area: %g"(area)
}

class Circle(val radius: Real) : Shape {
    override val area get = Math.PI * radius * radius
}
```

### Class Properties with Accessors
Properties can now have custom getters and setters. They do not have automatic backing fields, making them perfect for computed values or delegation.

```lyng
class Rectangle(var width: Real, var height: Real) {
    val area: Real get() = width * height
    
    var squareSize: Real
        get() = area
        set(v) {
            width = sqrt(v)
            height = width
        }
}
```

### Singleton Objects
Declare singletons or anonymous objects easily.

```lyng
object Database {
    val connection = "connected"
}

val runner = object : Runnable {
    override fun run() = println("Running!")
}
```

### Named Arguments and Named Splats
Improve call-site clarity by specifying argument names. You can also expand a Map into named arguments using the splat operator.

```lyng
fun configure(timeout: Int, retry: Int = 3) { ... }

configure(timeout: 5000, retry: 5)
val options = Map("timeout": 1000, "retry": 1)
configure(...options)
```

### Modern Operators
The `?=` operator allows for concise "assign if null" logic.

```lyng
var cache: Map? = null
cache ?= Map("status": "ok") // Only assigns if cache is null
```

## Tooling and IDE

- **IDEA Plugin**: Significant improvements to autocompletion, documentation tooltips, and natural language support (Grazie integration).
- **CLI**: The `lyng fmt` command is now a first-class tool for formatting code with various options like `--check` and `--in-place`.
- **Performance**: Ongoing optimizations in the bytecode VM and compiler for faster execution and smaller footprint.

## Standard Library
- **`with(self, block)`**: Scoped execution with a dedicated `self`.
- **`clamp(value, min, max)`**: Easily restrict values to a range.

## Migration Guide (from 1.3.*)

1. **Check Visibility**: Refined `protected` and `private` rules may catch previously undetected invalid accesses.
2. **Override Keyword**: Ensure all members that override ancestor declarations are marked with the `override` keyword (now mandatory).
3. **Return in Shorthand**: Remember that `return` is forbidden in `=` shorthand functions; use block syntax if you need early exit.
4. **Empty Map Literals**: Use `Map()` or `{:}` for empty maps, as `{}` is now strictly a block/lambda.

## References
- [Object Oriented Programming](OOP.md)
- [Generics](generics.md)
- [Return Statement](return_statement.md)
- [Delegation](delegation.md)
- [Tutorial](tutorial.md)
