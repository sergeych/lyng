# What's New in Lyng 1.5 (vs 1.3.* / master)

This document summarizes the significant changes and new features introduced in the 1.5.0-SNAPSHOT development cycle.

## Highlights

- **The `return` Statement**: Added support for local and non-local returns using labels.
- **Abstract Classes and Interfaces**: Full support for `abstract` members and the `interface` keyword.
- **Class Properties with Accessors**: Define `val` and `var` properties with custom `get()` and `set()`.
- **Restricted Setter Visibility**: Use `private set` and `protected set` on fields and properties.
- **Late-initialized `val`**: Support for `val` fields that are initialized in `init` blocks or class bodies.
- **Named Arguments and Splats**: Improved call-site readability with `name: value` and map-based splats.
- **Refined Visibility**: Improved `protected` access and `closed` modifier for better encapsulation.

## Language Features

### The `return` Statement
You can now exit from the innermost enclosing callable (function or lambda) using `return`. Lyng also supports non-local returns to outer scopes using labels.

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

### Named Arguments and Named Splats
Improve call-site clarity by specifying argument names. You can also expand a Map into named arguments using the splat operator.

```lyng
fun configure(timeout: Int, retry: Int = 3) { ... }

configure(timeout: 5000, retry: 5)
val options = { timeout: 1000, retry: 1 }
configure(...options)
```

### Modern Operators
The `?=` operator allows for concise "assign if null" logic.

```lyng
var cache: Map? = null
cache ?= { "status": "ok" } // Only assigns if cache is null
```

## Tooling and IDE

- **IDEA Plugin**: Significant improvements to autocompletion, documentation tooltips, and natural language support (Grazie integration).
- **CLI**: The `lyng fmt` command is now a first-class tool for formatting code with various options like `--check` and `--in-place`.
- **Performance**: Ongoing optimizations in the bytecode VM and compiler for faster execution and smaller footprint.

## Migration Guide (from 1.3.*)

1. **Check Visibility**: Refined `protected` and `private` rules may catch previously undetected invalid accesses.
2. **Override Keyword**: Ensure all members that override ancestor declarations are marked with the `override` keyword (now mandatory).
3. **Return in Shorthand**: Remember that `return` is forbidden in `=` shorthand functions; use block syntax if you need early exit.

## References
- `docs/OOP.md`
- `docs/return_statement.md`
- `docs/tutorial.md`
