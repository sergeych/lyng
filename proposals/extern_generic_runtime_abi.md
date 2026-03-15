# Proposal: Runtime Generic Metadata for Extern Classes (Obj-Centric ABI)

Status: Draft  
Date: 2026-03-15  
Owner: compiler/runtime/bridge

## Context

`extern class` declarations are currently allowed to be generic (e.g. `extern class Cell<T>`), and this is already used by stdlib (`Iterable<T>`, `List<T>`, `Map<K,V>`, etc.).

The open problem is exposing applied generic type arguments to Kotlin-side bindings at runtime.

Important constraint:
- Extern-class values are **not necessarily** `ObjInstance`.
- The only hard requirement is that values are `Obj`.

So the ABI design must be `Obj`-centric and must not assume any specific object implementation layout.

## Goals

- Keep `extern class<T>` allowed.
- Make runtime generic metadata collection explicit and opt-in.
- Support all `Obj` subclasses, including host/custom objects not backed by `ObjInstance`.
- Provide a stable Kotlin bridge API for reading applied type args.
- Keep default runtime overhead near zero for classes that do not need this feature.

## Non-goals (phase 1)

- Full reified method-generic metadata for every call site.
- Runtime enforcement of generic bounds from metadata alone.
- Backfilling metadata for pre-existing objects created without capture.

## Proposed ABI Shape

### 1) Opt-in flag on `ObjClass`

Add runtime policy to `ObjClass`:

```kotlin
enum class RuntimeGenericMode {
    None,
    CaptureClassTypeArgs
}

open class ObjClass(...) : Obj() {
    open val runtimeGenericMode: RuntimeGenericMode = RuntimeGenericMode.None
}
```

Default remains `None`.

### 2) Per-object metadata storage owned by `ObjClass`

Store metadata keyed by `Obj` identity, not by instance internals:

```kotlin
open class ObjClass(...) : Obj() {
    open fun setRuntimeTypeArgs(obj: Obj, args: List<RuntimeTypeRef>)
    open fun getRuntimeTypeArgs(obj: Obj): List<RuntimeTypeRef>?
    open fun clearRuntimeTypeArgs(obj: Obj) // optional
}
```

Default implementation:
- class-local identity map keyed by `Obj` references.
- no assumptions about object field layout.
- works for any `Obj` subtype.

Implementation note:
- JVM: weak identity map preferred to avoid leaks.
- JS/Wasm/Native: platform-appropriate identity map strategy; weak where available.

### 3) Type token format

Introduce compact runtime token:

```kotlin
sealed class RuntimeTypeRef {
    data class Simple(val className: String, val nullable: Boolean = false) : RuntimeTypeRef()
    data class Generic(val className: String, val args: List<RuntimeTypeRef>, val nullable: Boolean = false) : RuntimeTypeRef()
    data class Union(val options: List<RuntimeTypeRef>, val nullable: Boolean = false) : RuntimeTypeRef()
    data class Intersection(val options: List<RuntimeTypeRef>, val nullable: Boolean = false) : RuntimeTypeRef()
    data class TypeVar(val name: String, val nullable: Boolean = false) : RuntimeTypeRef()
    data class Unknown(val nullable: Boolean = false) : RuntimeTypeRef()
}
```

`Unknown` is required so arity is preserved even when inference cannot produce a concrete runtime type.

### 4) Compiler/runtime collection point

When constructing/obtaining a value of generic class `C<...>`:
- Resolve applied class type arguments (explicit or inferred).
- If target class has `runtimeGenericMode == CaptureClassTypeArgs`, call:
  - `C.setRuntimeTypeArgs(resultObj, resolvedArgsAsRuntimeTypeRefs)`
- If mode is `None`, do nothing.

No assumption about concrete returned object type besides `Obj`.

### 5) Bridge accessor API

Add a stable helper on binding context/facade:

```kotlin
fun typeArgsOf(obj: Obj, asClass: ObjClass): List<RuntimeTypeRef>?
fun typeArgOf(obj: Obj, asClass: ObjClass, index: Int): RuntimeTypeRef?
```

Semantics:
- Reads metadata stored under `asClass`.
- Returns `null` when absent/not captured.
- Does not throw on non-`ObjInstance` objects.

This allows a host binding for `extern class Cell<T>` to inspect `T` safely.

## Source-Level Policy

No syntax change required in phase 1.

How to enable capture:
- Kotlin host sets `runtimeGenericMode` for classes that need it.
- Optionally add compiler directive later (future work), but not required for MVP.

## ABI Compatibility

- Backward compatible by default (`None` mode does not change behavior).
- Older runtimes can ignore metadata-related calls if feature not used.
- Optional capability flag can be introduced (`GENERIC_RUNTIME_ARGS`) for explicit runtime negotiation.

## Performance Considerations

- Zero overhead for classes with `None`.
- For capture-enabled classes: one map write per object creation/registration event.
- Memory cost proportional to number of live captured objects and type token size.
- Prefer weak-key maps where possible to minimize retention risk.

## Failure Modes and Handling

- If type args cannot be fully resolved at capture point:
  - store `Unknown` tokens in unresolved positions.
- If an object is produced outside normal constructor flow:
  - bridge can call `setRuntimeTypeArgs` manually if needed.
- If map strategy is unavailable on a platform:
  - provide best-effort fallback map and document lifetime caveats.

## Phased Implementation Plan

### Phase 1 (MVP)
- Add `RuntimeGenericMode` on `ObjClass`.
- Add `RuntimeTypeRef`.
- Add `ObjClass` metadata storage/getters.
- Collect class-level generic args for opted-in extern classes.
- Add bridge getters and tests with non-`ObjInstance` `Obj` subtype.

### Phase 2
- Inheritance-aware views (querying args for parent extern classes if needed).
- Better token normalization/canonicalization.

### Phase 3
- Optional method-level generic call metadata (if a concrete use-case appears).

## Test Plan (minimum)

- `extern class Cell<T>` with capture enabled:
  - `Cell<Int>()` exposes `Int`.
  - inferred type args captured where available.
- Same class with capture disabled:
  - no metadata returned.
- Non-`ObjInstance` host object path:
  - metadata can be set/read via `ObjClass` API.
- Unknown/incomplete inference:
  - returned args preserve arity via `Unknown`.

## Open Questions

- Exact cross-platform weak identity map abstraction location.
- Whether to add user-facing Lyng syntax for enabling capture, or keep host-only policy.
- Whether parent-generic projection should be materialized at capture time or computed lazily.

