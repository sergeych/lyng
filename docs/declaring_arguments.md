# Declaring arguments in Lyng

It is a common thing that occurs in many places in Lyng, function declarations, 
lambdas and class declarations.

## Regular

## default values

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
    

[tutorial]: tutorial.md
