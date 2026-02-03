The goal is to add types with minimal changes to existing language,
using inference as wide as possible, and providing generic styles
better than even in C++ ;)

```lyng
fun t(x) {
    // x is Object (non-null)
    println(x)
}

t(3) // ok
```

Inferred by defaults:

```lyng
fun t1(a=0,b="foo") {
    // a is Int, b is String
}
class Point(x=0.0, y=0.0) // Real, Real
```
val/ver declaration infers the type:

```lyng
val x = 3 // INT

extern fun obj_fun(): Object
extern fun int_fun(): Int

val  i = int_fun() // inferred as Int
val o = obj_fun()  // inferred as Object 

val x: Int = objfun() // compiler generates "objfun as Int"
val y = objfun() as Int // Int, inferred
```

Inferring result:

```lyng
fun ifun1(a=0) = a+1 // type is (Int)->Int, inferred
fun ifun2(a=0) { a+1 } // type is (Int)->Int, inferred
fun ifun3(a=0) { void } // type is void inferred
fun ifun4(a: Int): Void { }
fun ifun5() { return 5 } // Inferred, ()->Int
```

In more complex cases we will use type parameters, like in Kotlin, Scala, Typescript:

```lyng
fun f1<T,R>(a: T,b: T): R {
    (a + b) as R
}
// Type parameters are first class citizens:
fun f<T>(x: T) = T::class.name + "!"
assertEquals( "String!", f("foo") )
```
When the compiler ecnounters template types, it adds it as invisible parameters to a fun/method
call, or a class instance (to constructor, much the same as with a fun), so it is usable inside
the function/class body, as a `Class` instance:

```lyng
class Test<P> {
    fun P_is_Int() = P is Int
}
```

So we do not expand templates, but we store generic types. Types can have bounds, like in Typescript:

```
class Foo<T: Iterable & Comparable> {
    // ...
}
class Bar<T: Iterable | Comparable> {
    val description by lazy {
        if( T is Iterable ) "iterable and maybe comparable"
        else "comparable only"
    }
}
// compile time error:
assertEquals( "comparable only", Bar(42).description )
assertEquals( "iterable and maybe comparable", Bar([42]).description )
```

When bounds are specified, they are checked at compile time. Type compositions also can be used
when declare types much the same:

```lyng
fun x10(x: Int | Real) {
    x  * 10  
}

assertEquals( 20, x10(2) )
assert( x10(2) is Int )

assertEquals(25, x10(2.5) )
assert( x10(2.5) is Real )

// the followinf is a compile time error:
x10("20") // string does not fit bounds
```
It is possible to create bounds that probably can't be satisfied:
```lyng
fun x10(x: Int & String) {
    // ....
}
```
but in fact some strange programmer can create `class T: String, Int` so we won't check it for sanity, except that we certainly disallow <T: Void &...> 

Instead of template expansions, we might provide explicit `inline` later.

Notes and open questions to answer in this spec:
- Void vs void: Void is a class, and void is the same as Void (alias).

`void` is a singleton of class Void; so `return void` is ok. `fun (): void` is allowed as syntax sugar and is checked as returning Void.

- Nullability rule: Are all types nullable by default (Object?) or non-null by default (Object), and how is nullable spelled? (e.g., Object?, Int?)

Not null by default (Object), must be specified with `?` suffix. We use Kotlin-style `!!` for non-null assertion. Therefore we check nullability at compile time, and we throw NPE only at `x!!` or `obj as X` (if obj is nullable, it is same as `obj!! as X`).

Return type inference and nullability:
- If any branch or return expression is nullable, the inferred return type is nullable.
- This is independent of whether `return` is used or implicit last-expression rules apply.

- Default type of untyped values: If a parameter has no type and no default, is it Object? (dynamic), or a new top type?

Lets discuss in more details. For example:
```lyng
var x // Unset, yet no type, no type check
x = 1 // set to 1, since now type is Int and is checled
x = "11" // error. type is already determined
```
With classes it is more interesting:
```lyng
// we assume it is x: Object, y: Object, no need to specify type
class Point(x,y) {
    val d by lazy { sqrt(x*x + y*y) } // real!
}
assert( 5, Point(3,4).distance.roundToInt() )
// and this is ok too:
assert( 5, Point(3,4.0).distance.roundToInt() )
```

Syntax sugar for parameters:
- `fun foo(x?)` means `fun foo(x: Object?)`
- `class X(a, b?)` means `class X(a: Object, b: Object?)`
- `fun foo(x=3?)` means `fun foo(x: Int?) { ... }` with a nullable default

Untyped parameters:
- `fun foo(x)` means `x: Object`
- `fun foo(x?)` means `x: Object?`
- `fun foo(x=3)` means `x: Int`
- `fun foo(x=3?)` means `x: Int?`

Untyped vars and vals:
- `var x` is `Unset`. First assignment fixes its type (including nullability).
- If first assignment is `null`, the type becomes `Object?`.
- `val x = null` is allowed; type is `Null` (practically not useful and cannot be reassigned).
- `var x = null` is allowed; type is `Object?`.

Class-scope val initialization:
- `val x` at class scope is Unset until initialized
- it must be initialized by the constructor or init blocks
- indirect initialization via nested `run {}` or other delayed blocks should be disallowed (unless we add an explicit rule later)


- Implicit runtime checks: When assigning/calling with a declared type, do we insert "as Type" automatically, or require explicit casts? If inserted, what exception is thrown on failure?

We check that it is possible using inference or declared type, and if it _is possible_, and if it _is necessary_ we insert `as Type`:
```lyng
(3 as Int) // no insert, it is Int
val x: Object = // ...
(x as Real) // insert, possible, necessary
((x as Real) as String) // strange but still possible unless Real and Int will be final types (we don't have yet?)
(3 as String) // compile time error, impossible
```
Necessary means: if the compile-time type is fully known and assignable, emit direct assignment. If it is not fully known at compile time but potentially compatible, emit `(x as T)` which can throw `ClassCastException` at runtime.

- Numeric literals: default Int or Real? Is there literal suffix syntax? What about overflow?

as in docs, `3` is `Int`, `3.0` is `Real`. I think it is already implemented properly.

Let's not allow type conversion in `as`: let it only look for a proper `this` in inheritance graph and process nullability. `3.14.toInt()` checks for overflow, but (3.14 as Int) is a compile time error, as Int has no base class Real.

- Union/Intersection runtime checks: If T is inferred from value, are T | U bounds checked at compile time, runtime, or both?

At compile time, checked at call site. If the compile time exchaustive check is possible, we don't emit runtime check. Otherwise, we emit (x as T):
```lyng
// here no checks, any check is at the call site.
fun square<T: Int | Real>(x: T) = x*x

// this one checks at runtime as x is Object, and we have no idea at compile time what it is:
fun f(x) = suqare(x)

// this one checks at compile time only, no runtime checks:
fun f1(x: Int) = square(x)

// Here the compiler checks, but no runtime checks:
f1(100)

// and this is comlipe time error:
square("3.14")
```

- Generics runtime model: Are type params reified via hidden Class args always, or only when used (T::class, T is ...)? How does this interact with Kotlin interop?

Type params are erased by default. Hidden `Class` args are only injected when a type parameter is used in a reified way (`T::class`, `T is`, `is T`, `as T`) or when the class has at least one `extern` symbol (so host implementations can rely on them). Otherwise `T` is compile-time only and runtime uses `Object`.

- Variance syntax:
  - Declaration-site only, Kotlin-style: `out` (covariant) and `in` (contravariant).
  - Example: `class Box<out T>`, `class Sink<in T>`.
  - Bounds remain `T: A & B` or `T: A | B`.

- Member access rules: If a variable is Object (dynamic), is member access a compile-time error, or allowed with fallback (which we are trying to remove)? If error, do we require explicit cast first?

Compile time error unless it is an Object own method. Let's force rewriting existing code in favor of explicit casts. It will repay itself: I laready have a project on Lyng that suffers from implicit casts har to trace errors.

No runtime lookups or fallbacks:
- All symbol and member resolution must be done at compile time.
- If an extension is not known at compile time (not imported or declared before use), it is a compile-time error.
- Runtime lookup is only possible via explicit reflection helpers.

Example:
```lyng
fun f(x) { // x: Object
    x.size() // compile time error
    val s = (x as String).size() // ok
    val l = (x as List).size() // ok
}
```

Object methods:
- remove `inspect` from Object (too valuable a name)
- prefer `toInspectString()` as a reserved method
- keep `toString()` as Object method
- if we need extra metadata later, use explicit helpers like `Object.getHashCode(obj)`

- Builtin classes inheritance: Are Int/String final? If so, is "class T: String, Int" forbidden (and thus Int & String is unsatisfiable but still allowed)?

What keyword we did used for final vals/vars/funs? "closed"? Anyway I am uncertain whether to make Int or String closed, it is a discussion subject. But if we have some closed independent classes A, B, <T: A & B> is a compile time error.

Contextual typing rules (minimal):
1. If a contextual/expected type is known (declared type, parameter type, return position), use it to type literals, `Unset`, and empty collections.
2. Otherwise infer from literal contents:
   - `[]` is `List<Object>` (non-null elements).
   - non-empty list uses union element type with nullability.
   - map literals infer from keys and values.
3. If contextual and literal inference disagree, it is a compile-time error.
4. Declared type is never erased by inference.

List inference:
- Empty list: `[]` is `List<Object>`.
- Non-empty list literal infers union type and nullability:
  - `[1, 2, null]` is `List<Int?>`.
  - `[1, 2, "3"]` is `List<Int|String>`.
Normalize unions by removing duplicates and collapsing nullability (e.g. `Int|Int?` -> `Int?`).

Map inference:
- `{ "a": 1, "b": 2 }` is `Map<String,Int>`.
- Empty map literal uses `{:}` (since `{}` is empty callable).
- `extern class Map<K=String,V=Object>` so `Map()` is `Map<String,Object>()` unless contextual type overrides.

Flow typing:
- Compiler should narrow types based on control-flow (e.g., `if (x != null)` narrows `x` to non-null inside the branch).
- Flow typing is permissive: it only makes types more precise.
- If a branch asserts a type that is impossible (e.g., `x is Int` then `x as String`), it is a compile-time error.

Class-scope val initialization:
- `val` must be initialized either in place or on every execution path in `init`, except paths that explicitly throw.
