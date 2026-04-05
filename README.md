# Lyng: ideal scripting for kotlin multiplatform

__Please visit the project homepage: [https://lynglang.com](https://lynglang.com) and a [telegram channel](https://t.me/lynglang).__

__Main development site:__ [https://gitea.sergeych.net/SergeychWorks/lyng](https://gitea.sergeych.net/SergeychWorks/lyng)
__github mirror__: [https://github.com/sergeych/lyng](https://github.com/sergeych/lyng)

We keep github as a mirror and backup for the project, while the main development site is hosted on gitea.sergeych.net. We use gitea for issues and pull requests, and as a main point of trust, as github access now is a thing that can momentarily be revoked for no apparent reason.

We encourage using the github if the main site is not accessible from your country and vice versa. We recommend to `publishToMavenLocal` and not depend on politics.


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

class A {
    class B(x?)
    object Inner { val foo = "bar" }
    enum E* { One, Two }
}
val ab = A.B()
assertEquals(null, ab.x)
assertEquals("bar", A.Inner.foo)
assertEquals(A.E.One, A.One)
```

- extremely simple Kotlin integration on any platform (JVM, JS, WasmJS, Lunux, MacOS, iOS, Windows)
- 100% secure: no access to any API you didn't explicitly provide
- 100% coroutines! Every function/script is a coroutine, it does not block the thread, no async/await/suspend keyword garbage, see [parallelism]. it is multithreaded on platforms supporting it (automatically, no code changes required, just `launch` more coroutines and they will be executed concurrently if possible). See [parallelism]
- functional style and OOP together: multiple inheritance (so you got it all - mixins, interfaces, etc.), delegation, sigletons, anonymous classes,extensions.
- nice literals for maps and arrays, destructuring assignment, ranges.
- Any Unicode letters can be used as identifiers: `assert( sin(π/2) == 1 )`.

 ## Resources: 

- [Language home](https://lynglang.com)
- [introduction and tutorial](docs/tutorial.md) - start here please
- [Latest release notes (1.5.4)](docs/whats_new.md)
- [What's New in 1.5](docs/whats_new_1_5.md)
- [Testing and Assertions](docs/Testing.md)
- [Filesystem and Processes (lyngio)](docs/lyngio.md)
- [Return Statement](docs/return_statement.md)
- [Efficient Iterables in Kotlin Interop](docs/EfficientIterables.md)
- [Samples directory](docs/samples)
- [Formatter (core + CLI + IDE)](docs/formatter.md)
- [Books directory](docs)
- [AI agent guidance](AGENTS.md)

## Integration in Kotlin multiplatform

### Add dependency to your project

```kotlin
val lyngVersion = "1.5.4"

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
import net.sergeych.lyng.*

// we need a coroutine to start, as Lyng
// is a coroutine based language, async topdown
runBlocking {
    val session = EvalSession()
    assert(5 == session.eval(""" 3*3 - 4 """).toInt())
    session.eval(""" println("Hello, Lyng!") """)
}
```

### Exchanging information

The preferred host runtime is `EvalSession`. It owns the script scope and any coroutines
started with `launch { ... }`. Create a session, grab its scope when you need low-level
binding APIs, then execute scripts through the session:

```kotlin
import net.sergeych.lyng.*

runBlocking {
    val session = EvalSession()
    val scope = session.getScope().apply {
        // simple function
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

    // execute through the session:
    session.eval("sumOf(1,2,3)") // <- 6
}
```
Note that the session reuses one scope, so state persists across `session.eval(...)` calls.
Use raw `Scope.eval(...)` only when you intentionally want low-level control without session-owned coroutine lifecycle.

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

## AI Assistant Support

To help AI assistants (like Cursor, Windsurf, or GitHub Copilot) understand Lyng with minimal effort, we provide a high-density language specification:

- **[LYNG_AI_SPEC.md](LYNG_AI_SPEC.md)**: A concise guide for AI models to learn Lyng syntax, idioms, and core philosophy. We recommend pointing your AI tool to this file or including it in your project's custom instructions.

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

The current stable release is **v1.5.4**: the 1.5 cycle is feature-complete, compiler/runtime stabilization work is in, and the language, tooling, and site are aligned around the current release.

Ready features:

- [x] Language platform and independent command-line launcher
- [x] Integral types and user classes, variables and constants, functions
- [x] lambdas and closures, coroutines for all callables
- [x] while-else, do-while-else, for-else loops with break-continue returning values and labels support
- [x] ranges, lists, strings, interfaces: Iterable, Iterator, Collection, Array
- [x] when(value), if-then-else
- [x] exception handling: throw, try-catch-finally, exception classes.
- [x] user-defined exception classes
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
- [x] `return` statement for local and non-local exit
- [x] Unified Delegation model: val, var and fun
- [x] `lazy val` using delegation
- [x] singletons `object TheOnly { ... }`
- [x] object expressions `object: List { ... }`
- [x] late-init vals in classes
- [x] properties with getters and setters
- [x] assign-if-null operator `?=`
- [x] user-defined exception classes

All of this is documented on the [language site](https://lynglang.com) and locally in [docs/tutorial.md](docs/tutorial.md). The site reflects the current release, while development snapshots continue in the private Maven repository.  

## plan: towards v2.0 Next Generation

- [x] site with integrated interpreter to give a try
- [x] kotlin part public API good docs, integration focused
- [x] type specifications
- [x] Textmate Bundle
- [x] IDEA plugin
- [x] source docs and maybe lyng.md to a standard
- [x] aggressive optimizations

## After 1.5 "Ideal scripting"

* __we are here now ;)__

- propose your feature! 

## Authors

@-links are for contacting authors on [project home](https://gitea.sergeych.net/SergeychWorks/lyng): this simplest s to open issue for the person you need to convey any information about this project.

<img src="https://www.gravatar.com/avatar/7e3a56ff8a090fc9ffbd1909dea94904?s=32&d=identicon" alt="Sergey Chernov" width="32" height="32" style="vertical-align: middle; margin-right: 0.5em;" /> <b>Sergey Chernov</b> @sergeych, real.sergeych@gmail.com: Initial idea and architecture, language concept, design, implementation.

<br/>

<img src="https://www.gravatar.com/avatar/53a90bca30c85a81db8f0c0d8dea43a1?s=32&d=identicon" alt="Yulia Nezhinskaya" width="32" height="32" style="vertical-align: middle; margin-right: 0.5em;" /> <b>Yulia Nezhinskaya</b> @AlterEgoJuliaN, neleka88@gmail.com: System analysis, math and feature design.



[parallelism]: docs/parallelism.md
