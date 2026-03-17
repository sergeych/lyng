# Lyng Language Reference for AI Agents (Current Compiler State)

Purpose: dense, implementation-first reference for generating valid Lyng code.

Primary sources used: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/{Parser,Token,Compiler,Script,TypeDecl}.kt`, `lynglib/stdlib/lyng/root.lyng`, tests in `lynglib/src/commonTest` and `lynglib/src/jvmTest`.

## 1. Ground Rules
- Resolution is compile-time-first. Avoid runtime name/member lookup assumptions.
- `lyng.stdlib` is auto-seeded for normal scripts (default import manager).
- Use explicit casts when receiver type is unknown (`Object`/`Obj`).
- Prefer modern null-safe operators (`?.`, `?:`/`??`, `?=`, `as?`, `!!`).
- Do not rely on fallback opcodes or dynamic member fallback semantics.

## 2. Lexical Syntax
- Comments: `// line`, `/* block */`.
- Strings: `"..."` (supports escapes). Multiline string content is normalized by indentation logic.
- Numbers: `Int` (`123`, `1_000`), `Real` (`1.2`, `1e3`), hex (`0xFF`).
- Char: `'a'`, escaped chars supported.
- Labels:
  - statement label: `loop@ for (...) { ... }`
  - label reference: `break@loop`, `continue@loop`, `return@fnLabel`
- Keywords/tokens include (contextual in many places):
  - declarations: `fun`/`fn`, `val`, `var`, `class`, `object`, `interface`, `enum`, `type`, `init`
  - modifiers: `private`, `protected`, `static`, `abstract`, `closed`, `override`, `extern`, `open`
  - flow: `if`, `else`, `when`, `for`, `while`, `do`, `try`, `catch`, `finally`, `throw`, `return`, `break`, `continue`

## 3. Literals and Core Expressions
- Scalars: `null`, `true`, `false`, `void`.
- List literal: `[a, b, c]`, spreads with `...`.
  - Spread positions: beginning, middle, end are all valid: `[...a]`, `[0, ...a, 4]`, `[head, ...mid, tail]`.
  - Spread source must be a `List` at runtime (non-list spread raises an error).
- Map literal: `{ key: value, x:, ...otherMap }`.
  - `x:` means shorthand `x: x`.
  - Map spread source must be a `Map`.
- Range literals:
  - inclusive: `a..b`
  - exclusive end: `a..<b`
  - open-ended forms are supported (`a..`, `..b`, `..`).
  - optional step: `a..b step 2`
- Lambda literal:
  - with params: `{ x, y -> x + y }`
  - implicit `it`: `{ it + 1 }`
- Ternary conditional is supported: `cond ? thenExpr : elseExpr`.

## 3.1 Splats in Calls and Lambdas
- Declaration-side variadic parameters use ellipsis suffix:
  - functions: `fun f(head, tail...) { ... }`
  - lambdas: `{ x, rest... -> ... }`
- Call-side splats use `...expr` and are expanded by argument kind:
  - positional splat: `f(...[1,2,3])`
  - named splat: `f(...{ a: 1, b: 2 })` (map-style)
- Runtime acceptance for splats:
  - positional splat accepts `List` and general `Iterable` (iterable is converted to list first).
  - named splat accepts `Map` with string keys only.
- Ordering/validation rules (enforced):
  - positional argument cannot follow named arguments (except trailing-block parsing case).
  - positional splat cannot follow named arguments.
  - duplicate named arguments are errors (including duplicates introduced via named splat).
  - unknown named parameters are errors.
  - variadic parameter itself cannot be passed as a named argument (`fun g(args..., tail)` then `g(args: ...)` is invalid).
- Trailing block + named arguments:
  - if the last callable parameter is already provided by name in parentheses, adding a trailing block is invalid.

## 4. Operators (implemented)
- Assignment: `=`, `+=`, `-=`, `*=`, `/=`, `%=`, `?=`.
- Logical: `||`, `&&`, unary `!`.
- Bitwise: `|`, `^`, `&`, `~`, shifts `<<`, `>>`.
- Equality/comparison: `==`, `!=`, `===`, `!==`, `<`, `<=`, `>`, `>=`, `<=>`, `=~`, `!~`.
- Type/containment: `is`, `!is`, `in`, `!in`, `as`, `as?`.
- Null-safe family:
  - member access: `?.`
  - safe index: `?[i]`
  - safe invoke: `?(...)`
  - safe block invoke: `?{ ... }`
  - elvis: `?:` and `??`.
- Increment/decrement: prefix and postfix `++`, `--`.

## 5. Declarations
- Variables:
  - `val` immutable, `var` mutable.
  - top-level/local `val` must be initialized.
  - class `val` may be late-initialized, but must be assigned in class body/init before class parse ends.
  - destructuring declaration: `val [a, b, rest...] = expr`.
  - destructuring declaration details:
    - allowed in `val` and `var` declarations.
    - supports nested patterns: `val [a, [b, c...], d] = rhs`.
    - supports at most one splat (`...`) per pattern level.
    - RHS must be a `List`.
    - without splat: RHS must have at least as many elements as pattern arity.
    - with splat: head/tail elements are bound directly, splat receives a `List`.
- Functions:
  - `fun` and `fn` are equivalent.
  - full body: `fun f(x) { ... }`
  - shorthand: `fun f(x) = expr`.
  - generics: `fun f<T>(x: T): T`.
  - extension functions: `fun Type.name(...) { ... }`.
  - delegated callable: `fun f(...) by delegate`.
- Type aliases:
  - `type Name = TypeExpr`
  - generic: `type Box<T> = List<T>`
  - aliases are expanded structurally.
- Classes/objects/enums/interfaces:
  - `interface` is parsed as abstract class synonym.
  - `object` supports named singleton and anonymous object expression forms.
  - enums support lifted entries: `enum E* { A, B }`.
  - multiple inheritance is supported; override is enforced when overriding base members.
- Properties/accessors in class body:
  - accessor form supports `get`/`set`, including `private set`/`protected set`.

## 6. Control Flow
- `if` is expression-like.
- `when(value) { ... }` supported.
  - branch conditions support equality, `in`, `!in`, `is`, `!is`, and `nullable` predicate.
  - `when { ... }` (subject-less) is currently not implemented.
- Loops: `for`, `while`, `do ... while`.
  - loop `else` blocks are supported.
  - `break value` can return a loop result.
- Exceptions: `try/catch/finally`, `throw`.

## 6.1 Destructuring Assignment (implemented)
- Reassignment form is supported (not only declaration):
  - `[x, y] = [y, x]`
- Semantics match destructuring declaration:
  - nested patterns allowed.
  - at most one splat per pattern level.
  - RHS must be a `List`.
  - too few RHS elements raises runtime error.
- Targets in pattern are variables parsed from identifier patterns.

## 7. Type System (current behavior)
- Non-null by default (`T`), nullable with `T?`.
- `as` (checked cast), `as?` (safe cast returning `null`), `!!` non-null assertion.
- Type expressions support:
  - unions `A | B`
  - intersections `A & B`
  - function types `(A, B)->R` and receiver form `Receiver.(A)->R`
  - variadics in function type via ellipsis (`T...`)
- Generics:
  - type params on classes/functions/type aliases
  - bounds via `:` with union/intersection expressions
  - declaration-site variance via `in` / `out`
- Generic function/class/type syntax examples:
  - function: `fun choose<T>(a: T, b: T): T = a`
  - class: `class Box<T>(val value: T)`
  - alias: `type PairList<T> = List<List<T>>`
- Untyped params default to `Object` (`x`) or `Object?` (`x?` shorthand).
- Untyped `var x` starts as `Unset`; first assignment fixes type tracking in compiler.

## 7.1 Generics Runtime Model and Bounds (AI-critical)
- Lyng generic type information is operational in script execution contexts; do not assume JVM-style full erasure.
- Generic call type arguments can be:
  - explicit at call site (`f<Int>(1)` style),
  - inferred from runtime values/declared arg types,
  - defaulted from type parameter defaults (or `Any` fallback).
- At function execution, generic type parameters are runtime-bound as constants in scope:
  - simple non-null class-like types are bound as `ObjClass`,
  - complex/nullable/union/intersection forms are bound as `ObjTypeExpr`.
- Practical implication for generated code:
  - inside generic code, treat type params as usable type objects in `is`/`in`/type-expression logic (not as purely compile-time placeholders).
  - example pattern: `if (value is T) { ... }`.
- Bound syntax (implemented):
  - intersection bound: `fun f<T: A & B>(x: T) { ... }`
  - union bound: `fun g<T: A | B>(x: T) { ... }`
- Bound checks happen at two points:
  - compile-time call checking for resolvable generic calls,
  - runtime re-check while binding type params for actual invocation.
- Bound satisfaction is currently class-hierarchy based for class-resolvable parts (including union/intersection combination rules).
- Keep expectations realistic:
  - extern-generic runtime ABI for full instance-level generic metadata is still proposal-level (`proposals/extern_generic_runtime_abi.md`), so avoid assuming fully materialized generic-instance metadata everywhere.

## 7.2 Differences vs Java / Kotlin / Scala
- Java:
  - Java generics are erased at runtime (except reflection metadata and raw `Class` tokens).
  - Lyng generic params in script execution are runtime-bound type objects, so generated code can reason about `T` directly.
- Kotlin:
  - Kotlin on JVM is mostly erased; full runtime type access usually needs `inline reified`.
  - Lyng generic function execution binds `T` without requiring an inline/reified escape hatch.
- Scala:
  - Scala has richer static typing but still runs on JVM erasure model unless carrying explicit runtime evidence (`TypeTag`, etc.).
  - Lyng exposes runtime-bound type expressions/classes directly in generic execution scope.
- AI generation rule:
  - do not port JVM-language assumptions like “`T` unavailable at runtime unless reified/tagged”.
  - in Lyng, prefer direct type-expression-driven branching when useful, but avoid assuming extern object generic args are always introspectable today.

## 8. OOP, Members, and Dispatch
- Multiple inheritance with C3-style linearization behavior is implemented in class machinery.
- Disambiguation helpers are supported:
  - qualified this: `this@Base.member()`
  - cast view: `(obj as Base).member()`
- On unknown receiver types, compiler allows only Object-safe members:
  - `toString`, `toInspectString`, `let`, `also`, `apply`, `run`
- Other members require known receiver type or explicit cast.

## 9. Delegation (`by`)
- Works for `val`, `var`, and `fun`.
- Expected delegate hooks in practice:
  - `getValue(thisRef, name)`
  - `setValue(thisRef, name, newValue)`
  - `invoke(thisRef, name, args...)` for delegated callables
  - optional `bind(name, access, thisRef)`
- `@Transient` is recognized for declarations/params and affects serialization/equality behavior.

## 10. Modules and Imports
- `package` and `import module.name` are supported.
- Import form is module-only (no aliasing/selective import syntax in parser).
- Default module ecosystem includes:
  - auto-seeded: `lyng.stdlib`
  - available by import: `lyng.observable`, `lyng.buffer`, `lyng.serialization`, `lyng.time`
  - extra module (when installed): `lyng.io.fs`, `lyng.io.process`

## 11. Current Limitations / Avoid
- No subject-less `when { ... }` yet.
- No regex literal tokenization (`/.../`); use `Regex("...")` or `"...".re`.
- Do not generate runtime name fallback patterns from legacy docs.
