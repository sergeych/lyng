# Lyng Language AI Specification (V1.2)

High-density specification for LLMs. Reference this for all Lyng code generation.

## 1. Core Philosophy & Syntax
- **Everything is an Expression**: Blocks, `if`, `when`, `for`, `while` return their last expression (or `void`).
- **Implicit Coroutines**: All functions are coroutines. No `async/await`. Use `launch { ... }` (returns `Deferred`) or `flow { ... }`.
- **Variables**: `val` (read-only), `var` (mutable). Supports late-init `val` in classes (must be assigned in `init` or body).
- **Null Safety**: `?` (nullable type), `?.` (safe access), `?( )` (safe invoke), `?{ }` (safe block invoke), `?[ ]` (safe index), `?:` or `??` (elvis), `?=` (assign-if-null).
- **Equality**: `==` (equals), `!=` (not equals), `===` (ref identity), `!==` (ref not identity).
- **Comparison**: `<`, `>`, `<=`, `>=`, `<=>` (shuttle/spaceship, returns -1, 0, 1).
- **Destructuring**: `val [a, b, rest...] = list`. Supports nested `[a, [b, c]]` and splats.

## 2. Object-Oriented Programming (OOP)
- **Multiple Inheritance**: Supported with **C3 MRO** (Python-style). Diamond-safe.
- **Header Arguments**: `class Foo(a, b) : Base(a)` defines fields `a`, `b` and passes `a` to `Base`.
- **Members**: `fun name(args) { ... }`, `val`, `var`, `static val`, `static fun`.
- **Properties (Get/Set)**: Pure accessors, no auto-backing fields.
    ```lyng
    var age
        get() = _age
        private set(v) { if(v >= 0) _age = v }
    // Laconic syntax:
    val area get = π * r * r
    ```
- **Mandatory `override`**: Required for all members existing in the ancestor chain.
- **Visibility**: `public` (default), `protected` (subclasses), `private` (this class instance only). `private set` / `protected set` allowed on properties.
- **Disambiguation**: `this@Base.member()` or `(obj as Base).member()`. `as` returns a qualified view.
- **Abstract/Interface**: `interface` is a synonym for `abstract class`. Both support state and constructors.
- **Extensions**: `fun Class.ext()` or `val Class.ext get = ...`. Scope-isolated.

## 3. Delegation (`by`)
Unified model for `val`, `var`, and `fun`.
```lyng
val x by MyDelegate()
var y by Map() // Uses "y" as key in map
fn f(a, b) by RemoteProxy() // Calls Proxy.invoke(thisRef, "f", a, b)
```
Delegate Methods:
- `getValue(thisRef, name)`: for `val`/`var`.
- `setValue(thisRef, name, val)`: for `var`.
- `invoke(thisRef, name, args...)`: for `fn` (called if `getValue` is absent).
- `bind(name, access, thisRef)`: optional hook called at declaration/binding time. `access` is `DelegateAccess.Val`, `Var`, or `Callable`.

## 4. Standard Library & Functional Built-ins
- **Scope Functions**:
    - `obj.let { it... }`: result of block. `it` is `obj`.
    - `obj.apply { this... }`: returns `obj`. `this` is `obj`.
    - `obj.also { it... }`: returns `obj`. `it` is `obj`.
    - `obj.run { this... }`: result of block. `this` is `obj`.
    - `with(obj, { ... })`: result of block. `this` is `obj`.
- **Functional**: `forEach`, `map`, `filter`, `any`, `all`, `sum`, `count`, `sortedBy`, `flatten`, `flatMap`, `associateBy`.
- **Lazy**: `val x = cached { expensive() }` (call as `x()`) or `val x by lazy { ... }`.
- **Collections**: `List` ( `[a, b]` ), `Map` ( `Map(k => v)` ), `Set` ( `Set(a, b)` ). `MapEntry` ( `k => v` ).

## 5. Patterns & Shorthands
- **Map Literals**: `{ key: value, identifier: }` (identifier shorthand `x:` is `x: x`). Empty map is `Map()`.
- **Named Arguments**: `fun(y: 10, x: 5)`. Shorthand: `Point(x:, y:)`.
- **Varargs & Splats**: `fun f(args...)`, `f(...otherList)`.
- **Labels**: `loop@ for(x in list) { if(x == 0) break@loop }`.
- **Dynamic**: `val d = dynamic { get { name -> ... } }` allows `d.anyName`.

## 6. Operators & Methods to Overload
| Op | Method | Op | Method |
| :--- | :--- | :--- | :--- |
| `+` | `plus` | `==` | `equals` |
| `-` | `minus` | `<=>` | `compareTo` |
| `*` | `mul` | `[]` | `getAt` / `putAt` |
| `/` | `div` | `!` | `logicalNot` |
| `%` | `mod` | `-` | `negate` (unary) |
| `=~` | `operatorMatch` | `+=` | `plusAssign` |

## 7. Common Snippets
```lyng
// Multiple Inheritance and Properties
class Warrior(id, hp) : Character(id), HealthPool(hp) {
    override fun toString() = "Warrior #%s (%s HP)"(id, hp)
}

// Map entry and merging
val m = Map("a" => 1) + ("b" => 2)
m += "c" => 3

// Destructuring with splat
val [first, middle..., last] = [1, 2, 3, 4, 5]

// Safe Navigation and Elvis
val companyName = person?.job?.company?.name ?? "Freelancer"
```
