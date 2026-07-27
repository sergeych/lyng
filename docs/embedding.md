# Embedding Lyng in your Kotlin project

[//]: # (topMenu)

Lyng is a tiny, embeddable, Kotlin‑first scripting language. This page shows, step by step, how to:

- add Lyng to your build
- create a runtime and execute scripts
- declare extern globals in Lyng and bind them from Kotlin
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

### 2) Preferred runtime: `EvalSession`

For host applications, prefer `EvalSession` as the main way to run scripts.
It owns one reusable Lyng scope, serializes `eval(...)` calls, and governs coroutines started from Lyng `launch { ... }`.

Main entrypoints:

- `session.eval(code)` / `session.eval(source)`
- `session.getScope()` when you need low-level binding APIs
- `session.cancel()` to cancel active session-owned coroutines
- `session.join()` to wait for active session-owned coroutines

```kotlin
fun main() = kotlinx.coroutines.runBlocking {
    val session = EvalSession()

    // Evaluate a one‑liner
    val result = session.eval("1 + 2 * 3")
    println("Lyng result: $result") // ObjReal/ObjInt etc.

    // Optional lifecycle management
    session.join()
}
```

The session creates its underlying scope lazily. If you need raw low-level APIs, get the scope explicitly:

```kotlin
val session = EvalSession()
val scope = session.getScope()
```

Use `cancel()` / `join()` to govern async work started by scripts:

```kotlin
val session = EvalSession()
session.eval("""launch { delay(1000); println("done") }""")
session.cancel()
session.join()
```

### 2.1) Low-level runtime: `Scope`

Use `Scope` directly when you intentionally want lower-level control.

```kotlin
fun main() = kotlinx.coroutines.runBlocking {
    val scope = Script.newScope() // suspends on first init
    val result = scope.eval("1 + 2 * 3")
    println("Lyng result: $result")
}
```

You can also pre‑compile a script and execute it multiple times on the same scope:

```kotlin
val script = Compiler.compile("""
    // any Lyng code
    val x = 40 + 2
    x
""")

val run1 = script.execute(scope)
val run2 = script.execute(scope)
```

`Scope.eval("...")` is the low-level shortcut that compiles and executes on the given scope.
For most embedding use cases, prefer `session.eval("...")`.

### 2.2) Inspect function signatures without executing source

Declaration generators and other host tools can ask the compiler for semantic metadata about
top-level functions without running the script:

```kotlin
val source = Source(
    "service.lyng",
    """
        @Export
        fun greet<T: Object = String>(
            first: T,
            rest: T...,
            punctuation: String = "!",
        ): String = first.toString() + punctuation
    """.trimIndent()
)

val functions = Compiler.resolveFunctionMetadata(
    source,
    Script.defaultImportManager,
)
val greet = functions.single()

println(greet.name)                         // greet
println(greet.annotations)                  // [Export]
println(greet.parameters[1].isEllipsis)     // true
println(greet.parameters[2].defaultSource)  // "!"
println(greet.returnType)                   // String
```

`resolveFunctionMetadata(...)` parses the source and resolves its imports and types, but it does
not execute the script, annotation functions, or default expressions. It returns
`ResolvedFunctionMetadata` entries in source order. Each entry provides:

- the function name, visibility, annotations, and source positions;
- generic parameters with their variance, bounds, and default types;
- parameters, variadic markers, and the original text of default expressions when available;
- the complete function type, including receivers, context receivers, and the declared or inferred
  return type.

For a variadic declaration such as `values: Int...`, `ResolvedFunctionParameter.type` is the
element type `Int`, `isEllipsis` is `true`, and the parameter in `functionType` is `Int...`.
Inside Lyng function code, the collected `values` variable has type `List<Int>`.

If you already compiled the source, obtain the same information without resolving it again:

```kotlin
val script = Compiler.compile(source, Script.defaultImportManager)
val functions = script.resolvedFunctionMetadata()
```

Only top-level function declarations are returned. Use the Mini-AST APIs when tooling also needs
nested declarations or a syntax-oriented representation.

### 2.3) Prepare imports separately from execution

Low-level hosts that own a scope can install a compiled script's imports without executing its
statements:

```kotlin
val script = Compiler.compile(source, importManager)
val scope = importManager.newModule()

script.importInto(scope)
val result = script.execute(scope)
```

`importInto(...)` is idempotent for a scope and is useful when import preparation and execution
belong to different host lifecycle phases. To create a fresh raw module with the script's imports
already installed, use `script.instantiateModule(seedScope)`. When supplied, `seedScope` provides
the import provider and any seed-bound imports used by the new module.

### 3) Preferred: bind extern globals from Kotlin

For module-level APIs, the default workflow is:

1. declare globals in Lyng using `extern fun` / `extern val` / `extern var`;
2. bind Kotlin implementation via `ModuleScope.globalBinder()`.

This is also the recommended way to expose a Kotlin-backed value that should behave like a true
Lyng global variable/property. If you need `x` to read/write through Kotlin on every access, use
`extern var` / `extern val` plus `bindGlobalVar(...)`.

Do not use `addConst(...)` for this case: `addConst(...)` installs a value, not a Kotlin-backed
property accessor. It is appropriate for fixed values and objects, but not for a global that should
delegate reads/writes back into Kotlin state.

```kotlin
import net.sergeych.lyng.bridge.*
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjString

val session = EvalSession()
val scope = session.getScope()
val im = Script.defaultImportManager.copy()
im.addPackage("my.api") { module ->
    module.eval("""
        extern fun globalFun(v: Int): Int
        extern var globalProp: String
        extern val globalVersion: String
    """.trimIndent())

    val binder = module.globalBinder()

    binder.bindGlobalFun1<Int>("globalFun") { v ->
        ObjInt.of((v + 1).toLong())
    }

    var prop = "initial"
    binder.bindGlobalVar(
        name = "globalProp",
        get = { prop },
        set = { prop = it }
    )

    binder.bindGlobalVar(
        name = "globalVersion",
        get = { "1.0.0" } // readonly: setter omitted
    )
}
```

Usage from Lyng:

```lyng
import my.api

assertEquals(42, globalFun(41))
assertEquals("initial", globalProp)
globalProp = "changed"
assertEquals("changed", globalProp)
assertEquals("1.0.0", globalVersion)
```

Minimal rule of thumb:

- use `bindGlobalFun(...)` for global functions
- use `bindGlobalVar(...)` for Kotlin-backed global variables/properties
- use `addConst(...)` only for fixed values/objects that do not need getter/setter behavior

For custom argument handling and full runtime access:

```kotlin
binder.bindGlobalFun("sum3") {
    requireExactCount(3)
    ObjInt.of((int(0) + int(1) + int(2)).toLong())
}

binder.bindGlobalFunRaw("echoRaw") { _, args ->
    args.firstAndOnly()
}
```

### 4) Low-level: direct functions/variables from Kotlin

Use this when you intentionally want raw `Scope` APIs. For most module APIs, prefer section 3.

```kotlin
val session = EvalSession()
val scope = session.getScope()

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

// When adding a member function to a class, you can use isOverride = true
// myClass.addFn("toString", isOverride = true) {
//     ObjString("Custom string representation")
// }

// Call them from Lyng
session.eval("val y = inc(41); log('Answer:', y)")
```

You can register multiple names (aliases) at once: `addFn<ObjInt>("inc", "increment") { ... }`.

Scope-backed Kotlin lambdas receive a `ScopeFacade` (not a full `Scope`). For migration and convenience, these utilities are available on the facade:

- Access: `args`, `pos`, `thisObj`, `get(name)`
- Invocation: `call(...)`, `resolve(...)`, `assign(...)`, `toStringOf(...)`, `inspect(...)`, `trace(...)`
- Args helpers: `requiredArg<T>()`, `requireOnlyArg<T>()`, `requireExactCount(...)`, `requireNoArgs()`, `thisAs<T>()`
- Errors: `raiseError(...)`, `raiseClassCastError(...)`, `raiseIllegalArgument(...)`, `raiseIllegalState(...)`, `raiseNoSuchElement(...)`,
  `raiseSymbolNotFound(...)`, `raiseNotImplemented(...)`, `raiseNPE()`, `raiseIndexOutOfBounds(...)`, `raiseIllegalAssignment(...)`,
  `raiseUnset(...)`, `raiseNotFound(...)`, `raiseAssertionFailed(...)`, `raiseIllegalOperation(...)`, `raiseIterationFinished()`

If you truly need the full `Scope` (e.g., for low-level interop), use `requireScope()` explicitly.

### 4.5) Indexers from Kotlin: `getAt` and `putAt`

Lyng bracket syntax is dispatched through `getAt` and `putAt`.

That means:

- `x[i]` calls `getAt(index)`
- `x[i] = value` calls `putAt(index, value)` or `setAt(index, value)`
- field-like `x["name"]` also uses the same index path unless you expose a real field/property

For Kotlin-backed classes, bind indexers as ordinary methods named `getAt` and `putAt`:

```kotlin
moduleScope.eval("""
    extern class Grid {
        override fun getAt(index: List<Int>): Int
        override fun putAt(index: List<Int>, value: Int): void
    }
""".trimIndent())

moduleScope.bind("Grid") {
    init { _ -> data = IntArray(4) }

    addFun("getAt") {
        val index = args.requiredArg<ObjList>(0)
        val row = (index.list[0] as ObjInt).value.toInt()
        val col = (index.list[1] as ObjInt).value.toInt()
        val data = (thisObj as ObjInstance).data as IntArray
        ObjInt.of(data[row * 2 + col].toLong())
    }

    addFun("putAt") {
        val index = args.requiredArg<ObjList>(0)
        val value = args.requiredArg<ObjInt>(1).value.toInt()
        val row = (index.list[0] as ObjInt).value.toInt()
        val col = (index.list[1] as ObjInt).value.toInt()
        val data = (thisObj as ObjInstance).data as IntArray
        data[row * 2 + col] = value
        ObjVoid
    }
}
```

Usage from Lyng:

```lyng
val g = Grid()
g[0, 1] = 42
assertEquals(42, g[0, 1])
```

Important rule: multiple selectors inside brackets are packed into one index object.
So:

- `x[i]` passes `i`
- `x[i, j]` passes a `List` containing `[i, j]`
- `x[i, j, k]` passes `[i, j, k]`

This applies equally to:

- Kotlin-backed classes
- Lyng classes overriding `getAt`
- `dynamic { get { ... } set { ... } }`

If you want multi-axis slicing semantics, decode that list yourself in `getAt`.

### 5) Add Kotlin‑backed fields

If you need a simple field (with a value) instead of a computed property, use `createField`. This adds a field to the class that will be present in all its instances.

```kotlin
val session = EvalSession()
val scope = session.getScope()
val myClass = ObjClass("MyClass")

// Add a read-only field (constant)
myClass.createField("version", ObjString("1.0.0"), isMutable = false)

// Add a mutable field with an initial value
myClass.createField("count", ObjInt(0), isMutable = true)

// If you are overriding a field from a base class, use isOverride = true
// myClass.createField("someBaseField", ObjInt(42), isOverride = true)

scope.addConst("MyClass", myClass)
```

In Lyng:
```lyng
val instance = MyClass()
println(instance.version) // -> "1.0.0"
instance.count = 5
println(instance.count)   // -> 5
```

### 6) Add Kotlin‑backed properties

Properties in Lyng are pure accessors (getters and setters) and do not have automatic backing fields. You can add them to a class using `addProperty`.

```kotlin
val session = EvalSession()
val scope = session.getScope()
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

// You can also create an ObjProperty explicitly
val explicitProp = ObjProperty(
    name = "hexValue",
    getter = statement { ObjString(internalValue.toString(16)) }
)
myClass.addProperty("hexValue", prop = explicitProp)

// Use isOverride = true when overriding a property from a base class
// myClass.addProperty("baseProp", getter = { ... }, isOverride = true)

scope.addConst("MyClass", myClass)
```

Usage in Lyng:
```lyng
val instance = MyClass()
println(instance.value) // -> 10
instance.value = 42
println(instance.value) // -> 42
```

### 6.5) Preferred: bind Kotlin implementations to declared Lyng classes

For extensions and libraries, the **preferred** workflow is Lyng‑first: declare the class and its members in Lyng, then bind the Kotlin implementations using the bridge.

This keeps Lyng semantics (visibility, overrides, type checks) in Lyng, while Kotlin supplies the behavior.

Pure extern declarations use the simplified rule set:
- `extern class` / `extern object` are declaration-only ABI surfaces.
- Every member in their body is implicitly extern (you may still write `extern`, but it is redundant).
- Plain Lyng member implementations inside `extern class` / `extern object` are not allowed.
- Put Lyng behavior into regular classes or extension methods.

```lyng
// Lyng side (in a module)
class Counter {
    extern var value: Int
    extern fun inc(by: Int): Int
}
```

Note: members of `extern class` / `extern object` are treated as extern by default, so the compiler emits ABI slots that Kotlin bindings attach to. This applies to functions and properties bound via `addFun` / `addVal` / `addVar`.

Example of pure extern class declaration:

```lyng
extern class HostCounter {
    var value: Int
    fun inc(by: Int): Int
}
```

If you need Lyng-side convenience behavior, add it as an extension:

```lyng
fun HostCounter.bump() = inc(1)
```

```kotlin
// Kotlin side (binding)
val moduleScope = Script.newScope() // or an existing module scope
moduleScope.eval("class Counter { extern var value: Int; extern fun inc(by: Int): Int }")

moduleScope.bind("Counter") {
    addVar(
        name = "value",
        get = { thisObj.readField(this, "value").value },
        set = { v -> thisObj.writeField(this, "value", v) }
    )
    addFun("inc") {
        val by = args.requiredArg<ObjInt>(0).value
        val current = thisObj.readField(this, "value").value as ObjInt
        val next = ObjInt(current.value + by)
        thisObj.writeField(this, "value", next)
        next
    }
}
```

Notes:

- Binding must happen **before** the first instance is created.
- Use [LyngClassBridge] to bind by name/module, or by an already resolved `ObjClass`.
- Use `ObjInstance.data` / `ObjClass.classData` to attach Kotlin‑side state when needed.

### 6.5a) Bind Kotlin implementations to declared Lyng objects

For `extern object` declarations, bind implementations to the singleton instance using `ModuleScope.bindObject`.
This mirrors class binding but targets an already created object instance.
As with class binding, you must first add/evaluate the Lyng declaration into that module scope, then bind Kotlin handlers.

```kotlin
// Kotlin side (binding)
val moduleScope = importManager.createModuleScope(Pos.builtIn, "bridge.obj")

// 1) Seed the module with the Lyng declaration first
moduleScope.eval("""
    extern object HostObject {
        extern fun add(a: Int, b: Int): Int
        extern val status: String
        extern var count: Int
    }
""".trimIndent())

// 2) Then bind Kotlin implementations to that declared object
moduleScope.bindObject("HostObject") {
    classData = "OK"
    init { _ -> data = 0L }
    addFun("add") {
        val a = args.requiredArg<ObjInt>(0).value
        val b = args.requiredArg<ObjInt>(1).value
        ObjInt.of(a + b)
    }
    addVal("status") { ObjString(classData as String) }
    addVar(
        "count",
        get = { ObjInt.of((thisObj as ObjInstance).data as Long) },
        set = { value -> (thisObj as ObjInstance).data = (value as ObjInt).value }
    )
}
```

Notes:

- Required order: declare/eval Lyng object in the module first, then call `bindObject(...)`.
  This is the pattern covered by `BridgeBindingTest.testExternObjectBinding`.
- Members must be extern (explicitly, or implicitly via `extern object`) so the compiler emits ABI slots for Kotlin bindings.
- You can also bind by name/module via `LyngObjectBridge.bind(...)`.

Minimal `extern fun` example:

```kotlin
val moduleScope = importManager.createModuleScope(Pos.builtIn, "bridge.ping")

moduleScope.eval("""
    extern object HostObject {
        extern fun ping(): Int
    }
""".trimIndent())

moduleScope.bindObject("HostObject") {
    addFun("ping") { ObjInt.of(7) }
}
```

### 6.6) Preferred: Kotlin reflection bridge for call‑by‑name

For Kotlin code that needs dynamic access to Lyng variables, functions, or members, use the bridge resolver.
It provides explicit, cached handles and predictable lookup rules.

```kotlin
val session = EvalSession()
val scope = session.getScope()
session.eval("""
    val x = 40
    fun add(a, b) = a + b
    class Box { var value = 1 }
""")

val resolver = scope.resolver()

// Read a top‑level value
val x = resolver.resolveVal("x").get(scope)

// Call a function by name (cached inside the resolver)
val sum = (resolver as BridgeCallByName).callByName(scope, "add", Arguments(ObjInt(1), ObjInt(2)))

// Member access
val box = session.eval("Box()")
val valueHandle = resolver.resolveMemberVar(box, "value")
valueHandle.set(scope, ObjInt(10))
val value = valueHandle.get(scope)
```

### 7) Read variable values back in Kotlin

The simplest approach: evaluate an expression that yields the value and convert it.

```kotlin
val session = EvalSession()
val scope = session.getScope()
val kotlinAnswer = session.eval("(1 + 2) * 3").toKotlin(scope) // -> 9 (Int)

// After scripts manipulate your vars:
scope.addOrUpdateItem("name", ObjString("Lyng"))
session.eval("name = name + ' rocks!'")
val kotlinName = session.eval("name").toKotlin(scope) // -> "Lyng rocks!"
```

Advanced: you can also grab a variable record directly via `scope.get(name)` and work with its `Obj` value, but evaluating `"name"` is often clearer and enforces Lyng semantics consistently.

### 8) Execute scripts with parameters; call Lyng functions from Kotlin

There are two convenient patterns.

1) Evaluate a Lyng call expression directly:

```kotlin
// Suppose Lyng defines: fun add(a, b) = a + b
val session = EvalSession()
val scope = session.getScope()
session.eval("fun add(a, b) = a + b")

val sum = session.eval("add(20, 22)").toKotlin(scope) // -> 42
```

2) Call a Lyng function by name via a prepared call scope:

```kotlin
// Ensure the function exists in the scope
val session = EvalSession()
val scope = session.getScope()
session.eval("fun add(a, b) = a + b")

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

### 9) Create your own packages and import them in Lyng

Lyng supports packages that are imported from scripts. You can register packages programmatically via `ImportManager` or by providing source texts that declare `package ...`.

Key concepts:

- `ImportManager` holds package registrations and lazily builds `ModuleScope`s when first imported.
- Every `Scope` has `currentImportProvider` and (if it’s an `ImportManager`) a convenience `importManager` to register packages.

Register a Kotlin‑built package:

```kotlin
import net.sergeych.lyng.bridge.*
import net.sergeych.lyng.obj.ObjInt

val session = EvalSession()
val scope = session.getScope()

// Access the import manager behind this scope
val im: ImportManager = scope.importManager

// Register a package "my.tools"
im.addPackage("my.tools") { module: ModuleScope ->
    module.eval(
        """
        extern val version: String
        extern var status: String
        extern fun triple(x: Int): Int
        """.trimIndent()
    )
    val binder = module.globalBinder()
    var status = "ready"
    binder.bindGlobalVar(
        name = "version",
        get = { "1.0" }
    )
    binder.bindGlobalVar(
        name = "status",
        get = { status },
        set = { status = it }
    )
    binder.bindGlobalFun1<Int>("triple") { x ->
        ObjInt.of((x * 3).toLong())
    }
}

// Use it from Lyng
session.eval("""
    import my.tools.*
    val v = triple(14)
    status = "busy"
""")
val v = session.eval("v").toKotlin(scope) // -> 42
```

Register a package from Lyng source text:

```kotlin
val pkgText = """
    package math.extra

    fun sqr(x) = x * x
""".trimIndent()

scope.importManager.addTextPackages(pkgText)

session.eval("""
    import math.extra.*
    val s = sqr(12)
""")
val s = session.eval("s").toKotlin(scope) // -> 144
```

You can also register from parsed `Source` instances via `addSourcePackages(source)`.

### 10) Executing from files, security, and isolation

- To run code from a file, read it and pass to `session.eval(text)` or compile with `Compiler.compile(Source(fileName, text))`.
- `ImportManager` takes an optional `SecurityManager` if you need to restrict what packages or operations are available. By default, `Script.defaultImportManager` allows everything suitable for embedded use; clamp it down in sandboxed environments.
- For isolation, prefer a fresh `EvalSession()` per request. Use `Scope.new()` / `Script.newScope()` when you specifically need low-level raw scopes or modules.

```kotlin
// Preferred per-request runtime:
val isolatedSession = EvalSession()

// Low-level fresh module based on the default manager, without the standard prelude:
val isolatedScope = net.sergeych.lyng.Scope.new()
```

### 11) Tips and troubleshooting

- All values that cross the boundary must be Lyng `Obj` instances. Convert Kotlin values explicitly (e.g., `ObjInt`, `ObjReal`, `ObjString`).
- Use `toKotlin(scope)` to get Kotlin values back. Collections convert to Kotlin collections recursively.
- Most public API in Lyng is suspendable. If you are not already in a coroutine, wrap calls in `runBlocking { ... }` on the JVM for quick tests.
- When registering packages, names must be unique. Register before you compile/evaluate scripts that import them.
- To debug scope content, `scope.toString()` and `scope.trace()` can help during development.

### 12) Handling and serializing exceptions

When Lyng code throws an exception, it is caught in Kotlin as an `ExecutionError`. This error wraps the actual Lyng `Obj` that was thrown (which could be a built-in `ObjException` or a user-defined `ObjInstance`).

To simplify handling these objects from Kotlin, several extension methods are provided on the `Obj` class. These methods work uniformly regardless of whether the exception is built-in or user-defined.

#### Uniform Exception API

| Method | Description |
| :--- | :--- |
| `obj.isLyngException()` | Returns `true` if the object is an instance of `Exception`. |
| `obj.isInstanceOf("ClassName")` | Returns `true` if the object is an instance of the named Lyng class or its ancestors. |
| `obj.getLyngExceptionMessage(scope?=null)` | Returns the exception message as a Kotlin `String`. |
| `obj.getLyngExceptionMessageWithStackTrace(scope?=null)` | Returns a detailed message with a formatted stack trace. |
| `obj.getLyngExceptionString(scope)` | Returns a formatted string including the class name, message, and primary throw site. |
| `obj.getLyngExceptionStackTrace(scope)` | Returns the stack trace as an `ObjList` of `StackTraceEntry`. |
| `obj.getLyngExceptionExtraData(scope)` | Returns the extra data associated with the exception. |
| `obj.raiseAsExecutionError(scope?=null)` | Rethrows the object as a Kotlin `ExecutionError`. |

#### Example: Serialization and Rethrowing

You can serialize Lyng exception objects using `Lynon` to transmit them across boundaries and then rethrow them.

```kotlin
val session = EvalSession()
val scope = session.getScope()

try {
    session.eval("throw MyUserException(404, \"Not Found\")")
} catch (e: ExecutionError) {
    // 1. Serialize the Lyng exception object
    val encoded: UByteArray = lynonEncodeAny(scope, e.errorObject)

    // ... (transmit 'encoded' byte array) ...

    // 2. Deserialize it back to an Obj in a different context
    val decoded: Obj = lynonDecodeAny(scope, encoded)

    // 3. Properly rethrow it on the Kotlin side using the uniform API
    decoded.raiseAsExecutionError(scope)
}
```

---

That’s it. You now have Lyng embedded in your Kotlin app: you can expose your app’s API, evaluate user scripts, and organize your own packages to import from Lyng code.
