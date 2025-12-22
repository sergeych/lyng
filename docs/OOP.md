# Object-oriented programming

[//]: # (topMenu)

Lyng supports first class OOP constructs, based on classes with multiple inheritance.

## Class Declaration 

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

## Instance initialization: init block

In addition to the primary constructor arguments, you can provide an `init` block that runs on each instance creation. This is useful for more complex initializations, side effects, or setting up fields that depend on multiple constructor parameters.

    class Point(val x, val y) {
       var magnitude
       
       init {
          magnitude = Math.sqrt(x*x + y*y)
       }
    }

Key features of `init` blocks:
- **Scope**: They have full access to `this` members and all primary constructor parameters.
- **Order**: In a single-inheritance scenario, `init` blocks run immediately after the instance fields are prepared but before the primary constructor body logic.
- **Multiple blocks**: You can have multiple `init` blocks; they will be executed in the order they appear in the class body.

### Initialization in Multiple Inheritance

In cases of multiple inheritance, `init` blocks are executed following the constructor chaining rule:
1. All ancestors are initialized first, following the inheritance hierarchy (diamond-safe: each ancestor is initialized exactly once).
2. The `init` blocks of each class are executed after its parents have been fully initialized.
3. For a hierarchy `class D : B, C`, the initialization order is: `B`'s chain, then `C`'s chain (skipping common ancestors with `B`), and finally `D`'s own `init` blocks.

### Initialization during Deserialization

When an object is restored from a serialized form (e.g., using `Lynon`), `init` blocks are **re-executed**. This ensures that transient state or derived fields are correctly recalculated upon restoration. However, primary constructors are **not** re-called during deserialization; only the `init` blocks and field initializers are executed to restore the instance state.

Class point has a _method_, or a _member function_ `length()` that uses its _fields_ `x` and `y` to
calculate the magnitude. Length is called

### default values in constructor

Constructor arguments are the same as function arguments except visibility
statements discussed later, there could be default values, ellipsis, etc.

    class Point(x=0,y=0) 
    val p = Point()
    assert( p.x == 0 && p.y == 0 )
    
    // Named arguments in constructor calls use colon syntax:
    val p2 = Point(y: 10, x: 5)
    assert( p2.x == 5 && p2.y == 10 )
    >>> void

Note that unlike **Kotlin**, which uses `=` for named arguments, Lyng uses `:` to avoid ambiguity with assignment expressions.

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

## Multiple Inheritance (MI)

Lyng supports declaring a class with multiple direct base classes. The syntax is:

```
class Foo(val a) {
    var tag = "F"
    fun runA() { "ResultA:" + a }
    fun common() { "CommonA" }
    private fun privateInFoo() {}
    protected fun protectedInFoo() {}
}

class Bar(val b) {
    var tag = "B"
    fun runB() { "ResultB:" + b }
    fun common() { "CommonB" }
}

// Multiple inheritance with per‑base constructor arguments
class FooBar(a, b) : Foo(a), Bar(b) {
    // You can disambiguate via qualified this or casts
    fun fromFoo() { this@Foo.common() }
    fun fromBar() { this@Bar.common() }
}

val fb = FooBar(1, 2)
assertEquals("ResultA:1", fb.runA())
assertEquals("ResultB:2", fb.runB())
// Unqualified ambiguous member resolves to the first base (leftmost)
assertEquals("CommonA", fb.common())
// Disambiguation via casts
assertEquals("CommonB", (fb as Bar).common())
assertEquals("CommonA", (fb as Foo).common())

// Field inheritance with name collisions
assertEquals("F", fb.tag)            // unqualified: leftmost base
assertEquals("F", (fb as Foo).tag)   // qualified read: Foo.tag
assertEquals("B", (fb as Bar).tag)   // qualified read: Bar.tag

fb.tag = "X"                         // unqualified write updates leftmost base
assertEquals("X", (fb as Foo).tag)
assertEquals("B", (fb as Bar).tag)

(fb as Bar).tag = "Y"                 // qualified write updates Bar.tag
assertEquals("X", (fb as Foo).tag)
assertEquals("Y", (fb as Bar).tag)
```

Key rules and features:

- Syntax
  - `class Derived(args) : Base1(b1Args), Base2(b2Args)`
  - Each direct base may receive constructor arguments specified in the header. Only direct bases receive header args; indirect bases must either be default‑constructible or receive their args through their direct child (future extensions may add more control).

- Resolution order (C3 MRO — active)
  - Member lookup is deterministic and follows C3 linearization (Python‑like), which provides a monotonic, predictable order for complex hierarchies and diamonds.
  - Intuition: for `class D() : B(), C()` where `B()` and `C()` both derive from `A()`, the C3 order is `D → B → C → A`.
  - The first visible match along this order wins.

- Qualified dispatch
  - Inside a class body, use `this@Type.member(...)` to start lookup at the specified ancestor.
  - For arbitrary receivers, use casts: `(expr as Type).member(...)` or `(expr as? Type)?.member(...)` (safe‑call `?.` is already available in Lyng).
  - Qualified access does not relax visibility.

- Field inheritance (`val`/`var`) and collisions
  - Instance storage is kept per declaring class, internally disambiguated; unqualified read/write resolves to the first match in the resolution order (leftmost base).
  - Qualified read/write (via `this@Type` or casts) targets the chosen ancestor’s storage.
  - `val` remains read‑only; attempting to write raises an error as usual.

- Constructors and initialization
  - During construction, direct bases are initialized left‑to‑right in the declaration order. Each ancestor is initialized at most once (diamond‑safe de‑duplication).
  - Arguments in the header are evaluated in the instance scope and passed to the corresponding direct base constructor.
  - The most‑derived class’s constructor runs after the bases.

- Visibility
  - `private`: accessible only inside the declaring class body; not visible in subclasses and cannot be accessed via `this@Type` or casts.
  - `protected`: accessible in the declaring class and in any of its transitive subclasses (including MI), but not from unrelated contexts; qualification/casts do not bypass it.

- Diagnostics
  - When a member/field is not found, error messages include the receiver class name and the considered linearization order, with suggestions to disambiguate using `this@Type` or casts if appropriate.
  - Qualifying with a non‑ancestor in `this@Type` reports a clear error mentioning the receiver lineage.
  - `as`/`as?` cast errors mention the actual and target types.

Compatibility notes:

- Existing single‑inheritance code continues to work unchanged; its resolution order reduces to the single base.
- If your previous code accidentally relied on non‑deterministic parent set iteration, it may change behavior — the new deterministic order is a correctness fix.

### Migration note (declaration‑order → C3)

Earlier drafts and docs described a declaration‑order depth‑first linearization. Lyng now uses C3 MRO for member lookup and disambiguation. Most code should continue to work unchanged, but in rare edge cases involving diamonds or complex multiple inheritance, the chosen base for an ambiguous member may change to reflect C3. If needed, disambiguate explicitly using `this@Type.member(...)` inside class bodies or casts `(expr as Type).member(...)` from outside.

## Enums

Lyng provides lightweight enums for representing a fixed set of named constants. Enums are classes whose instances are predefined and singletons.

Current syntax supports simple enum declarations with just entry names:

    enum Color {
        RED, GREEN, BLUE
    }

Usage:

- Type of entries: every entry is an instance of its enum type.

      assert( Color.RED is Color )

- Order and names: each entry has zero‑based `ordinal` and string `name`.

      assertEquals(0, Color.RED.ordinal)
      assertEquals("BLUE", Color.BLUE.name)

- All entries as a list in declaration order: `EnumType.entries`.

      assertEquals([Color.RED, Color.GREEN, Color.BLUE], Color.entries)

- Lookup by name: `EnumType.valueOf("NAME")` → entry.

      assertEquals(Color.GREEN, Color.valueOf("GREEN"))

- Equality and comparison:
  - Equality uses identity of entries, e.g., `Color.RED == Color.valueOf("RED")`.
  - Cross‑enum comparisons are not allowed.
  - Ordering comparisons use `ordinal`.

      assert( Color.RED == Color.valueOf("RED") )
      assert( Color.RED.ordinal < Color.BLUE.ordinal )
      >>> void

### Enums with `when`

Use `when(subject)` with equality branches for enums. See full `when` guide: [The `when` statement](when.md).

    enum Color { RED, GREEN, BLUE }

    fun describe(c) {
        when(c) {
            Color.RED, Color.GREEN -> "primary-like"
            Color.BLUE -> "blue"
            else -> "unknown"   // if you pass something that is not a Color
        }
    }
    assertEquals("primary-like", describe(Color.RED))
    assertEquals("blue", describe(Color.BLUE))
    >>> void

### Serialization

Enums are serialized compactly with Lynon: the encoded value stores just the entry ordinal within the enum type, which is both space‑efficient and fast.

    import lyng.serialization

    enum Color { RED, GREEN, BLUE }

    val e = Lynon.encode(Color.BLUE)
    val decoded = Lynon.decode(e)
    assertEquals(Color.BLUE, decoded)
    >>> void

Notes and limitations (current version):

- Enum declarations support only simple entry lists: no per‑entry bodies, no custom constructors, and no user‑defined methods/fields on the enum itself yet.
- `name` and `ordinal` are read‑only properties of an entry.
- `entries` is a read‑only list owned by the enum type.

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

### Protected members

Protected members are available to the declaring class and all of its transitive subclasses (including via MI), but not from unrelated contexts:

```
class A() {
    protected fun ping() { "pong" }
}
class B() : A() {
    fun call() { this@A.ping() }
}

val b = B()
assertEquals("pong", b.call())

// Unrelated access is forbidden, even via cast
assertThrows { (b as A).ping() }
```

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

## dynamic symbols

Sometimes it is convenient to provide methods and variables whose names are not known at compile time. For example, it could be external interfaces not known to library code, user-defined data fields, etc. You can use `dynamic` function to create such:

    // val only dynamic object
    val accessor = dynamic {
        // all symbol reads are redirected here:
        get { name ->
            // lets provide one dynamic symbol:
            if( name == "foo" ) "bar" else null
            // consider also throw SymbolNotDefinedException
        }
    }
    
    // now we can access dynamic "fields" of accessor:
    assertEquals("bar", accessor.foo)
    assertEquals(null, accessor.bar)
    >>> void

The same we can provide writable dynamic fields (var-type), adding set method:

    // store one dynamic field here
    var storedValueForBar = null
    
    // create dynamic object with 2 fields:
    val accessor = dynamic {
        get { name ->
            when(name) {
                // constant field
                "foo" -> "bar"
                // mutable field
                "bar" -> storedValueForBar 

                else -> throw SymbolNotFoundException()
            }
        }
        set { name, value ->
            // only 'bar' is mutable:
            if( name == "bar" )
                storedValueForBar = value
                // the rest is immotable. consider throw also
                // SymbolNotFoundException when needed.
            else throw IllegalAssignmentException("Can't assign "+name)
        }
    }
    
    assertEquals("bar", accessor.foo)
    assertEquals(null, accessor.bar)
    accessor.bar = "buzz"
    assertEquals("buzz", accessor.bar)
    
    assertThrows {
        accessor.bad = "!23"
    }
    void
    >>> void

Of course, you can return any object from dynamic fields; returning lambdas let create _dynamic methods_ - the callable method. It is very convenient to implement libraries with dynamic remote interfaces, etc.

### Dynamic indexers

Index access for dynamics is passed to the same getter and setter, so it is
generally the same:

    var storedValue = "bar"
    val x = dynamic {
        get { 
            if( it == "foo" ) storedValue
            else null
        }
    }
    assertEquals("bar", x["foo"] )
    assertEquals("bar", x.foo )
    >>> void

And assigning them works the same. You can make it working
mimicking arrays, but remember, it is not Collection so
collection's sugar won't work with it:

    var storedValue = "bar"
    val x = dynamic {
        get { 
            when(it) {
                "size" -> 1
                0 -> storedValue
                else -> null
            }
        }
        set { index, value -> 
            if( index == 0 ) storedValue = value
            else throw "Illegal index: "+index
        }
    }
    assertEquals("bar", x[0] )
    assertEquals(1, x.size )
    x[0] = "buzz"
    assertThrows { x[1] = 1 }
    assertEquals("buzz", storedValue)
    assertEquals("buzz", x[0])
    >>> void

If you want dynamic to function like an array, create a [feature
request](https://gitea.sergeych.net/SergeychWorks/lyng/issues).

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
### Visibility from within closures and instance scopes

When a closure executes within a method, the closure retains the lexical class context of its creation site. This means private/protected members of that class remain accessible where expected (subject to usual visibility rules). Field resolution checks the declaring class and validates access using the preserved `currentClassCtx`.

See also: [Scopes and Closures: resolution and safety](scopes_and_closures.md)
