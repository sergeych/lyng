# Advanced topics

__See also:__ [parallelism].

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

    // Swap first and last arguments for call

    fun swap_args(first, others..., last, f) { 
        f(last, ...others, first) 
    }

    fun glue(args...) {
        var result = ""
        for( a in args ) result += a
    }

    assertEquals( 
        "4231", 
        swap_args( 1, 2, 3, 4, glue)
    )
    >>> void

# Annotations

Annotation in Lyng resembles these proposed for Javascript. Annotation is just regular functions that, if used as annotation, are called when defining a function, var, val or class. 

## Function annotation

When used without params, annotation calls a function with two arguments: actual function name and callable function body. Function annotation __must return callable for the function__, either what it received as a second argument (most often), or something else. Annotation name convention is upper scaled: 

    var annotated = false
    
    // this is annotation function:
    fun Special(name, body) {
        assertEquals("foo", name)
        annotated = true
        { body(it) + 100 }
    }

    @Special
    fun foo(value) { value + 1 }

    assert(annotated)
    assertEquals(111, foo( 10 ))
    >>> void

Function annotation can have more args specified at call time. There arguments must follow two mandatory ones (name and body). Use default values in order to allow parameterless annotation to be used simultaneously.

    val registered = Map()

    // it is recommended to provide defaults for extra parameters:
    fun Registered(name, body, overrideName = null) {
        registered[ overrideName ?: name ] = body
        body
    }

    // witout parameters is Ok as we provided default value
    @Registered
    fun foo() { "called foo" }

    @Registered("bar") 
    fun foo2() { "called foo2" }

    assertEquals(registered["foo"](), "called foo")
    assertEquals(registered["bar"](), "called foo2")
    >>> void

[parallelism]: parallelism.md

## Scopes and Closures: resolution and safety

Closures and dynamic scope graphs require care to avoid accidental recursion and to keep name resolution predictable. See the dedicated page for detailed rules, helper APIs, and best practices:

- Scopes and Closures: resolution and safety → [scopes_and_closures.md](scopes_and_closures.md)
