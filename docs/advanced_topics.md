# Advanced topics

## Closures/scopes isolation

Each block has own scope, in which it can safely use closures and override
outer vars. Lets use some lambdas to create isolated scopes:

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
    
    println(scope1())
    println(scope2())
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
    }()
    // notice using of () above: it calls the lambda block that returns
    // a function (callable!) that we will use:
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

The example above could be rewritten using inner lambda, too:

    val getAndIncrement = {
        // will be updated by doIt()
        var counter = 0

        // we return callable fn from the block:
        {
            val was = counter
            counter = counter + 1
            was
        }
    }()
    // notice using of () above: it calls the lambda block that returns
    // a function (callable!) that we will use:
    println(getAndIncrement())
    println(getAndIncrement())
    println(getAndIncrement())
    >>> 0
    >>> 1
    >>> 2
    >>> void

Lambda functions remember their scopes, so it will work the same as previous:

    var counter = 200
    fun createLambda() {
        var counter = 0
        { counter += 1 }
    }
    val c = createLambda()
    println(c)
    >> 1
    >> void

# Elements of functional programming

With ellipsis and splats you can create partial functions, manipulate
arguments list in almost arbitrary ways. For example:

    // Swap first and last arguments in the call
    fun swap_args(first, others..., last, f) { f(last, ...others, first) }

    fun glue(args...) {
        var result = ""
        for( a in args ) result += a
    }

    assertEquals( 
        "321", 
        swap_args( 1, 2, 3, glue)
    )
    >>> void

,
