# Advanced topics

## Closures/scopes isolation

Each block has own scope, in which it can safely uses closures and override
outer vars:

> blocks are no-yet-ready lambda declaration so this sample will soon be altered

    var param = "global"
    val prefix = "param in "
    val scope1 = {
        var param = prefix + "scope1"
        param
    }
    val scope2 = {
        var param = prefix + "scope2"
        param
    }
    // note that block returns its last value
    println(scope1)
    println(scope2)
    println(param)
    >>> param in scope1
    >>> param in scope2
    >>> global
    >>> void

One interesting way of using closure isolation is to keep state of the functions:

    val getAndIncrement = {
        // will be updated by doIt()
        var counter = 0

        // we return callable fn from the block:
        fun doit() {
            val was = counter
            counter = counter + 1
            was
        }
    }
    println(getAndIncrement())
    println(getAndIncrement())
    println(getAndIncrement())
    >>> 0
    >>> 1
    >>> 2
    >>> void

Inner `counter` is not accessible from outside, no way; still it is kept 
between calls in the closure, as inner function `doit`, returned from the
block, keeps reference to it and keeps it alive.