# Lyng: modern scripting for kotlin multiplatform

Please visit the project homepage: [https://lynglang.com](https://lynglang.com) and a [telegram channel](https://t.me/lynglang) for updates.

- simple, compact, intuitive and elegant modern code:

```lyng
class Point(x, y) {
   fun dist() { sqrt(x*x + y*y) } 
}

// Auto-named arguments shorthand (x: is x: x):
val x = 3
val y = 4
Point(x:, y:).dist() //< 5

fun swapEnds(first, args..., last, f) {
    f( last, ...args, first)
} 
```

- extremely simple Kotlin integration on any platform (JVM, JS, WasmJS, Lunux, MacOS, iOS, Windows)
- 100% secure: no access to any API you didn't explicitly provide
- 100% coroutines! Every function/script is a coroutine, it does not block the thread, no async/await/suspend keyword garbage, see [parallelism]

```
  val deferred = launch {
    delay(1.5) // coroutine is delayed for 1.5s, thread is not blocked!
    "done"
  }
  // ...
  // suspend current coroutine, no thread is blocked again,
  // and wait for deferred to return something:
  assertEquals("donw", deferred.await())
```
and it is multithreaded on platforms supporting it (automatically, no code changes required, just
`launch` more coroutines and they will be executed concurrently if possible). See [parallelism]

- functional style and OOP together, multiple inheritance, implementing interfaces for existing classes, writing extensions.
- Any Unicode letters can be used as identifiers: `assert( sin(π/2) == 1 )`.

 ## Resources: 

- [Language home](https://lynglang.com)
- [introduction and tutorial](docs/tutorial.md) - start here please
- [Testing and Assertions](docs/Testing.md)
- [Efficient Iterables in Kotlin Interop](docs/EfficientIterables.md)
- [Samples directory](docs/samples)
- [Formatter (core + CLI + IDE)](docs/formatter.md)
- [Books directory](docs)

## Integration in Kotlin multiplatform

### Add dependency to your project

```kotlin
// update to current please:
val lyngVersion = "0.6.1-SNAPSHOT"

repositories {
    // ...
    maven("https://gitea.sergeych.net/api/packages/SergeychWorks/maven")
}
```

And add dependency to the proper place in your project, it could look like:

```kotlin
comminMain by getting {
    dependencies {
        // ...
        implementation("net.sergeych:lynglib:$lyngVersion")
    }
}
```

Now you can import lyng and use it:

### Execute script:

```kotlin
import net.sergeyh.lyng.*

// we need a coroutine to start, as Lyng
// is a coroutine based language, async topdown
runBlocking {
    assert(5 == eval(""" 3*3 - 4 """).toInt())
    eval(""" println("Hello, Lyng!") """)
}
```

### Exchanging information

Script is executed over some `Scope`. Create instance,
add your specific vars and functions to it, and call:

```kotlin

import com.sun.source.tree.Scope
import new.sergeych.lyng.*

// simple function
val scope = Script.newScope().apply {
    addFn("sumOf") {
        var sum = 0.0
        for (a in args) sum += a.toDouble()
        ObjReal(sum)
    }
    addConst("LIGHT_SPEED", ObjReal(299_792_458.0))

    // callback back to kotlin to some suspend fn, for example::
    // suspend fun doSomeWork(text: String): Int
    addFn("doSomeWork") {
        // this _is_ a suspend lambda, we can call suspend function,
        // and it won't consume the thread.
        // note that in kotlin handler, `args` is a list of `Obj` arguments
        // and return value from this lambda should be Obj too:
        doSomeWork(args[0]).toObj()
    }
}
// adding constant:
scope.eval("sumOf(1,2,3)") // <- 6
```
Note that the scope stores all changes in it so you can make calls on a single scope to preserve state between calls.

## IntelliJ IDEA plugin: Lightweight autocompletion (experimental)

The IDEA plugin provides a fast, lightweight BASIC completion for Lyng code (IntelliJ IDEA 2024.3+).

What it does:
- Global suggestions: in-scope parameters, same-file declarations (functions/classes/vals), imported modules, and stdlib symbols.
- Member completion after dot: offers only members of the inferred receiver type. It works for chained calls like `Path(".." ).lines().` (suggests `Iterator` methods), and for literals like `"abc".` (String methods) or `[1,2,3].` (List/Iterable methods).
- Inheritance-aware: shows direct class members first, then inherited. For example, `List` also exposes common `Collection`/`Iterable` methods.
- Static/namespace members: `Name.` lists only static members when `Name` is a known class or container (e.g., `Math`).
- Performance: suggestions are capped; prefix filtering is early; parsing is cached; computation is cancellation-friendly.

What it does NOT do (yet):
- No heavy resolve or project-wide indexing. It’s best-effort, driven by a tiny MiniAst + built-in docs registry.
- No control/data-flow type inference.

Enable/disable:
- Settings | Lyng Formatter → "Enable Lyng autocompletion (experimental)" (default: ON).

Tips:
- After a dot, globals are intentionally suppressed (e.g., `lines().Path` is not valid), only the receiver’s members are suggested.
- If completion seems sparse, make sure related modules are imported (e.g., `import lyng.io.fs` so that `Path` and its methods are known).

## Why? 

Designed to add scripting to kotlin multiplatform application in easy and efficient way. This is attempt to achieve what Lua is for C/++.

- fast start (times and times faster than initializing v8/wasm)
- fast and simple kotlin interoperability
- coroutine-based, truly async. On platforms with multithreading, run multithreaded. No python/ruby/javascript threads hell.
- small footprint
- absolutely safe: no access to any dangerous or sensitive functions until you specifically provide it.

# Language 

- Javascript, WasmJS, native, JVM, android - batteries included.
- dynamic types in most elegant and concise way
- async, 100% coroutines, supports multiple cores where platform supports thread
- good for functional an object-oriented style

# Language Roadmap

We are now at **v1.0**: basic optimization performed, battery included: standard library is 90% here, initial
support in HTML, popular editors, and IDEA; tools to syntax highlight and format code are ready. It was released closed to schedule.

Ready features:

- [x] Language platform and independent command-line launcher
- [x] Integral types and user classes, variables and constants, functions
- [x] lambdas and closures, coroutines for all callables
- [x] while-else, do-while-else, for-else loops with break-continue returning values and labels support
- [x] ranges, lists, strings, interfaces: Iterable, Iterator, Collection, Array
- [x] when(value), if-then-else
- [x] exception handling: throw, try-catch-finally, exception classes.
- [x] multiplatform maven publication
- [x] documentation for the current state
- [x] maps, sets and sequences (flows?)
- [x] modules
- [x] string formatting and tools
- [x] launch, deferred, CompletableDeferred, Mutex, etc.
- [x] multiline strings
- [x] typesafe bit-effective serialization
- [x] compression/decompression (integrated in serialization)
- [x] dynamic fields
- [x] function annotations
- [x] better stack reporting
- [x] regular exceptions + extended `when`
- [x] multiple inheritance for user classes
- [x] class properties (accessors)

## plan: towards v1.5 Enhancing

- [x] site with integrated interpreter to give a try
- [x] kotlin part public API good docs, integration focused
- [ ] type specifications
- [x] Textmate Bundle
- [x] IDEA plugin
- [ ] source docs and maybe lyng.md to a standard
- [ ] metadata first class access from lyng
- [x] aggressive optimizations
- [ ] compile to JVM bytecode optimization

## After 1.5 "Ideal scripting"

Estimated summer 2026

- propose your feature! 

## Authors

@-links are for contacting authors on [project home](https://gitea.sergeych.net/SergeychWorks/lyng): this simplest s to open issue for the person you need to convey any information about this project.

__Sergey Chernov__ @sergeych: Initial idea and architecture, language concept, design, implementation.

__Yulia Nezhinskaya__ @AlterEgoJuliaN: System analysis, math and features design.

[parallelism]: docs/parallelism.md