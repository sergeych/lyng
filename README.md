# Lyng: modern scripting for kotlin multiplatform

A KMP library and a standalone interpreter

- simple, compact, intuitive and elegant modern code style:

```
class Point(x,y) {
   fun dist() { sqrt(x*x + y*y) } 
}
Point(3,4).dist() //< 5
```

- extremely simple Kotlin integration on any platform
- 100% secure: no access to any API you didn't explicitly provide
- 100% coroutines! Every function/script is a coroutine, it does not block the thread, no async/await/suspend keyword garbage:

```
    delay(1.5) // coroutine is delayed for 1.5s, thread is not blocked!
```
and it is multithreaded on platforms supporting it (automatically, no code changes required, just
`launch` more coroutines and they will be executed concurrently if possible)/

- functional style and OOP together, multiple inheritance, implementing interfaces for existing classes, writing extensions.
- Any unicode letters can be used as identifiers: `assert( sin(π/2) == 1 )`.

 ## Resources: 

- [introduction and tutorial](docs/tutorial.md) - start here please
- [Samples directory](docs/samples)

## Integration in Kotlin multiplatform

### Add library

TBD

### Execute script:

```kotlin
assertEquals("hello, world", eval(""" 
    "hello, " + "world" 
    """).toString())
```

### Exchanging information

Script is executed over some `Context`. Create instance of the context,
add your specific vars and functions to it, an call over it:

```kotlin
// simple function
val context = Context().apply {
    addFn("addArgs") {
        var sum = 0.0
        for( a in args) sum += a.toDouble()
        ObjReal(sum)
    }
    addConst("LIGHT_SPEED", ObjReal(299_792_458.0))
    
    // callback back to kotlin to some suspend fn, for example::
    // suspend fun doSomeWork(text: String): Int
    addFn("doSomeWork") {
        // this _is_ a suspend lambda, we can call suspend function,
        // and it won't consume the thread:
        doSomeWork(args[0].toString()).toObj()
    }
}
// adding constant:
context.eval("addArgs(1,2,3)") // <- 6
```
Note that the context stores all changes in it so you can make calls on a single context to preserve state between calls.

## Why? 

Designed to add scripting to kotlin multiplatform application in easy and efficient way. This is attempt to achieve what Lua is for C/++.

- fast start (times and times faster than initializing v8/wasm)
- fast and simple kotlin interoperability
- coroutine-based, truly async. On platforms with multithreading, run multithreaded. No python/ruby/javascript threads hell.
- small footprint
- absolutely safe: no access to any dangerous or sensitive functions until you specifically provide it.

# Language 

- dynamic 
- async
- multithreaded (coroutines could be dispatched using threads on appropriate platforms, automatically)

## By-stage

Here are plans to develop it:

### First stage

Interpreted, precompiled into threaded code, actually. Dynamic types.

### Second stage

Will add:

- optimizations
- p-code serialization
- static typing