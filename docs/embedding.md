# Embedding Lyng in your Kotlin project

Lyng is a tiny, embeddable, Kotlin‑first scripting language. This page shows, step by step, how to:

- add Lyng to your build
- create a runtime and execute scripts
- define functions and variables from Kotlin
- read variable values back in Kotlin
- call Lyng functions from Kotlin
- create your own packages and import them in Lyng

All snippets below use idiomatic Kotlin and rely on Lyng public APIs. They work on JVM and other Kotlin Multiplatform targets supported by `lynglib`.

Note: all Lyng APIs shown are `suspend`, because script evaluation is coroutine‑friendly and can suspend.

### 1) Add Lyng to your build

Add the repository where you publish Lyng artifacts and the dependency on the core library `lynglib`.

Gradle Kotlin DSL (build.gradle.kts):

```kotlin
repositories {
    // Your standard repos
    mavenCentral()

    // If you publish to your own Maven (example: Gitea packages). Adjust URL/token as needed.
    maven(url = uri("https://gitea.sergeych.net/api/packages/SergeychWorks/maven"))
}

dependencies {
    // Multiplatform: place in appropriate source set if needed
    implementation("net.sergeych:lynglib:1.0.0-SNAPSHOT")
}
```

If you use Kotlin Multiplatform, add the dependency in the `commonMain` source set (and platform‑specific sets if you need platform APIs).

### 2) Create a runtime (Scope) and execute scripts

The easiest way to get a ready‑to‑use scope with standard packages is via `Script.newScope()`.

```kotlin
fun main() = kotlinx.coroutines.runBlocking {
    val scope = Script.newScope() // suspends on first init

    // Evaluate a one‑liner
    val result = scope.eval("1 + 2 * 3")
    println("Lyng result: $result") // ObjReal/ObjInt etc.
}
```

You can also pre‑compile a script and execute it multiple times:

```kotlin
val script = Compiler.compile("""
    // any Lyng code
    val x = 40 + 2
    x
""")

val run1 = script.execute(scope)
val run2 = script.execute(scope)
```

`Scope.eval("...")` is a shortcut that compiles and executes on the given scope.

### 3) Define variables from Kotlin

To expose data to Lyng, add constants (read‑only) or mutable variables to the scope. All values in Lyng are `Obj` instances; the core types live in `net.sergeych.lyng.obj`.

```kotlin
// Read‑only constant
scope.addConst("pi", ObjReal(3.14159))

// Mutable variable: create or update
scope.addOrUpdateItem("counter", ObjInt(0))

// Use it from Lyng
scope.eval("counter = counter + 1")
```

Tip: Lyng values can be converted back to Kotlin with `toKotlin(scope)`:

```kotlin
val current = (scope.eval("counter")).toKotlin(scope) // Any? (e.g., Int/Double/String/List)
```

### 4) Add Kotlin‑backed functions

Use `Scope.addFn`/`addVoidFn` to register functions implemented in Kotlin. Inside the lambda, use `this.args` to access arguments and return an `Obj`.

```kotlin
// A function returning value
scope.addFn<ObjInt>("inc") {
    val x = args.firstAndOnly() as ObjInt
    ObjInt(x.value + 1)
}

// A void function (returns Lyng Void)
scope.addVoidFn("log") {
    val items = args.list // List<Obj>
    println(items.joinToString(" ") { it.toString(this).value })
}

// Call them from Lyng
scope.eval("val y = inc(41); log('Answer:', y)")
```

You can register multiple names (aliases) at once: `addFn<ObjInt>("inc", "increment") { ... }`.

### 5) Add Kotlin‑backed properties

Properties in Lyng are pure accessors (getters and setters) and do not have automatic backing fields. You can add them to a class using `addProperty`.

```kotlin
val myClass = ObjClass("MyClass")
var internalValue: Long = 10

myClass.addProperty(
    name = "value",
    getter = { 
        // Return current value as a Lyng object
        ObjInt(internalValue) 
    },
    setter = { newValue ->
        // newValue is passed as a Lyng object (the first and only argument)
        internalValue = (newValue as ObjInt).value
    }
)

scope.addConst("MyClass", myClass)
```

Usage in Lyng:
```kotlin
val instance = MyClass()
println(instance.value) // -> 10
instance.value = 42
println(instance.value) // -> 42
```

### 6) Read variable values back in Kotlin

The simplest approach: evaluate an expression that yields the value and convert it.

```kotlin
val kotlinAnswer = scope.eval("(1 + 2) * 3").toKotlin(scope) // -> 9 (Int)

// After scripts manipulate your vars:
scope.addOrUpdateItem("name", ObjString("Lyng"))
scope.eval("name = name + ' rocks!'")
val kotlinName = scope.eval("name").toKotlin(scope) // -> "Lyng rocks!"
```

Advanced: you can also grab a variable record directly via `scope.get(name)` and work with its `Obj` value, but evaluating `"name"` is often clearer and enforces Lyng semantics consistently.

### 7) Execute scripts with parameters; call Lyng functions from Kotlin

There are two convenient patterns.

1) Evaluate a Lyng call expression directly:

```kotlin
// Suppose Lyng defines: fun add(a, b) = a + b
scope.eval("fun add(a, b) = a + b")

val sum = scope.eval("add(20, 22)").toKotlin(scope) // -> 42
```

2) Call a Lyng function by name via a prepared call scope:

```kotlin
// Ensure the function exists in the scope
scope.eval("fun add(a, b) = a + b")

// Look up the function object
val addFn = scope.get("add")!!.value as Statement

// Create a child scope with arguments (as Lyng Objs)
val callScope = scope.createChildScope(
    args = Arguments(listOf(ObjInt(20), ObjInt(22)))
)

val resultObj = addFn.execute(callScope)
val result = resultObj.toKotlin(scope) // -> 42
```

If you need to pass complex data (lists, maps), construct the corresponding Lyng `Obj` types (`ObjList`, `ObjMap`, etc.) and pass them in `Arguments`.

### 8) Create your own packages and import them in Lyng

Lyng supports packages that are imported from scripts. You can register packages programmatically via `ImportManager` or by providing source texts that declare `package ...`.

Key concepts:

- `ImportManager` holds package registrations and lazily builds `ModuleScope`s when first imported.
- Every `Scope` has `currentImportProvider` and (if it’s an `ImportManager`) a convenience `importManager` to register packages.

Register a Kotlin‑built package:

```kotlin
val scope = Script.newScope()

// Access the import manager behind this scope
val im: ImportManager = scope.importManager

// Register a package "my.tools"
im.addPackage("my.tools") { module: ModuleScope ->
    // Expose symbols inside the module scope
    module.addConst("version", ObjString("1.0"))
    module.addFn<ObjInt>("triple") {
        val x = args.firstAndOnly() as ObjInt
        ObjInt(x.value * 3)
    }
}

// Use it from Lyng
scope.eval("""
    import my.tools.*
    val v = triple(14)
""")
val v = scope.eval("v").toKotlin(scope) // -> 42
```

Register a package from Lyng source text:

```kotlin
val pkgText = """
    package math.extra

    fun sqr(x) = x * x
""".trimIndent()

scope.importManager.addTextPackages(pkgText)

scope.eval("""
    import math.extra.*
    val s = sqr(12)
""")
val s = scope.eval("s").toKotlin(scope) // -> 144
```

You can also register from parsed `Source` instances via `addSourcePackages(source)`.

### 9) Executing from files, security, and isolation

- To run code from a file, read it and pass to `scope.eval(text)` or compile with `Compiler.compile(Source(fileName, text))`.
- `ImportManager` takes an optional `SecurityManager` if you need to restrict what packages or operations are available. By default, `Script.defaultImportManager` allows everything suitable for embedded use; clamp it down in sandboxed environments.
- For isolation, create fresh modules/scopes via `Scope.new()` or `Script.newScope()` when you need a clean environment per request.

```kotlin
// Fresh module based on the default manager, without the standard prelude
val isolated = net.sergeych.lyng.Scope.new()
```

### 10) Tips and troubleshooting

- All values that cross the boundary must be Lyng `Obj` instances. Convert Kotlin values explicitly (e.g., `ObjInt`, `ObjReal`, `ObjString`).
- Use `toKotlin(scope)` to get Kotlin values back. Collections convert to Kotlin collections recursively.
- Most public API in Lyng is suspendable. If you are not already in a coroutine, wrap calls in `runBlocking { ... }` on the JVM for quick tests.
- When registering packages, names must be unique. Register before you compile/evaluate scripts that import them.
- To debug scope content, `scope.toString()` and `scope.trace()` can help during development.

---

That’s it. You now have Lyng embedded in your Kotlin app: you can expose your app’s API, evaluate user scripts, and organize your own packages to import from Lyng code.
