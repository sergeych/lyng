## Fix: prevent cycles in scope parent chain during pooled frame reuse

### What changed
- Scope.resetForReuse now fully detaches a reused scope from its previous chain/state before re-parenting:
  - sets `parent = null` and regenerates `frameId`
  - clears locals/slots/bindings caches
  - only after that, validates the new parent with `ensureNoCycle` and assigns it
- ScopePool.borrow on all targets (JVM, Android, JS, Native, Wasm) now has a defensive fallback:
  - if `resetForReuse` throws `IllegalStateException` indicating a parent-chain cycle, the pool allocates a fresh `Scope` instead of failing.

### Why
In some nested call patterns (notably instance method calls where an instance is produced by another function and immediately used), the same pooled `Scope` object can be rebound into a chain that already (transitively) contains it. Reassigning `parent` in that case forms a structural cycle, which `ensureNoCycle` correctly detects and throws. This could surface as:

```
IllegalStateException: cycle detected in scope parent chain assignment
  at net.sergeych.lyng.Scope.ensureNoCycle(...)
  at net.sergeych.lyng.Scope.resetForReuse(...)
  at net.sergeych.lyng.ScopePool.borrow(...)
  ... during instance method invocation
```

The fix removes the failure mode by:
1) Detaching the reused frame from its prior chain/state before validating and assigning the new parent.
2) Falling back to a new frame allocation if a cycle is still detected (extremely rare and cheap vs. a crash).

### Expected effects
- Eliminates sporadic `cycle detected in scope parent chain` crashes during instance method invocation.
- No change to public API or normal semantics.
- Pooling remains enabled by default; the fallback only triggers on the detected cycle edge case.
- Negligible performance impact: fresh allocation is used only when a cycle would have crashed the VM previously.

### Notes
- The fix is platform-wide (all ScopePool actuals are covered).
- We recommend adding/keeping a regression test that exercises: a class with a method, a function returning an instance, and an exported function calling the instance method. The test should pass without exceptions.
