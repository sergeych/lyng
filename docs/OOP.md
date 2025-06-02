# OO implementation in Lying

Basic principles:

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

Result of executing of any expression or statement in the Lying is the object that
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

More complex is singleton classes, because you don't need to compare their class
instances and generally don't need them at all, these are normally just Obj:

    null::class
    >>> Obj

At this time, `Obj` can't be accessed as a class.


### Methods in-depth

Regular methods are called on instances as usual `instance.method()`. The method resolution order is

1. this instance methods;
2. parents method: no guarantee but we enumerate parents in order of appearance;
3. possible extension methods (scoped)
