# Scopes and Closures: resolution and safety

This page documents how name resolution works with `ClosureScope`, how to avoid recursion pitfalls, and how to safely capture and execute callbacks that need access to outer locals.

## Why this matters
Name lookup across nested scopes and closures can accidentally form recursive resolution paths or hide expected symbols (outer locals, module/global functions). The rules below ensure predictable resolution and prevent infinite recursion.

## Resolution order in ClosureScope
When evaluating an identifier `name` inside a closure, `ClosureScope.get(name)` resolves in this order:

1. Closure frame locals and arguments
2. Captured receiver (`closureScope.thisObj`) instance/class members
3. Closure ancestry locals + each frame’s `thisObj` members (cycle‑safe)
4. Caller `this` members
5. Caller ancestry locals + each frame’s `thisObj` members (cycle‑safe)
6. Module pseudo‑symbols (e.g., `__PACKAGE__`) from the nearest `ModuleScope`
7. Direct module/global fallback (nearest `ModuleScope` and its parent/root scope)
8. Final fallback: base local/parent lookup for the current frame

This preserves intuitive visibility (locals → captured receiver → closure chain → caller members → caller chain → module/root) while preventing infinite recursion between scope types.

## Use raw‑chain helpers for ancestry walks
When authoring new scope types or advanced lookups, avoid calling virtual `get` while walking parents. Instead, use the non‑dispatch helpers on `Scope`:

- `chainLookupIgnoreClosure(name)`
  - Walk raw `parent` chain and check only per‑frame locals/bindings/slots.
  - Ignores overridden `get` (e.g., in `ClosureScope`). Cycle‑safe.
- `chainLookupWithMembers(name)`
  - Like above, but after locals/bindings it also checks each frame’s `thisObj` members.
  - Ignores overridden `get`. Cycle‑safe.
- `baseGetIgnoreClosure(name)`
  - For the current frame only: check locals/bindings, then walk raw parents (locals/bindings), then fallback to this frame’s `thisObj` members.

These helpers avoid ping‑pong recursion and make structural cycles harmless (lookups terminate).

## Preventing structural cycles
- Don’t construct parent chains that can point back to a descendant.
- A debug‑time guard throws if assigning a parent would create a cycle; keep it enabled for development builds.
- Even with a cycle, chain helpers break out via a small `visited` set keyed by `frameId`.

## Capturing lexical environments for callbacks
For dynamic objects or custom builders, capture the creator’s lexical scope so callbacks can see outer locals/parameters:

1. Use `snapshotForClosure()` on the caller scope to capture locals/bindings/slots and parent.
2. Store this snapshot and run callbacks under `ClosureScope(callScope, captured)`.

Kotlin sketch:
```kotlin
val captured = scope.snapshotForClosure()
val execScope = ClosureScope(currentCallScope, captured)
callback.execute(execScope)
```

This ensures expressions like `contractName` used inside dynamic `get { name -> ... }` resolve to outer variables defined at the creation site.

## Closures in coroutines (launch/flow)
- The closure frame still prioritizes its own locals/args.
- Outer locals declared before suspension points remain visible through slot‑aware ancestry lookups.
- Global functions like `delay(ms)` and `yield()` are resolved via module/root fallbacks from within closures.

Tip: If a closure unexpectedly cannot see an outer local, check whether an intermediate runtime helper introduced an extra call frame; the built‑in lookup already traverses caller ancestry, so prefer the standard helpers rather than custom dispatch.

## Local variable references and missing symbols
- Unqualified identifier resolution first prefers locals/bindings/slots before falling back to `this` members.
- If neither locals nor members contain the symbol, missing field lookups map to `SymbolNotFound` (compatibility alias for `SymbolNotDefinedException`).

## Performance notes
- The `visited` sets used for cycle detection are tiny and short‑lived; in typical scripts the overhead is negligible.
- If profiling shows hotspots, consider limiting ancestry depth in your custom helpers or using small fixed arrays instead of hash sets—only for extremely hot code paths.

## Practical Example: `cached`

The `cached` function (defined in `lyng.stdlib`) is a classic example of using closures to maintain state. It wraps a builder into a zero-argument function that computes once and remembers the result:

```kotlin
fun cached(builder) {
    var calculated = false
    var value = null
    { // This lambda captures `calculated`, `value`, and `builder`
        if( !calculated ) {
            value = builder()
            calculated = true
        }
        value
    }
}
```

Because Lyng now correctly isolates closures for each evaluation of a lambda literal, using `cached` inside a class instance works as expected: each instance maintains its own private `calculated` and `value` state, even if they share the same property declaration.

## Dos and Don’ts
- Do use `chainLookupIgnoreClosure` / `chainLookupWithMembers` for ancestry traversals.
- Do maintain the resolution order above for predictable behavior.
- Don’t call virtual `get` while walking parents; it risks recursion across scope types.
- Don’t attach instance scopes to transient/pool frames; bind to a stable parent scope instead.
