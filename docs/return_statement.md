# The `return` statement

The `return` statement is used to terminate the execution of the innermost enclosing callable (a function or a lambda) and optionally return a value to the caller.

## Basic Usage

By default, Lyng functions and blocks return the value of their last expression. However, `return` allows you to exit early, which is particularly useful for guard clauses.

```lyng
fun divide(a, b) {
    if (b == 0) return null // Guard clause: early exit
    a / b
}
```

If no expression is provided, `return` returns `void`:

```lyng
fun logIfDebug(msg) {
    if (!DEBUG) return
    println("[DEBUG] " + msg)
}
```

## Scoping Rules

In Lyng, `return` always exits the **innermost enclosing callable**. Callables include:
*   Named functions (`fun` or `fn`)
*   Anonymous functions/lambdas (`{ ... }`)

Standard control flow blocks like `if`, `while`, `do`, and `for` are **not** callables; `return` inside these blocks will return from the function or lambda that contains them.

```lyng
fun findFirstPositive(list) {
    list.forEach { 
        if (it > 0) return it // ERROR: This returns from the lambda, not findFirstPositive!
    }
    null
}
```
*Note: To return from an outer scope, use [Non-local Returns](#non-local-returns).*

## Non-local Returns

Lyng supports returning from outer scopes using labels. This is a powerful feature for a closure-intensive language.

### Named Functions as Labels
Every named function automatically provides its name as a label.

```lyng
fun findFirstPositive(list) {
    list.forEach { 
        if (it > 0) return@findFirstPositive it // Returns from findFirstPositive
    }
    null
}
```

### Labeled Lambdas
You can explicitly label a lambda using the `@label` syntax to return from it specifically when nested.

```lyng
val process = @outer { x ->
    val result = {
        if (x < 0) return@outer "negative" // Returns from the outer lambda
        x * 2
    }()
    "Result: " + result
}
```

## Restriction on Shorthand Functions

To maintain Lyng's clean, expression-oriented style, the `return` keyword is **forbidden** in shorthand function definitions (those using `=`).

```lyng
fun square(x) = x * x          // Correct
fun square(x) = return x * x   // Syntax Error: 'return' not allowed here
```

## Summary
*   `return [expression]` exits the innermost `fun` or `{}`.
*   Use `return@label` for non-local returns.
*   Named functions provide automatic labels.
*   Cannot be used in `=` shorthand functions.
*   Consistency: Mirrors the syntax and behavior of `break@label expression`.
