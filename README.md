# Ling: scripting lang for kotlin multiplatform

in the form of multiplatform library.

__current state of implementation and docs__: [docs/math.md].

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