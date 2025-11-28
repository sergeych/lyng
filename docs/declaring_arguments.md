# Declaring arguments in Lyng

[//]: # (topMenu)

It is a common thing that occurs in many places in Lyng, function declarations, 
lambdas and class declarations.

## Regular

## Default values

Default parameters should not be mixed with mandatory ones:

    // ok:
    fun validFun(a, b, c=0, d=1) {}

    // this is a compilration error
    fun invalidFun(a, b=1, c) {} // throw error

Valid examples:

    fun foo(bar, baz="buz", end= -1) {
        println(bar + ' ' + baz + ' ' + end)
    }
    foo("bar")
    foo("nobar", "buzz")
    foo("nobar", "buzz", 120)
    >>> bar buz -1
    >>> nobar buzz -1
    >>> nobar buzz 120
    >>> void

# Ellipsis

Ellipsis are used to declare variadic arguments. It basically means "all the arguments available here". It means, ellipsis argument could be in any part of the list, being, end or middle, but there could be only one ellipsis argument and it must not have default value, its default value is always `[]`, en empty list.

Ellipsis argument receives what is left from arguments after processing regular one that could be before or after.

Ellipsis could be a first argument:

    fun testCountArgs(data...,size) {
        assert(size is Int)
        assertEquals(size, data.size)
    }
    testCountArgs( 1, 2, "three", 3)
    >>> void

Ellipsis could also be a last one:

    fun testCountArgs(size, data...) {
        assert(size is Int)
        assertEquals(size, data.size)
    }
    testCountArgs( 3, 10, 2, "three")
    >>> void

Or in the middle:

    fun testCountArgs(size, data..., textToReturn) {
        assert(size is Int)
        assertEquals(size, data.size)
        textToReturn
    }
    testCountArgs( 3, 10, 2, "three", "All OK")
    >>> "All OK"

## Destructuring with splats

When combined with splat arguments discussed in the [tutorial] it could be used to effectively
destructuring arrays when calling functions and lambdas:

    fun getFirstAndLast(first, args..., last) {
        [ first, last ]
    }
    getFirstAndLast( ...(1..10) ) // see "splats" section below
    >>> [1,10]

# Splats

Ellipsis allows to convert argument lists to lists. The inversa algorithm that converts [List],
or whatever implementing [Iterable], is called _splats_. Here is how we use it:

    fun testSplat(data...) {
        println(data)
    }
    val array = [1,2,3]
    testSplat("start", ...array, "end")
    >>> [start,1,2,3,end]
    >>> void

There could be any number of splats at any positions. You can splat any other [Iterable] type:

    fun testSplat(data...) {
        println(data)
    }
    val range = 1..3
    testSplat("start", ...range, "end")
    >>> [start,1,2,3,end]
    >>> void

## Named arguments in calls

Lyng supports named arguments at call sites using colon syntax `name: value`:

```lyng
    fun test(a="foo", b="bar", c="bazz") { [a, b, c] }

    assertEquals(["foo", "b", "bazz"], test(b: "b"))
    assertEquals(["a", "bar", "c"], test("a", c: "c"))
```

Rules:

- Named arguments must follow positional arguments. After the first named argument, no positional arguments may appear inside the parentheses.
- The only exception is the syntactic trailing block after the call: `f(args) { ... }`. This block is outside the parentheses and is handled specially (see below).
- A named argument cannot reassign a parameter already set positionally.
- If the last parameter has already been assigned by a named argument (or named splat), a trailing block is not allowed and results in an error.

Why `:` and not `=` at call sites? In Lyng, `=` is an expression (assignment), so we use `:` to avoid ambiguity. Declarations continue to use `:` for types, while call sites use `as` / `as?` for type operations.

## Named splats (map splats)

Splat (`...`) of a Map provides named arguments to the call. Only string keys are allowed:

```lyng
    fun test(a="a", b="b", c="c", d="d") { [a, b, c, d] }
    val r = test("A?", ...Map("d" => "D!", "b" => "B!"))
    assertEquals(["A?","B!","c","D!"], r)
```

The same with a map literal is often more concise. Define the literal, then splat the variable:

    fun test(a="a", b="b", c="c", d="d") { [a, b, c, d] }
    val patch = { d: "D!", b: "B!" }
    val r = test("A?", ...patch)
    assertEquals(["A?","B!","c","D!"], r)
    >>> void

Constraints:

- Map splat keys must be strings; otherwise, a clean error is thrown.
- Named splats cannot duplicate parameters already assigned (by positional or named arguments).
- Named splats must follow all positional arguments and positional splats.
- Ellipsis parameters (variadic) remain positional-only and cannot be assigned by name.

## Trailing-lambda rule interaction

If a call is immediately followed by a block `{ ... }`, it is treated as an extra last argument and bound to the last parameter. However, if the last parameter is already assigned by a named argument or a named splat, using a trailing block is an error:

```lyng
    fun f(x, onDone) { onDone(x) }
    f(x: 1) { 42 }   // ERROR
    f(1) { 42 }      // OK
```
    

[tutorial]: tutorial.md
