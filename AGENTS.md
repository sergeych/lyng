# AI Agent Notes

## Canonical AI References
- Use `docs/ai_language_reference.md` as the primary, compiler-verified Lyng language reference for code generation.
- For generics-heavy code generation, follow `docs/ai_language_reference.md` section `7.1 Generics Runtime Model and Bounds` and `7.2 Differences vs Java / Kotlin / Scala`.
- Use `docs/ai_stdlib_reference.md` for default runtime/module APIs and stdlib surface.
- Treat `LYNG_AI_SPEC.md` and older docs as secondary if they conflict with the two files above.
- Prefer the shortest clear loop: use `for` for straightforward iteration/ranges; use `while` only when loop state/condition is irregular or changes in ways `for` cannot express cleanly.

## Lyng-First API Declarations
- Use `.lyng` declarations as the single source of truth for Lyng-facing API docs and types (especially module extern declarations).
- Prefer defining Lyng entities (enums/classes/type shapes) in `.lyng` files; only define them in Kotlin when there is Kotlin/platform-specific implementation detail that cannot be expressed in Lyng.
- Avoid hardcoding Lyng API documentation in Kotlin registrars when it can be declared in `.lyng`; Kotlin-side docs should be fallback/bridge only.
- For mixed pluggable modules (Lyng + Kotlin), embed module `.lyng` sources as generated Kotlin string literals, evaluate them into module scope during registration, then attach Kotlin implementations/bindings.

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
- Type aliases: `type Name = TypeExpr` (generic allowed) expand to their underlying type expressions; no nominal distinctness.
- Bounds and variance: `T: A & B` / `T: A | B` for bounds; declaration-site variance with `out` / `in`.
- Do not reintroduce bytecode fallback opcodes (e.g., `GET_NAME`, `EVAL_*`, `CALL_FALLBACK`) or runtime name-resolution fallbacks; all symbol resolution must stay compile-time only.

## Bytecode frame-first migration plan
- Treat frame slots as the only storage for locals/temps by default; avoid pre-creating scope slot mappings for compiled functions.
- Create closure references only when a capture is detected; use a direct frame+slot reference (foreign slot ref) instead of scope slots.
- Keep Scope as a lazy reflection facade: resolve name -> slot only on demand for Kotlin interop (no eager name mapping on every call).
- Avoid PUSH_SCOPE/POP_SCOPE in bytecode for loops/functions unless dynamic name access or Kotlin reflection is requested.

## ABI proposal notes
- Runtime generic metadata for generic extern classes is tracked in `proposals/extern_generic_runtime_abi.md`.
- Keep this design `Obj`-centric: do not assume extern-class values are `ObjInstance`; collection must be enabled on `ObjClass`.
