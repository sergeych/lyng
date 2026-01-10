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

## Singleton Objects

Singleton objects are declared using the `object` keyword. An `object` declaration defines both a class and a single instance of that class at the same time. This is perfect for stateless utilities, global configuration, or shared delegates.

```lyng
object Config {
    val version = "1.0.0"
    val debug = true
    
    fun printInfo() {
        println("App version: " + version)
    }
}

// Usage:
println(Config.version)
Config.printInfo()
```

Objects can also inherit from classes or interfaces:

```lyng
object DefaultLogger : Logger("Default") {
    override fun log(msg) {
        println("[DEFAULT] " + msg)
    }
}
```

## Object Expressions

Object expressions allow you to create an instance of an anonymous class. This is useful when you need to provide a one-off implementation of an interface or inherit from a class without declaring a new named subclass.

```lyng
val worker = object : Runnable {
    override fun run() {
        println("Working...")
    }
}
```

Object expressions can implement multiple interfaces and inherit from one class:

```lyng
val x = object : Base(arg1), Interface1, Interface2 {
    val property = 42
    override fun method() = property * 2
}
```

### Scoping and `this@object`

Object expressions capture their lexical scope, meaning they can access local variables and members of the outer class. When `this` is rebound (for example, inside an `apply` block), you can use the reserved alias `this@object` to refer to the innermost anonymous object instance.

```lyng
val handler = object {
    fun process() {
        this@object.apply {
            // here 'this' is rebound to the map/context
            // but we can still access the anonymous object via this@object
            println("Processing in " + this@object)
        }
    }
}
```

### Serialization and Identity

- **Serialization**: Anonymous objects are **not serializable**. Attempting to encode an anonymous object via `Lynon` will throw a `SerializationException`. This is because their class definition is transient and cannot be safely restored in a different session or process.
- **Type Identity**: Every object expression creates a unique anonymous class. Two identical object expressions will result in two different classes with distinct type identities.

## Properties

Properties allow you to define member accessors that look like fields but execute code when read or written. Unlike regular fields, properties in Lyng do **not** have automatic backing fields; they are pure accessors.

### Basic Syntax

Properties are declared using `val` (read-only) or `var` (read-write) followed by a name and a `get` (and optionally `set`) accessor. Unlike fields, properties do not have automatic storage and must compute their values or delegate to other members.

The standard syntax uses parentheses:
```lyng
class Person(private var _age: Int) {
    // Read-only property
    val ageCategory 
        get() {
            if (_age < 18) "Minor" else "Adult"
        }

    // Read-write property
    var age: Int
        get() { _age }
        set(value) {
            if (value >= 0) _age = value
        }
}
```

### Laconic Syntax (Optional Parentheses)

For even cleaner code, you can omit the parentheses for `get` and `set`. This is especially useful for simple expression shorthand:

```lyng
class Circle(val radius: Real) {
    // Laconic expression shorthand
    val area get = π * radius * radius
    val circumference get = 2 * π * radius
    
    fun diameter() = radius * 2
}

fun median(a, b) = (a + b) / 2

class Counter {
    private var _count = 0
    var count get = _count set(v) = _count = v
}
```

### Key Rules

- **`val` properties** must have a `get` accessor (with or without parentheses) and cannot have a `set`.
- **`var` properties** must have both `get` and `set` accessors.
- **Functions and methods** can use the `=` shorthand to return the result of a single expression.
- **`override` is mandatory**: If you are overriding a member from a base class, you MUST use the `override` keyword.
- **No Backing Fields**: There is no magic `field` identifier. If you need to store state, you must declare a separate (usually `private`) field.
- **Type Inference**: You can omit the type declaration if it can be inferred or if you don't need strict typing.

### Lazy Properties with `cached`

When you want to define a property that is computed only once (on demand) and then remembered, use the built-in `cached` function. This is more efficient than a regular property with `get()` if the computation is expensive, as it avoids re-calculating the value on every access.

```lyng
class DataService(val id: Int) {
    // The lambda passed to cached is only executed once, the first time data() is called.
    val data = cached {
        println("Fetching data for " + id)
        // Perform expensive operation
        "Record " + id
    }
}

val service = DataService(42)
// No printing yet
println(service.data()) // Prints "Fetching data for 42", then returns "Record 42"
println(service.data()) // Returns "Record 42" immediately (no second fetch)
```

Note that `cached` returns a lambda, so you access the value by calling it like a method: `service.data()`. This is a powerful pattern for lazy-loading resources, caching results of database queries, or delaying expensive computations until they are truly needed.

## Delegation

Delegation allows you to hand over the logic of a property or function to another object. This is done using the `by` keyword.

### Property Delegation

Instead of providing `get()` and `set()` accessors, you can delegate them to an object that implements the `getValue` and `setValue` methods.

```lyng
class User {
    var name by MyDelegate()
}
```

### Function Delegation

You can also delegate a whole function to an object. When the function is called, it will invoke the delegate's `invoke` method.

```lyng
fun remoteAction by RemoteProxy("actionName")
```

### The Unified Delegate Interface

A delegate is any object that provides the following methods (all optional depending on usage):

- `getValue(thisRef, name)`: Called when a delegated `val` or `var` is read.
- `setValue(thisRef, name, newValue)`: Called when a delegated `var` is written.
- `invoke(thisRef, name, args...)`: Called when a delegated `fun` is invoked.
- `bind(name, access, thisRef)`: Called once during initialization to configure or validate the delegate.

### Map as a Delegate

Maps can also be used as delegates. When delegated to a property, the map uses the property name as the key:

```lyng
val settings = { "theme": "dark", "fontSize": 14 }
val theme by settings
var fontSize by settings

println(theme)    // "dark"
fontSize = 16     // Updates settings["fontSize"]
```

For more details and advanced patterns (like `lazy`, `observable`, and shared stateless delegates), see the [Delegation Guide](delegation.md).

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
    
    // Auto-substitution shorthand for named arguments:
    val x = 1
    val y = 2
    val p3 = Point(x:, y:)
    assert( p3.x == 1 && p3.y == 2 )
    >>> void

Note that unlike **Kotlin**, which uses `=` for named arguments, Lyng uses `:` to avoid ambiguity with assignment expressions.

### Late-initialized `val` fields

You can declare a `val` field without an immediate initializer if you provide an assignment for it within an `init` block or the class body. This is useful when the initial value depends on logic that cannot be expressed in a single expression.

```lyng
class DataProcessor(data: Object) {
    val result: Object
    
    init {
        // Complex initialization logic
        result = transform(data)
    }
}
```

Key rules for late-init `val`:
- **Compile-time Check**: The compiler ensures that every `val` declared without an initializer in a class body has at least one assignment within that class body (including `init` blocks). Failing to do so results in a syntax error.
- **Write-Once**: A `val` can only be assigned once. Even if it was declared without an initializer, once it is assigned a value (e.g., in `init`), any subsequent assignment will throw an `IllegalAssignmentException`.
- **Access before Initialization**: If you attempt to read a late-init `val` before it has been assigned (for example, by calling a method in `init` that reads the field before its assignment), it will hold a special `Unset` value. Using `Unset` for most operations (like arithmetic or method calls) will throw an `UnsetException`.
- **No Extensions**: Extension properties do not support late initialization as they do not have per-instance storage. Extension `val`s must always have an initializer or a `get()` accessor.

### The `Unset` singleton

The `Unset` singleton represents a field that has been declared but not yet initialized. While it can be compared and converted to a string, most other operations on it are forbidden to prevent accidental use of uninitialized data.

```lyng
class T {
    val x
    fun check() {
        if (x == Unset) println("Not ready")
    }
    init {
        check() // Prints "Not ready"
        x = 42
    }
}
```

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

```lyng
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
  - Each direct base may receive constructor arguments specified in the header. Only direct bases receive header args; indirect bases must either be default‑constructible or receive their args through their direct child.

- Resolution order (C3 MRO)
  - Member lookup is deterministic and follows C3 linearization (Python‑like), which provides a monotonic, predictable order for complex hierarchies and diamonds.
  - Intuition: for `class D() : B(), C()` where `B()` and `C()` both derive from `A()`, the C3 order is `D → B → C → A`.
  - The first visible match along this order wins.

- Qualified dispatch
  - Inside a class body, use `this@Type.member(...)` to start lookup at the specified ancestor.
  - For arbitrary receivers, use casts: `(expr as Type).member(...)` or `(expr as? Type)?.member(...)`.
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

## Abstract Classes and Members

An `abstract` class is a class that cannot be instantiated and is intended to be inherited by other classes. It can contain `abstract` members that have no implementation and must be implemented by concrete subclasses.

### Abstract Classes

To declare an abstract class, use the `abstract` modifier:

```lyng
abstract class Shape {
    abstract fun area(): Real
}
```

Abstract classes can have constructors, fields, and concrete methods, just like regular classes.

### Abstract Members

Methods and variables (`val`/`var`) can be marked as `abstract`. Abstract members must not have a body or initializer.

```lyng
abstract class Base {
    abstract fun foo(): Int
    abstract var bar: String
}
```

- **Safety**: `abstract` members cannot be `private`, as they must be visible to subclasses for implementation.
- **Contract of Capability**: An `abstract val/var` represents a requirement for a capability. It can be implemented by either a **field** (storage) or a **property** (logic) in a subclass.

## Interfaces

An `interface` in Lyng is a synonym for an `abstract class`. Following the principle that Lyng's Multiple Inheritance system is powerful enough to handle stateful contracts, interfaces support everything classes do, including constructors, fields, and `init` blocks.

```lyng
interface Named(val name: String) {
    fun greet() { "Hello, " + name }
}

class Person(name) : Named(name)
```

Using `interface` instead of `abstract class` is a matter of semantic intent, signaling that the class is primarily intended to be used as a contract in MI.

### Implementation by Parts

One of the most powerful benefits of Lyng's Multiple Inheritance and C3 MRO is the ability to satisfy an interface's requirements "by parts" from different parent classes. Since an `interface` can have state and requirements, a subclass can inherit these requirements and satisfy them using members inherited from other parents in the MRO chain.

Example:

```lyng
// Interface with state (id) and abstract requirements
interface Character(val id) {
    var health
    var mana
    
    fun isAlive() = health > 0
    fun status() = name + " (#" + id + "): " + health + " HP, " + mana + " MP"
}

// Parent class 1: provides health
class HealthPool(var health)

// Parent class 2: provides mana and name
class ManaPool(var mana) {
    val name = "Hero"
}

// Composite class: implements Character by combining HealthPool and ManaPool
class Warrior(id, h, m) : HealthPool(h), ManaPool(m), Character(id)

val w = Warrior(1, 100, 50)
assertEquals("Hero (#1): 100 HP, 50 MP", w.status())
```

In this example, `Warrior` inherits from `HealthPool`, `ManaPool`, and `Character`. The abstract requirements `health` and `mana` from `Character` are automatically satisfied by the matching members inherited from `HealthPool` and `ManaPool`. The `status()` method also successfully finds the `name` field from `ManaPool`. This pattern allows for highly modular and reusable "trait-like" classes that can be combined to fulfill complex contracts without boilerplate proxy methods.

## Overriding and Virtual Dispatch

When a class defines a member that already exists in one of its parents, it is called **overriding**.

### The `override` Keyword

In Lyng, the `override` keyword is **mandatory when declaring a member** that exists in the ancestor chain (MRO).

```lyng
class Parent {
    fun foo() = 1
}

class Child : Parent() {
    override fun foo() = 2 // Mandatory override keyword
}
```

- **Implicit Satisfaction**: If a class inherits an abstract requirement and a matching implementation from different parents, the requirement is satisfied automatically without needing an explicit `override` proxy.
- **No Accidental Overrides**: If you define a member that happens to match a parent's member but you didn't use `override`, the compiler will throw an error. This prevents the "Fragile Base Class" problem.
- **Private Members**: Private members in parent classes are NOT part of the virtual interface and cannot be overridden. Defining a member with the same name in a subclass is allowed without `override` and is treated as a new, independent member.

### Visibility Widening

A subclass can increase the visibility of an overridden member (e.g., `protected` → `public`), but it is strictly forbidden from narrowing it (e.g., `public` → `protected`).

### The `closed` Modifier

To prevent a member from being overridden in subclasses, use the `closed` modifier (equivalent to `final` in other languages).

```lyng
class Critical {
    closed fun secureStep() { ... }
}
```

Attempting to override a `closed` member results in a compile-time error.

## Operator Overloading

Lyng allows you to overload standard operators by defining specific named methods in your classes. When an operator expression is evaluated, Lyng delegates the operation to these methods if they are available.

### Binary Operators

To overload a binary operator, define the corresponding method that takes one argument:

| Operator | Method Name |
| :--- | :--- |
| `a + b` | `plus(other)` |
| `a - b` | `minus(other)` |
| `a * b` | `mul(other)` |
| `a / b` | `div(other)` |
| `a % b` | `mod(other)` |
| `a && b` | `logicalAnd(other)` |
| `a \|\| b` | `logicalOr(other)` |
| `a =~ b` | `operatorMatch(other)` |
| `a & b` | `bitAnd(other)` |
| `a \| b` | `bitOr(other)` |
| `a ^ b` | `bitXor(other)` |
| `a << b` | `shl(other)` |
| `a >> b` | `shr(other)` |

Example:
```lyng
class Vector(val x, val y) {
    fun plus(other) = Vector(x + other.x, y + other.y)
    override fun toString() = "Vector(${x}, ${y})"
}

val v1 = Vector(1, 2)
val v2 = Vector(3, 4)
assertEquals(Vector(4, 6), v1 + v2)
```

### Unary Operators

Unary operators are overloaded by defining methods with no arguments:

| Operator | Method Name |
| :--- | :--- |
| `-a` | `negate()` |
| `!a` | `logicalNot()` |
| `~a` | `bitNot()` |

### Assignment Operators

Assignment operators like `+=` first attempt to call a specific assignment method. If that method is not defined, they fall back to a combination of the binary operator and a regular assignment (e.g., `a = a + b`).

| Operator | Method Name | Fallback |
| :--- | :--- | :--- |
| `a += b` | `plusAssign(other)` | `a = a + b` |
| `a -= b` | `minusAssign(other)` | `a = a - b` |
| `a *= b` | `mulAssign(other)` | `a = a * b` |
| `a /= b` | `divAssign(other)` | `a = a / b` |
| `a %= b` | `modAssign(other)` | `a = a % b` |

Example of in-place mutation:
```lyng
class Counter(var value) {
    fun plusAssign(n) {
        value = value + n
    }
}

val c = Counter(10)
c += 5
assertEquals(15, c.value)
```

### Comparison Operators

Comparison operators use `compareTo` and `equals`.

| Operator | Method Name |
| :--- | :--- |
| `a == b`, `a != b` | `equals(other)` |
| `<`, `>`, `<=`, `>=`, `<=>` | `compareTo(other)` |

- `compareTo` should return:
  - `0` if `a == b`
  - A negative integer if `a < b`
  - A positive integer if `a > b`
- The `<=>` (shuttle) operator returns the result of `compareTo` directly.
- `equals` returns a `Bool`. If `equals` is not explicitly defined, Lyng falls back to `compareTo(other) == 0`.

> **Note**: Methods that are already defined in the base `Obj` class (like `equals`, `toString`, or `contains`) require the `override` keyword when redefined in your class or as an extension. Other operator methods (like `plus` or `negate`) do not require `override` unless they are already present in your class's hierarchy.

### Increment and Decrement

`++` and `--` operators are implemented using `plus(1)` or `minus(1)` combined with an assignment back to the variable. If the variable is a field or local variable, it will be updated with the result of the operation.

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

## Exception Classes

You can define your own exception classes by inheriting from the built-in `Exception` class. User-defined exceptions are regular classes and can have their own properties and methods.

```lyng
class MyError(val code, m) : Exception(m)

try {
    throw MyError(500, "Internal Server Error")
}
catch(e: MyError) {
    println("Error " + e.code + ": " + e.message)
}
```

For more details on error handling, see the [Exceptions Handling Guide](exceptions_handling.md).

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

### Restricted Setter Visibility

You can restrict the visibility of a `var` field's or property's setter by using `private set` or `protected set` modifiers. This allows the member to be publicly readable but only writable from within the class or its subclasses.

#### On Fields

```lyng
class SecretCounter {
    var count = 0
        private set // Can be read anywhere, but written only in SecretCounter
        
    fun increment() { count++ }
}

val c = SecretCounter()
println(c.count) // OK
c.count = 10     // Throws IllegalAccessException
c.increment()    // OK
```

#### On Properties

You can also apply restricted visibility to custom property setters:

```lyng
class Person(private var _age: Int) {
    var age
        get() = _age
        private set(v) { if (v >= 0) _age = v }
}
```

#### Protected Setters and Inheritance

A `protected set` allows subclasses to modify a field that is otherwise read-only to the public:

```lyng
class Base {
    var state = "initial"
        protected set
}

class Derived : Base() {
    fun changeState(newVal) {
        state = newVal // OK: protected access from subclass
    }
}

val d = Derived()
println(d.state) // OK: "initial"
d.changeState("updated")
println(d.state) // OK: "updated"
d.state = "bad"  // Throws IllegalAccessException: public write not allowed
```

### Key Rules and Limitations

- **Only for `var`**: Restricted setter visibility cannot be used with `val` declarations, as they are inherently read-only. Attempting to use it with `val` results in a syntax error.
- **Class Body Only**: These modifiers can only be used on members declared within the class body. They are not supported for primary constructor parameters.
- **`private set`**: The setter is only accessible within the same class context (specifically, when `this` is an instance of that class).
- **`protected set`**: The setter is accessible within the declaring class and all its transitive subclasses.
- **Multiple Inheritance**: In MI scenarios, visibility is checked against the class that actually declared the member. Qualified access (e.g., `this@Base.field = value`) also respects restricted setter visibility.

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

It sometimes happen that the class is missing some particular functionality that can be _added to it_ without rewriting its inner logic and using its private state. In this case _extension members_ could be used.

## Extension methods

For example, we want to create an extension method that would test if some object of unknown type contains something that can be interpreted as an integer. In this case we _extend_ class `Object`, as it is the parent class for any instance of any type:

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

## Extension properties

Just like methods, you can extend existing classes with properties. These can be defined using simple initialization (for `val` only) or with custom accessors.

### Simple val extension

A read-only extension can be defined by assigning an expression:

```lyng
val String.isLong = length > 10

val s = "Hello, world!"
assert(s.isLong)
```

### Properties with accessors

For more complex logic, use `get()` and `set()` blocks:

```lyng
class Box(var value: Int)

var Box.doubledValue
    get() = value * 2
    set(v) = value = v / 2

val b = Box(10)
assertEquals(20, b.doubledValue)
b.doubledValue = 30
assertEquals(15, b.value)
```

Extension members are strictly barred from accessing private members of the class they extend, maintaining encapsulation.

### Extension Scoping and Isolation

Extensions in Lyng are **scope-isolated**. This means an extension is only visible within the scope where it is defined and its child scopes. This reduces the "attack surface" and prevents extensions from polluting the global space or other modules.

#### Scope Isolation Example

You can define different extensions with the same name in different scopes:

```lyng
fun scopeA() {
    val Int.description = "Number: " + toString()
    assertEquals("Number: 42", 42.description)
}

fun scopeB() {
    val Int.description = "Value: " + toString()
    assertEquals("Value: 42", 42.description)
}

scopeA()
scopeB()

// Outside those scopes, Int.description is not defined
assertThrows { 42.description }
```

This isolation ensures that libraries can use extensions internally without worrying about name collisions with other libraries or the user's code. When a module is imported using `use`, its top-level extensions become available in the importing scope.

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
- We avoid imported classes duplication using packages and import caching, so the same imported module is the same object in all its classes.

## Instances

Result of executing of any expression or statement in the Lyng is the object that
inherits `Obj`, but is not `Obj`. For example, it could be Int, void, null, real, string, bool, etc.

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
