# Lyng: modern scripting for kotlin multiplatform

A KMP library and a standalone interpreter

- simple, compact, intuitive and elegant modern code:

```
class Point(x,y) {
   fun dist() { sqrt(x*x + y*y) } 
}
Point(3,4).dist() //< 5

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

- [introduction and tutorial](docs/tutorial.md) - start here please
- [Samples directory](docs/samples)
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

## v1.0.0

Planned autumn 2025. Complete dynamic language with sufficient standard library:

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

###  Under way: 

- [ ] regular exceptions
- [ ] multiple inheritance for user classes
- [ ] site with integrated interpreter to give a try
- [ ] kotlin part public API good docs, integration focused
- [ ] better stack reporting

## v1.1+

Planned features.

- [ ] type specifications
- [ ] source docs and maybe lyng.md to a standard
- [ ] metadata first class access from lyng

Further

- [ ] client with GUI support based on compose multiplatform somehow
- [ ] notebook - style workbooks with graphs, formulae, etc.
- [ ] language server or compose-based lyng-aware editor

[parallelism]: docs/parallelism.md