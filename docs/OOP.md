# OO implementation in Lyng

## Declaration 

The class clause looks like

    class Point(x,y)
    assert( Point is Class ) 
    >>> void

It creates new `Class` with two fields. Here is the more practical sample:

    class Point(x,y) {
        fun length() { sqrt(x*x + y*y) } 
    }

    val p = Point(3,4)
    assert(p is Point)
    assertEquals(5, p.length())
    
    // we can access the fields:
    assert( p.x == 3 )
    assert( p.y == 4 )
    
    // we can assign new values to fields:
    p.x = 1
    p.y = 1
    assertEquals(sqrt(2), p.length())
    >>> void


Let's see in details. The statement `class Point(x,y)` creates a class,
with two field, which are mutable and publicly visible.`(x,y)` here
is the [argument list], same as when defining a function. All together creates a class with
a _constructor_ that requires two parameters for fields. So when creating it with
`Point(10, 20)` we say _calling Point constructor_ with these parameters.

Form now on `Point` is a class, it's type is `Class`, and we can create instances with it as in the
example above.

Class point has a _method_, or a _member function_ `length()` that uses its _fields_ `x` and `y` to
calculate the magnitude. Length is called

### default values in constructor

Constructor arguments are the same as function arguments except visibility
statements discussed later, there could be default values, ellipsis, etc.

    class Point(x=0,y=0) 
    val p = Point()
    assert( p.x == 0 && p.y == 0 )
    >>> void

## Methods

Functions defined inside a class body are methods, and unless declared
`private` are available to be called from outside the class:

    class Point(x,y) {
        // public method declaration:
        fun length() { sqrt(d2()) }

        // private method:
        private fun d2() {x*x + y*y}
    }
    val p = Point(3,4)
    // private called from inside public: OK
    assertEquals( 5, p.length() )
    // but us not available directly
    assertThrows { p.d2() }
    void
    >>> void

## fields and visibility

It is possible to add non-constructor fields:

    class Point(x,y) {
        fun length() { sqrt(x*x + y*y) } 
    
        // set at construction time:   
        val initialLength = length()
    }
    val p = Point(3,4)
    p.x = 3
    p.y = 0
    assertEquals( 3, p.length() )
    // but initial length could not be changed after as declard val:
    assert( p.initialLength == 5 )
    >>> void

### Mutable fields

Are declared with var

    class Point(x,y) {
        var isSpecial = false
    }
    val p = Point(0,0)
    assert( p.isSpecial == false )

    p.isSpecial = true
    assert( p.isSpecial == true )
    >>> void

### Private fields

Private fields are visible only _inside the class instance_:

    class SecretCounter {
        private var count = 0
        
        fun increment() {
            count++
            void // hide counter
        }
        
        fun isEnough() {
            count > 10
        }
    }
    val c = SecretCounter()
    assert( c.isEnough() == false )
    assert( c.increment() == void )
    for( i in 0..10 ) c.increment()
    assert( c.isEnough() )

    // but the count is not available outside:
    assertThrows { c.count }
    void
    >>> void

It is possible to provide private constructor parameters so they can be
set at construction but not available outside the class:

    class SecretCounter(private var count = 0) {
        // ...
    }
    val c = SecretCounter(10)
    assertThrows { c.count }
    void
    >>> void

## Default class methods

In many cases it is necessary to implement custom comparison and `toString`, still
each class is provided with default implementations:

- default toString outputs class name and its _public_ fields.
- default comparison compares all fields in order of appearance.

For example, for our class Point:

    class Point(x,y)
    assert( Point(1,2) == Point(1,2) )
    assert( Point(1,2) !== Point(1,2) )
    assert( Point(1,2) != Point(1,3) )
    assert( Point(1,2) < Point(2,2) )
    assert( Point(1,2) < Point(1,3) )
    Point(1,1+1)
    >>> Point(x=1,y=2)

## Statics: class fields and class methods

You can mark a field or a method as static. This is borrowed from Java as more plain version of a kotlin's companion object or Scala's object. Static field and functions is one for a class, not for an instance. From inside the class, e.g. from the class method, it is a regular var. From outside, it is accessible as `ClassName.field` or method:


    class Value(x) {
        static var foo = Value("foo")

        static fun exclamation() {
            // here foo is a regular var:
            foo.x + "!"
        }
    }
    assertEquals( Value.foo.x, "foo" )
    assertEquals( "foo!", Value.exclamation() )

    // we can access foo from outside like this:
    Value.foo = Value("bar")
    assertEquals( "bar!", Value.exclamation() )
    >>> void

As usual, private statics are not accessible from the outside:

    class Test {
        // private, inacessible from outside protected data:
        private static var data = null

        // the interface to access and change it:
        static fun getData() { data }
        static fun setData(value) { data = value }
    }

    // no direct access:
    assertThrows { Test.data }

    // accessible with the interface:
    assertEquals( null, Test.getData() )
    Test.setData("fubar")
    assertEquals("fubar", Test.getData() )
    >>> void

# Extending classes

It sometimes happen that the class is missing some particular functionality that can be _added to it_ without rewriting its inner logic and using its private state. In this case _extension methods_ could be used, for example. we want to create an extension method
that would test if some object of unknown type contains something that can be interpreted
as an integer. In this case we _extend_ class `Object`, as it is the parent class for any instance of any type:

        fun Object.isInteger() {
            when(this) {
                // already Int?
                is Int -> true

                // real, but with no declimal part?
                is Real -> toInt() == this

                // string with int or real reuusig code above
                is String -> toReal().isInteger()
                
                // otherwise, no:
                else -> false
            }
        }

        // Let's test:        
        assert( 12.isInteger() == true )
        assert( 12.1.isInteger() == false )
        assert( "5".isInteger() )
        assert( ! "5.2".isInteger() )
        >>> void

__Important note__ as for version 0.6.9, extensions are in __global scope__. It means, that once applied to a global type (Int in our sample), they will be available for _all_ contexts, even new created, 
as they are modifying the type, not the context.

Beware of it. We might need to reconsider it later.

# Theory

## Basic principles:

- Everything is an instance of some class
- Every class except Obj has at least one parent
- Obj has no parents and is the root of the hierarchy
- instance has member fields and member functions
- Every class has hclass members and class functions, or companion ones, are these of the base class.
- every class has _type_ which is an instances of ObjClass
- ObjClass sole parent is Obj
- ObjClass contains code for instance methods, class fields, hierarchy information.
- Class information is also scoped. 
- We acoid imported classes duplication using packages and import caching, so the same imported module is the same object in all its classes.

## Instances

Result of executing of any expression or statement in the Lyng is the object that
inherits `Obj`, but is not `Obj`. For example it could be Int, void, null, real, string, bool, etc.

This means whatever expression returns or the variable holds, is the first-class
object, no differenes. For example:

    1.67.roundToInt()
    1>>> 2

Here, instance method of the real object, created from literal `1.67` is called.

## Instance class

Everything can be classified, and classes could be tested for equivalence:

    3.14::class
    1>>> Real

Class is the object, naturally, with class:

    3.14::class::class
    1>>> Class

Classes can be compared:

    assert(1.21::class == Math.PI::class)
    assert(3.14::class != 1::class)
    assert(π::class == Real)
    π::class
    >>> Real

Note `Real` class: it is global variable for Real class; there are such class instances for all built-in types:

    assert("Hello"::class == String)
    assert(1970::class == Int)
    assert(true::class == Bool)
    assert('$'::class == Char)
    >>> void

Singleton classes also have class:

    null::class
    >>> Null

At this time, `Obj` can't be accessed as a class.


### Methods in-depth

Regular methods are called on instances as usual `instance.method()`. The method resolution order is

1. this instance methods;
2. parents method: no guarantee but we enumerate parents in order of appearance;
3. possible extension methods (scoped)

TBD

[argument list](declaring_arguments.md)