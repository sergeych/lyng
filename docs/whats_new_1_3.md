# What's New in Lyng 1.3 (vs 1.2.* / master)

This is a programmer-focused summary of what changed since the 1.2.* line on `master`. It highlights new language and type-system features, runtime/IDE improvements, and how to migrate code safely.

## Highlights

- Generics are now a first-class part of the type system, with bounds, variance, unions, and intersections.
- Type aliases and type-expression checks (`T1 is T2`, `A in T`) enable richer static modeling.
- Nested declarations inside classes, plus lifted enum entries via `enum E*`.
- Stepped ranges (`step`) including iterable open-ended and real ranges.
- Runtime and compiler speedups: more bytecode coverage, direct slot access, call-site caching.

## Language and type system

### Generics, bounds, and variance

You can declare generic functions/classes with `<...>`, restrict them with bounds, and control variance.

```lyng
fun id<T>(x: T): T = x
class Box<out T>(val value: T)

fun sum<T: Int | Real>(x: T, y: T) = x + y
class Named<T: Iterable & Comparable>(val data: T)
```

### Type aliases and type expressions

Type aliases can name any type expression, including unions and intersections.

```lyng
type Num = Int | Real
type Maybe<T> = T?
```

Type expressions can be checked directly:

```lyng
fun f<T>(xs: List<T>) {
    assert( T is Int | String | Bool ) // type-subset check
    assert( Int in T )                 // same relation as `Int is T`
}
```

Value checks remain `x is T`. Type expression equality uses `==` and is structural.

### Nullable shorthand for parameters

Untyped parameters and constructor args can use `x?` as shorthand for `x: Object?`:

```lyng
class A(x?) { ... }
fun f(x?) { x == null }
```

### List/map literal inference

The compiler now infers element and key/value types from literals and spreads. Mixed element types produce unions.

```lyng
val a = [1, 2, 3]             // List<Int>
val b = [1, "two", true]      // List<Int | String | Bool>
val m = { "a": 1, "b": "x" } // Map<String, Int | String>
```

### Compile-time member access only

Member access is resolved at compile time. On unknown types, only `Object` members are visible; other members require an explicit cast.

```lyng
fun f(x) {          // x: Object
    x.toString()    // ok
    x.size()        // compile-time error
    (x as List).size()
}
```

This removes runtime name-resolution fallbacks and makes errors deterministic.

### Nested declarations and lifted enums

Classes, objects, enums, and type aliases can be declared inside another class and accessed by qualifier. Enums can lift entries into the outer namespace with `*`.

```lyng
class A {
    class B(x?)
    object Inner { val foo = "bar" }
    type Alias = B
    enum E* { One, Two }
}

val b = A.B()
assertEquals(A.One, A.E.One)
```

### Stepped ranges

Ranges now support `step`, and open-ended/real ranges are iterable only with an explicit step.

```lyng
(1..5 step 2).toList()      // [1,3,5]
(0.0..1.0 step 0.25).toList() // [0,0.25,0.5,0.75,1.0]
(0.. step 1).take(3).toList() // [0,1,2]
```

## Tooling and performance

- Bytecode compiler/VM coverage expanded (loops, expressions, calls), improving execution speed and consistency.
- Direct frame-slot access and scoped slot addressing reduce lookup overhead, including in closures.
- Call-site caching and numeric fast paths reduce hot-loop overhead.
- IDE tooling updated for the new type system and nested declarations; MiniAst-based completion work continues.

## Migration guide (from 1.2.*)

1) Replace dynamic member access on unknown types
- If you relied on runtime name resolution, add explicit casts or annotate types so the compiler can resolve members.

2) Adopt new type-system constructs where helpful
- Consider `type` aliases for complex unions/intersections.
- Prefer generic signatures over `Object` when the API is parametric.

3) Update range iteration where needed
- Use `step` for open-ended or real ranges you want to iterate.

4) Nullable shorthand is optional
- If you used untyped nullable params, you can keep `x` (Object) or switch to `x?` (Object?) for clarity.

## References

- `docs/generics.md`
- `docs/Range.md`
- `docs/OOP.md`
- `docs/BytecodeSpec.md`
