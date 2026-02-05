# AI Agent Notes

## Kotlin/Wasm generation guardrails
- Avoid creating suspend lambdas for compiler runtime statements. Prefer explicit `object : Statement()` with `override suspend fun execute(...)`.
- Do not use `statement { ... }` or other inline suspend lambdas in compiler hot paths (e.g., parsing/var declarations, initializer thunks).
- If you need a wrapper for delegated properties, check for `getValue` explicitly and return a concrete `Statement` object when missing; avoid `onNotFoundResult` lambdas.
- If wasmJs browser tests hang, first run `:lynglib:wasmJsNodeTest` and look for wasm compilation errors; hangs usually mean module instantiation failed.
- Do not increase test timeouts to mask wasm generation errors; fix the invalid IR instead.

## Type inference notes (notes/new_lyng_type_system_spec.md)
- Nullability is Kotlin-style: `T` non-null, `T?` nullable, `!!` asserts non-null.
- `void` is a singleton of class `Void` (syntax sugar for return type).
- Object members are always allowed even on unknown types; non-Object members require explicit casts. Remove `inspect` from Object and use `toInspectString()` instead.
- Type expression checks: `x is T` is value instance check; `T1 is T2` is type-subset; `A in T` means `A` is subset of `T`; `==` is structural type equality.
- Do not reintroduce bytecode fallback opcodes (e.g., `GET_NAME`, `EVAL_*`, `CALL_FALLBACK`) or runtime name-resolution fallbacks; all symbol resolution must stay compile-time only.

## Bytecode frame-first migration plan
- Treat frame slots as the only storage for locals/temps by default; avoid pre-creating scope slot mappings for compiled functions.
- Create closure references only when a capture is detected; use a direct frame+slot reference (foreign slot ref) instead of scope slots.
- Keep Scope as a lazy reflection facade: resolve name -> slot only on demand for Kotlin interop (no eager name mapping on every call).
- Avoid PUSH_SCOPE/POP_SCOPE in bytecode for loops/functions unless dynamic name access or Kotlin reflection is requested.
