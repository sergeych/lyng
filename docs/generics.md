# Generics and type expressions

This document covers generics, bounds, unions/intersections, and the rules for type expressions in Lyng.

# Generic parameters

Declare type parameters with `<...>` on functions and classes:

    fun id<T>(x: T): T = x
    class Box<T>(val value: T)

Type arguments are usually inferred at call sites:

    val b = Box(10)     // Box<Int>
    val s = id("ok")    // T is String

# Bounds

Use `:` to set bounds. Bounds may be unions (`|`) or intersections (`&`):

    fun sum<T: Int | Real>(x: T, y: T) = x + y
    class Named<T: Iterable & Comparable>(val data: T)

Bounds are checked at compile time. For union bounds, the argument must fit at least one option. For intersection bounds, it must fit all options.

# Variance

Generic types are invariant by default. You can specify declaration-site variance:

    class Source<out T>(val value: T)
    class Sink<in T> { fun accept(x: T) { ... } }

`out` makes the type covariant (produced), `in` makes it contravariant (consumed).

# Type aliases

Type aliases name type expressions (including unions/intersections):

    type Num = Int | Real
    type AB = A & B

Aliases can be generic and can use bounds and defaults:

    type Maybe<T> = T?
    type IntList<T: Int> = List<T>

Aliases expand to their underlying type expressions. They can be used anywhere a type expression is expected.

# Inference rules

- Literals set obvious types (`1` is `Int`, `1.0` is `Real`, etc.).
- Empty list literals default to `List<Object>` unless constrained by context.
- Non-empty list literals infer element type as a union of element types.
- Map literals infer key and value types; named keys are `String`.

Examples:

    val a = [1, 2, 3]             // List<Int>
    val b = [1, "two", true]      // List<Int | String | Bool>
    val c: List<Int> = []         // List<Int>

    val m1 = { "a": 1, "b": 2 }   // Map<String, Int>
    val m2 = { "a": 1, "b": "x" } // Map<String, Int | String>
    val m3 = { ...m1, "c": true } // Map<String, Int | Bool>

Map spreads carry key/value types when possible.

Spreads propagate element type when possible:

    val base = [1, 2]
    val mix = [...base, 3]        // List<Int>

# Type expressions

Type expressions include simple types, generics, unions, and intersections:

    Int
    List<String>
    Int | String
    Iterable & Comparable

These type expressions can appear in casts and `is` checks.

# `is`, `in`, and `==` with type expressions

There are two categories of `is` checks:

1) Value checks: `x is T`
   - `x` is a value, `T` is a type expression.
   - This is a runtime instance check.

2) Type checks: `T1 is T2`
   - both sides are type expressions (class objects or unions/intersections).
   - This is a *type-subset* check: every value of `T1` must fit in `T2`.

Exact type expression equality uses `==` and is structural (union/intersection order does not matter).

Includes checks use `in` with type expressions:

    A in T

This means `A` is a subset of `T` (the same relation as `A is T`).

Examples (T = A | B):

    T == A            // false
    T is A            // false
    A in T            // true
    B in T            // true
    T is A | B        // true

# Nullability checks for types

Use `is nullable` to check whether a type expression accepts `null`:

    T is nullable
    T !is nullable

This works with concrete and generic types:

    fun describe<T>(x: T): String = when (T) {
        nullable -> "nullable"
        else -> "non-null"
    }

Equivalent legacy form:

    null is T

# Practical examples

    fun acceptInts<T: Int>(xs: List<T>) { }
    acceptInts([1, 2, 3])
    // acceptInts([1, "a"]) -> compile-time error

    fun f<T>(list: List<T>) {
        assert( T is Int | String | Bool )
        assert( !(T is Int) )
        assert( Int in T )
    }
    f([1, "two", true])

# Notes

- `T` is reified as a type expression when needed (e.g., union/intersection). When it is a single class, `T` is that class object.
- Type expression checks are compile-time where possible; runtime checks only happen for `is` on values and explicit casts.
