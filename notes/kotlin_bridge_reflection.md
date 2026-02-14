# Kotlin Bridge Reflection Facade (Handles)

This note documents the Kotlin-side reflection facade used by extern bindings to access Lyng values efficiently.

## Overview

- `ScopeFacade.resolver()` returns a `BridgeResolver` bound to the current call scope.
- Resolution returns stable handles (val/var/callable/member) that avoid repeated name lookup.
- Handles are safe across calls; they re-resolve automatically when the frame changes.
- Call-by-name is available via `BridgeCallByName` for quick cached calls.

## Example: locals + functions

```kotlin
val facade = scope.asFacade()
val resolver = facade.resolver()

val x = resolver.resolveVar("x")
val add = resolver.resolveCallable("add")

val a = x.get(facade) as ObjInt
x.set(facade, ObjInt.of(a.value + 1))

val res = add.call(facade, Arguments(ObjInt.of(2), ObjInt.of(3))) as ObjInt
```

## Example: member handles

```kotlin
val f = scope["f"]!!.value as ObjInstance
val count = resolver.resolveMemberVar(f, "count")
val bump = resolver.resolveMemberCallable(f, "bump")

count.set(facade, ObjInt.of(10))
bump.call(facade)
```

## Notes

- Visibility rules are enforced exactly as in Lyng.
- `resolveExtensionCallable` treats extensions as member callables using wrapper names.
- `selfAs(type)` creates a receiver view (this@Base) when resolving members on the current `this`.
