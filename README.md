# Lyng: scripting lang for kotlin multiplatform

in the form of multiplatform library.

__current state of implementation and docs__: 

- [introduction and tutorial](docs/tutorial.md)
- [math and operators](docs/math.md).

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
val context = Context().apply {
    addFn("addArgs") {
        var sum = 0.0
        for( a in args) sum += a.toDouble()
        ObjReal(sum)
    }
    addConst("LIGHT_SPEED", ObjReal(299_792_458.0))
}

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