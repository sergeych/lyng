# Embedding Lyng in your Kotlin project

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

### 3) Preferred: bind extern globals from Kotlin

For module-level APIs, the default workflow is:

1. declare globals in Lyng using `extern fun` / `extern val` / `extern var`;
2. bind Kotlin implementation via `ModuleScope.globalBinder()`.

```kotlin
import net.sergeych.lyng.bridge.*
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjString

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
scope.eval("val y = inc(41); log('Answer:', y)")
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

### 5) Add Kotlin‑backed fields

If you need a simple field (with a value) instead of a computed property, use `createField`. This adds a field to the class that will be present in all its instances.

```kotlin
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

```lyng
// Lyng side (in a module)
class Counter {
    extern var value: Int
    extern fun inc(by: Int): Int
}
```

Note: members must be marked `extern` so the compiler emits the ABI slots that Kotlin bindings attach to. This applies to functions and properties bound via `addFun` / `addVal` / `addVar`.

```kotlin
// Kotlin side (binding)
val moduleScope = Script.newScope() // or an existing module scope
moduleScope.eval("class Counter { extern var value: Int; extern fun inc(by: Int): Int }")

moduleScope.bind("Counter") {
    addVar(
        name = "value",
        get = { _, self -> self.readField(this, "value").value },
        set = { _, self, v -> self.writeField(this, "value", v) }
    )
    addFun("inc") { _, self, args ->
        val by = args.requiredArg<ObjInt>(0).value
        val current = self.readField(this, "value").value as ObjInt
        val next = ObjInt(current.value + by)
        self.writeField(this, "value", next)
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
    addFun("add") { _, _, args ->
        val a = args.requiredArg<ObjInt>(0).value
        val b = args.requiredArg<ObjInt>(1).value
        ObjInt.of(a + b)
    }
    addVal("status") { _, _ -> ObjString(classData as String) }
    addVar(
        "count",
        get = { _, inst -> ObjInt.of((inst as ObjInstance).data as Long) },
        set = { _, inst, value -> (inst as ObjInstance).data = (value as ObjInt).value }
    )
}
```

Notes:

- Required order: declare/eval Lyng object in the module first, then call `bindObject(...)`.
  This is the pattern covered by `BridgeBindingTest.testExternObjectBinding`.
- Members must be marked `extern` so the compiler emits ABI slots for Kotlin bindings.
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
    addFun("ping") { _, _, _ -> ObjInt.of(7) }
}
```

### 6.6) Preferred: Kotlin reflection bridge for call‑by‑name

For Kotlin code that needs dynamic access to Lyng variables, functions, or members, use the bridge resolver.
It provides explicit, cached handles and predictable lookup rules.

```kotlin
val scope = Script.newScope()
scope.eval("""
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
val box = scope.eval("Box()")
val valueHandle = resolver.resolveMemberVar(box, "value")
valueHandle.set(scope, ObjInt(10))
val value = valueHandle.get(scope)
```

### 7) Read variable values back in Kotlin

The simplest approach: evaluate an expression that yields the value and convert it.

```kotlin
val kotlinAnswer = scope.eval("(1 + 2) * 3").toKotlin(scope) // -> 9 (Int)

// After scripts manipulate your vars:
scope.addOrUpdateItem("name", ObjString("Lyng"))
scope.eval("name = name + ' rocks!'")
val kotlinName = scope.eval("name").toKotlin(scope) // -> "Lyng rocks!"
```

Advanced: you can also grab a variable record directly via `scope.get(name)` and work with its `Obj` value, but evaluating `"name"` is often clearer and enforces Lyng semantics consistently.

### 8) Execute scripts with parameters; call Lyng functions from Kotlin

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

### 9) Create your own packages and import them in Lyng

Lyng supports packages that are imported from scripts. You can register packages programmatically via `ImportManager` or by providing source texts that declare `package ...`.

Key concepts:

- `ImportManager` holds package registrations and lazily builds `ModuleScope`s when first imported.
- Every `Scope` has `currentImportProvider` and (if it’s an `ImportManager`) a convenience `importManager` to register packages.

Register a Kotlin‑built package:

```kotlin
import net.sergeych.lyng.bridge.*
import net.sergeych.lyng.obj.ObjInt

val scope = Script.newScope()

// Access the import manager behind this scope
val im: ImportManager = scope.importManager

// Register a package "my.tools"
im.addPackage("my.tools") { module: ModuleScope ->
    module.eval(
        """
        extern val version: String
        extern fun triple(x: Int): Int
        """.trimIndent()
    )
    val binder = module.globalBinder()
    binder.bindGlobalVar(
        name = "version",
        get = { "1.0" }
    )
    binder.bindGlobalFun1<Int>("triple") { x ->
        ObjInt.of((x * 3).toLong())
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

### 10) Executing from files, security, and isolation

- To run code from a file, read it and pass to `scope.eval(text)` or compile with `Compiler.compile(Source(fileName, text))`.
- `ImportManager` takes an optional `SecurityManager` if you need to restrict what packages or operations are available. By default, `Script.defaultImportManager` allows everything suitable for embedded use; clamp it down in sandboxed environments.
- For isolation, create fresh modules/scopes via `Scope.new()` or `Script.newScope()` when you need a clean environment per request.

```kotlin
// Fresh module based on the default manager, without the standard prelude
val isolated = net.sergeych.lyng.Scope.new()
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
try {
    scope.eval("throw MyUserException(404, \"Not Found\")")
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
