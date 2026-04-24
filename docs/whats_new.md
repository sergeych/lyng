# What's New in Lyng

This document highlights the current Lyng release, **1.5.5**, and the broader additions from the 1.5 cycle.
It is intentionally user-facing: new language features, new modules, new tools, and the practical things you can build with them.
For a programmer-focused migration summary across 1.5.x, see `docs/whats_new_1_5.md`.

## Release 1.5.5 Highlights

- `1.5.5` extends the 1.5 line with practical database APIs, first-class calendar dates, and better coroutine building blocks.
- The 1.5 line now brings together richer ranges and loops, interpolation, math modules, immutable and observable collections, richer `lyngio`, and much better CLI/IDE support.
- `1.5.5` adds `Channel`, `LaunchPool`, and `joinAll()` so coroutine-heavy scripts can coordinate work more directly.
- `1.5.5` adds `Date`, the portable `lyng.io.db` layer, SQLite/JDBC providers, and a compatibility `lyng.legacy_digest` module.
- `1.5.5` also continues runtime/compiler hardening with better import dispatch, faster exact lambda calls, and correct `val +=`/`-=` behavior for mutating types versus real reassignment.
- The docs, homepage samples, and release metadata now point at the current stable version.

## User Highlights Across 1.5.x

- Descending ranges and loops with `downTo` / `downUntil`
- String interpolation with `$name` and `${expr}`
- Backtick string literals for raw-ish string text
- Decimal arithmetic, matrices/vectors, and complex numbers
- Calendar `Date` support in `lyng.time`
- `Channel`, `LaunchPool`, and `joinAll()` for coroutine workflows
- Immutable collections and opt-in `ObservableList`
- Rich `lyngio` modules for SQLite/JDBC databases, console, HTTP, WebSocket, TCP, and UDP
- Legacy SHA-1 compatibility helpers in `lyng.legacy_digest`
- CLI improvements including the built-in formatter `lyng fmt`
- Better IDE support and stronger docs around the released feature set

## Language Features

### Descending Ranges and Loops
Lyng ranges are no longer just ascending. You can now write explicit descending ranges with inclusive or exclusive lower bounds.

```lyng
assertEquals([5,4,3,2,1], (5 downTo 1).toList())
assertEquals([5,4,3,2], (5 downUntil 1).toList())

for (i in 10 downTo 1 step 3) {
    println(i)
}
```

This also works for characters:

```lyng
assertEquals(['e','c','a'], ('e' downTo 'a' step 2).toList())
```

See [Range](Range.md).

### String Interpolation
Lyng 1.5.1 added built-in string interpolation:

- `$name`
- `${expr}`

Literal dollar forms are explicit too:

- `\$` -> `$`
- `$$` -> `$`

```lyng
val name = "Lyng"
assertEquals("hello, Lyng!", "hello, $name!")
assertEquals("sum=3", "sum=${1+2}")
assertEquals("\$name", "\$name")
assertEquals("\$name", "$$name")
```

If you need legacy literal-dollar behavior in a file, add:

```lyng
// feature: interpolation: off
```

See [Tutorial](tutorial.md).

### Matrix and Vector Module (`lyng.matrix`)
Lyng now ships a dense linear algebra module with immutable double-precision `Matrix` and `Vector` types.

It provides:

- `matrix([[...]])` and `vector([...])`
- matrix multiplication
- matrix inversion
- determinant, trace, rank
- solving `A * x = b`
- vector operations such as `dot`, `normalize`, `cross`, and `outer`

```lyng
import lyng.matrix

val a: Matrix = matrix([[4, 7], [2, 6]])
val inv: Matrix = a.inverse()
assert(abs(inv.get(0, 0) - 0.6) < 1e-9)
```

Matrices also support Lyng-style slicing:

```lyng
import lyng.matrix

val m: Matrix = matrix([[1, 2, 3], [4, 5, 6], [7, 8, 9]])
assertEquals(6.0, m[1, 2])
val column: Matrix = m[0..2, 2]
val tail: Matrix = m[1.., 1..]
assertEquals([[3.0], [6.0], [9.0]], column.toList())
assertEquals([[5.0, 6.0], [8.0, 9.0]], tail.toList())
```

See [Matrix](Matrix.md).

### Multiple Selectors in Bracket Indexing
Bracket indexing now accepts more than one selector:

```lyng
value[i]
value[i, j]
value[i, j, k]
```

For custom indexers, multiple selectors are packed into one list-like index object and dispatched through `getAt` / `putAt`.
This is the rule used by `lyng.matrix` and by embedding APIs for Kotlin-backed indexers.

### Decimal Arithmetic Module (`lyng.decimal`)
Lyng now ships a first-class decimal module built as a regular extension library rather than a deep core special case.

It provides:

- `Decimal`
- convenient `.d` conversions from `Int`, `Real`, and `String`
- mixed arithmetic with `Int` and `Real`
- local division precision and rounding control via `withDecimalContext(...)`

```lyng
import lyng.decimal

assertEquals("3", (1 + 2.d).toStringExpanded())
assertEquals("0.30000000000000004", (0.1 + 0.2).d.toStringExpanded())
assertEquals("0.3", "0.3".d.toStringExpanded())

assertEquals(
    "0.3333333333",
    withDecimalContext(10) { (1.d / 3.d).toStringExpanded() }
)
```

The distinction between `Real -> Decimal` and exact decimal parsing is explicit by design:

- `2.2.d` converts the current `Real` value
- `"2.2".d` parses exact decimal text

See [Decimal](Decimal.md).

### Complex Numbers (`lyng.complex`)
Lyng also ships a complex-number module for ordinary arithmetic in the complex plane.

```lyng
import lyng.complex

assertEquals(Complex(1.0, 2.0), 1 + 2.i)
assertEquals(Complex(2.0, 2.0), 2.i + 2)

val z = 1 + π.i
println(z.exp())
```

See [Complex](Complex.md).

### Legacy Digest Module (`lyng.legacy_digest`)

For situations where an external protocol or file format requires a SHA-1 value,
Lyng now ships a `lyng.legacy_digest` module backed by a pure Kotlin/KMP
implementation with no extra dependencies.

> ⚠️ SHA-1 is cryptographically broken.  Use only for legacy-compatibility work.

```lyng
import lyng.legacy_digest

val hex = LegacyDigest.sha1("abc")
// → "a9993e364706816aba3e25717850c26c9cd0d89d"

// Also accepts raw bytes:
import lyng.buffer
val buf = Buffer.decodeHex("616263")
assertEquals(hex, LegacyDigest.sha1(buf))
```

The name `LegacyDigest` is intentional: it signals that these algorithms belong
to a compatibility layer, not to a current security toolkit.

See [LegacyDigest](LegacyDigest.md).

### Binary Operator Interop Registry
Lyng now provides a general mechanism for mixed binary operators through `lyng.operators`.

This solves cases like:

- `Int + MyType`
- `Real < MyType`
- `Int == MyType`

without requiring changes to built-in classes.

```lyng
import lyng.operators

class DecimalBox(val value: Int) {
    fun plus(other: DecimalBox) = DecimalBox(value + other.value)
    fun compareTo(other: DecimalBox) = value <=> other.value
}

OperatorInterop.register(
    Int,
    DecimalBox,
    DecimalBox,
    [BinaryOperator.Plus, BinaryOperator.Compare, BinaryOperator.Equals],
    { x: Int -> DecimalBox(x) },
    { x: DecimalBox -> x }
)

assertEquals(DecimalBox(3), 1 + DecimalBox(2))
assert(1 < DecimalBox(2))
assert(2 == DecimalBox(2))
```

`lyng.decimal` uses this same mechanism internally to interoperate with `Int` and `Real`.

See [Operator Interop Registry](OperatorInterop.md).

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

### Refined Protected Visibility
Ancestor classes can now access `protected` members of their descendants if it is an override of a member known to the ancestor. This enables base classes to call protected methods that are implemented or overridden in subclasses.

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
    val version = "1.5.6-SNAPSHOT"
    fun show() = println("Config version: " + version)
}

Config.show()
```

Named singleton objects can also be used as extension receivers:

```lyng
object X {
    fun base() = "base"
}

fun X.decorate(value): String {
    this.base() + ":" + value.toString()
}

val X.tag get() = this.base() + ":tag"

assertEquals("base:42", X.decorate(42))
assertEquals("base:tag", X.tag)
```

### Nested Declarations and Lifted Enums
You can now declare classes, objects, enums, and type aliases inside another class. These nested declarations live in the class namespace (no outer instance capture) and are accessed with a qualifier.

```lyng
class A {
    class B(x?)
    object Inner { val foo = "bar" }
    enum E* { One, Two }
}

val ab = A.B()
assertEquals(ab.x, null)
assertEquals(A.Inner.foo, "bar")
assertEquals(A.One, A.E.One)
```

The `*` on `enum E*` lifts entries into the enclosing class namespace (compile-time error on ambiguity).

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

### Transient Attribute (`@Transient`)
The `@Transient` attribute can now be applied to class fields, constructor parameters, and static fields to exclude them from serialization.

```lyng
class MyData(@Transient val tempSecret, val publicData) {
    @Transient var cachedValue = 0
    var persistentValue = 42
}
```

Key features:
- **Serialization**: Transient members are omitted from both Lynon binary streams and JSON output.
- **Structural Equality**: Transient fields are automatically ignored during `==` equality checks.
- **Deserialization**: Transient constructor parameters with default values are correctly restored to those defaults upon restoration.

### Value Clamping (`clamp`)
A new `clamp()` function has been added to the standard library to limit a value within a specified range. It is available as both a global function and an extension method on all objects.

```lyng
// Global function
clamp(15, 0..10)    // returns 10
clamp(-5, 0..10)   // returns 0

// Extension method
val x = 15
x.clamp(0..10)      // returns 10

// Exclusive and open-ended ranges
15.clamp(0..<10)    // returns 9
15.clamp(..10)      // returns 10
-5.clamp(0..)       // returns 0
```

`clamp()` correctly handles inclusive (`..`) and exclusive (`..<`) ranges. For discrete types like `Int` and `Char`, clamping to an exclusive upper bound returns the previous value.

### Immutable Collections
Lyng 1.5 adds immutable collection types for APIs that should not expose mutable state through aliases:

- `ImmutableList`
- `ImmutableSet`
- `ImmutableMap`

```lyng
val a = ImmutableList(1,2,3)
val b = a + 4

assertEquals(ImmutableList(1,2,3), a)
assertEquals(ImmutableList(1,2,3,4), b)
```

See [ImmutableList](ImmutableList.md), [ImmutableSet](ImmutableSet.md), and [ImmutableMap](ImmutableMap.md).

### Observable Mutable Lists
For reactive-style code, `lyng.observable` provides `ObservableList` with hooks and change streams.

```lyng
import lyng.observable

val xs = [1,2].observable()
xs.onChange { println("changed") }
xs += 3
```

You can validate or reject mutations in `beforeChange`, listen in `onChange`, and consume structured change events from `changes()`.

See [ObservableList](ObservableList.md).

### Random API
The standard library now includes a built-in random API plus deterministic seeded generators.

```lyng
val rng = Random.seeded(1234)
assert(rng.next(1..10) in 1..10)
assert(rng.next('a'..<'f') in 'a'..<'f')
```

Use:

- `Random.nextInt()`
- `Random.nextFloat()`
- `Random.next(range)`
- `Random.seeded(seed)`

## Tooling and Infrastructure

### Rich Console Apps with `lyng.io.console`
`lyngio` now includes a real console module for terminal applications:

- TTY detection
- screen clearing and cursor movement
- alternate screen buffer
- raw input mode
- typed key and resize events

```lyng
import lyng.io.console

Console.enterAltScreen()
Console.clear()
Console.moveTo(1, 1)
Console.write("Hello from Lyng console app")
Console.flush()
Console.leaveAltScreen()
```

The repository includes a full interactive Tetris sample built on this API.

See [lyng.io.console](lyng.io.console.md).

### HTTP, WebSocket, TCP, and UDP in `lyngio`
`lyngio` grew from filesystem/process support into a broader application-facing I/O library. In 1.5.x it includes:

- `lyng.io.http` for HTTP/HTTPS client calls
- `lyng.io.ws` for WebSocket clients
- `lyng.io.net` for raw TCP/UDP transport

HTTP example:

```lyng
import lyng.io.http

val r = Http.get("https://example.com")
println(r.status)
println(r.text())
```

TCP example:

```lyng
import lyng.io.net

val socket = Net.tcpConnect("127.0.0.1", 4040)
socket.writeUtf8("ping")
socket.flush()
println(socket.readLine())
socket.close()
```

WebSocket example:

```lyng
import lyng.io.ws

val ws = Ws.connect("wss://example.com/socket")
ws.sendText("hello")
println(ws.receive())
ws.close()
```

These modules are capability-gated and host-installed, keeping Lyng safe by default while making networked scripts practical when enabled.

See [lyngio overview](lyngio.md), [lyng.io.db](lyng.io.db.md), [lyng.io.http](lyng.io.http.md), [lyng.io.ws](lyng.io.ws.md), and [lyng.io.net](lyng.io.net.md).

### CLI: Formatting Command
A new `fmt` subcommand has been added to the Lyng CLI.

```bash
lyng fmt MyFile.lyng             # Print formatted code to stdout
lyng fmt --in-place MyFile.lyng  # Format file in-place
lyng fmt --check MyFile.lyng     # Check if file needs formatting
```

### CLI: Better Terminal Workflows
The CLI is no longer just a script launcher. In the 1.5 line it also gained:

- built-in formatter support
- integrated `lyng.io.console` support for terminal programs
- downloadable packaged distributions for easier local use

This makes CLI-first scripting and console applications much more practical than in earlier releases.

### IDEA Plugin: Autocompletion
Experimental lightweight autocompletion is now available in the IntelliJ plugin. It features type-aware member suggestions and inheritance-aware completion.

You can enable it in **Settings | Lyng Formatter | Enable Lyng autocompletion**.

### Kotlin API: Exception Handling
The `Obj.getLyngExceptionMessageWithStackTrace()` extension method has been added to simplify retrieving detailed error information from Lyng exception objects in Kotlin. Additionally, `getLyngExceptionMessage()` and `raiseAsExecutionError()` now accept an optional `Scope`, making it easier to use them when a scope is not immediately available.

### Kotlin API: Bridge Reflection and Class Binding (Preferred Extensions)
Lyng now provides a public Kotlin reflection bridge and a Lyng‑first class binding workflow. This is the **preferred** way to write Kotlin extensions and library integrations:

- **Bridge resolver**: explicit handles for values, vars, and callables with predictable lookup rules.
- **Class bridge binding**: declare extern surfaces in Lyng (`extern` members, or members inside `extern class/object`) and bind the implementations in Kotlin before the first instance is created.
- **Extern declaration rule**: `extern class` / `extern object` are declaration-only; all members in their bodies are implicitly extern.

See **Embedding Lyng** for full samples and usage details.
