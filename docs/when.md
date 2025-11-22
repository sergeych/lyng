# The `when` statement (expression)

Lyng provides a concise multi-branch selection with `when`, heavily inspired by Kotlin. In Lyng, `when` is an expression: it evaluates to a value. If the selected branch contains no value (e.g., it ends with `void` or calls a void function like `println`), the whole `when` expression evaluates to `void`.

Currently, Lyng implements the "subject" form: `when(value) { ... }`. The subject-less form `when { condition -> ... }` is not implemented yet.

## Quick examples

    val r1 = when("a") {
        "a" -> "ok"
        else -> "nope"
    }
    assertEquals("ok", r1)
    
    val r2 = when(5) {
        3 -> "no"
        4 -> "no"
        else -> "five"
    }
    assertEquals("five", r2)
    
    val r3 = when(5) {
        3 -> "no"
        4 -> "no"
    }
    // no matching case and no else → `void`
    assert(r3 == void)
    >>> void

## Syntax

    when(subject) {
        condition1 [, condition2, ...] -> resultExpressionOrBlock
        conditionN -> result
        else -> fallback
    }

- Commas group multiple conditions for one branch.
- First matching branch wins; there is no fall‑through.
- `else` is optional. If omitted and nothing matches, the result is `void`.

## Matching rules (conditions)

Within `when(subject)`, each condition is evaluated against the already evaluated `subject`. Lyng supports:

1) Equality match (default)
- Any expression value can be used as a condition. It matches if it is equal to `subject`.
- Equality relies on Lyng’s comparison (`compareTo(...) == 0`). For user types, implement comparison accordingly.

    when(x) {
        0 -> "zero"
        "EUR" -> "currency"
    }
    >>> void

2) Type checks: `is` and `!is`
- Check whether the subject is an instance of a class.
- Works with built‑in classes and user classes.

    fun typeOf(x) {
        when(x) {
            is Real, is Int -> "number"
            is String -> "string"
            else -> "other"
        }
    }
    assertEquals("number", typeOf(5))
    assertEquals("string", typeOf("hi"))
    >>> void

3) Containment checks: `in` and `!in`
- `in container` matches if `container.contains(subject)` is true.
- `!in container` matches if `contains(subject)` is false.
- Any object that provides `contains(item)` can act as a container.

Common containers:
- Ranges (e.g., `'a'..'z'`, `1..10`, `1..<10`, `..5`, `5..`)
- Lists, Sets, Arrays, Buffers
- Strings (character or substring containment)

Examples:

    when('e') {
        in 'a'..'c' -> "small"
        in 'a'..'z' -> "letter"
        else -> "other"
    }
    >>> "letter"
    
    when(5) {
        in [1,2,3,4,6] -> "no"
        in [7,0,9] -> "no"
        else -> "ok"
    }
    >>> "ok"
    
    when(5) {
        in [1,2,3,4,6] -> "no"
        in [7,0,9] -> "no"
        in [-1,5,11] -> "yes"
        else -> "no"
    }
    >>> "yes"
    
    when(5) {
        !in [1,2,3,4,6,5] -> "no"
        !in [7,0,9,5] -> "no"
        !in [-1,15,11] -> "ok"
        else -> "no"
    }
    >>> "ok"
    
    // String containment
    "foo" in "foobar"   // true (substring)
    'o' in "foobar"      // true (character)
    >>> true

Notes on mixed String/Char ranges:
- Prefer character ranges for characters: `'a'..'z'`.
- `"a".."z"` is a String range and may not behave as you expect with `Char` subjects.

    assert( "more" in "a".."z")
    assert( 'x' !in "a".."z")  // Char vs String range: often not what you want
    assert( 'x' in 'a'..'z')     // OK
    assert( "x" !in 'a'..'z')   // String in Char range: likely not intended
    >>> void

## Grouping multiple conditions with commas

You can group values and/or `is`/`in` checks for a single result:

    fun classify(x) {
        when(x) {
            "42", 42 -> "answer"
            is Real, is Int -> "number"
            in ['@', '#', '^'] -> "punct1"
            in "*&.," -> "punct2"
            else -> "unknown"
        }
    }
    assertEquals("number", classify(π/2))
    assertEquals("answer", classify(42))
    assertEquals("answer", classify("42"))
    >>> void

## Return value and blocks

- `when` returns the value of the matched branch result expression/block.
- Branch bodies can be single expressions or blocks `{ ... }`.
- If a matched branch produces `void` (e.g., only prints), the `when` result is `void`.

    val res = when(2) {
        1 -> 10
        2 -> { println("two"); 20 }
        else -> 0
    }
    assertEquals(20, res)
    >>> void

## Else branch

- Optional but recommended when non‑exhaustive.
- If omitted and nothing matches, `when` result is `void` (see r3 in the Quick examples).
- Only one `else` is allowed.

## Subject‑less `when`

The Kotlin‑style subject‑less form `when { condition -> ... }` is not implemented yet in Lyng. Use `if/else` chains or structure your checks around a subject with `when(subject) { ... }`.

## Extending `when` for your own types

### Equality matches
- Equality checks in `when(subject)` use Lyng comparison (`compareTo` semantics under the hood). For your own Lyng classes, implement comparison appropriately so that `subject == value` works as intended.

### `in` / `!in` containment
- Provide a `contains(item)` method on your class to participate in `in` conditions.
- Example: a custom `Box` that contains one specific item:

    class Box(val item)
    fun Box.contains(x) { x == item }
    
    val b = Box(10)
    when(10) { in b -> "hit" }
    >>> "hit"

Any built‑in collection (`List`, `Set`, `Array`), `Range`, `Buffer`, and other containers already implement `contains`.

### Type checks (`is` / `!is`)
- Every value has a `::class` that yields its Lyng class object, e.g. `[1,2,3]::class` → `List`.
- `is ClassName` in `when` uses Lyng’s class hierarchy. Ensure your class is declared and can be referenced by name.

    []::class == List
    >>> true
    
    fun f(x) { when(x) { is List -> "list" else -> "other" } }
    assertEquals("list", f([1]))
    >>> void

## Kotlin‑backed classes (embedding)

When embedding Lyng in Kotlin, you may expose Kotlin‑backed objects and classes. Interactions inside `when` work as follows:
- `is` checks use the Lyng class object you expose for your Kotlin type. Ensure your exposed class participates in the Lyng class hierarchy (see Embedding docs).
- `in` checks call `contains(subject)`; if your Kotlin‑backed object wants to support `in`, expose a `contains(item)` method (mapped to Lyng) or implement the corresponding Lyng container wrapper.
- Equality follows Lyng comparison rules. Ensure your Kotlin‑backed object’s Lyng adapter implements equality/compare correctly.

For details on exposing classes/methods from Kotlin, see: [Embedding Lyng in your Kotlin project](embedding.md).

## Gotchas and tips

- First match wins; there is no fall‑through. Order branches carefully.
- Group related conditions with commas for readability and performance (a single branch evaluation).
- Prefer character ranges for character tests; avoid mixing `String` and `Char` ranges.
- If you rely on `in`, check that your container implements `contains(item)`.
- Remember: `when` is an expression — you can assign its result to a variable or return it from a function.

## Additional examples

    fun label(ch) {
        when(ch) {
            in '0'..'9' -> "digit"
            in 'a'..'z', in 'A'..'Z' -> "letter"
            '$' -> "dollar"
            else -> "other"
        }
    }
    assertEquals("digit", label('3'))
    assertEquals("dollar", label('$'))
    >>> void

    fun normalize(x) {
        when(x) {
            is Int -> x
            is Real -> x.round()
            else -> 0
        }
    }
    assertEquals(12, normalize(12))
    assertEquals(3,  normalize(2.6))
    >>> void
