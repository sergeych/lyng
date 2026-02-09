# Bytecode Migration Plan

Goal: migrate the compiler so all values live in frames/bytecode, keeping JVM tests green after each step.

## Steps

- [x] Step 1: Module/import slots seeded into module frame; bytecode module resolution works across closures.
- [x] Step 2: Allow implicit/qualified `this` member refs to compile to bytecode.
  - [x] Enable bytecode for `ImplicitThisMethodCallRef`, `QualifiedThisMethodSlotCallRef`, `QualifiedThisFieldSlotRef`.
  - [x] Keep unsupported cases blocked: `ClassScopeMemberRef`, dynamic receivers, delegated members.
  - [x] JVM tests must be green before commit.
- [x] Step 3: Bytecode support for `try/catch/finally`.
  - [x] Implement bytecode emission for try/catch and finally blocks.
  - [x] Preserve existing error/stack semantics.
  - [x] JVM tests must be green before commit.

## Remaining Migration (prioritized)

- [x] Step 4: Allow bytecode wrapping for supported declaration statements.
  - [x] Enable `DestructuringVarDeclStatement` and `ExtensionPropertyDeclStatement` in `containsUnsupportedForBytecode`.
  - [x] Keep JVM tests green before commit.
- [x] Step 5: Enable bytecode for delegated var declarations.
  - [x] Revisit `containsDelegatedRefs` guard for `DelegatedVarDeclStatement`.
  - [x] Ensure delegate binding uses explicit `Statement` objects (no inline suspend lambdas).
  - [x] Keep JVM tests green before commit.
- [x] Step 6: Map literal spread in bytecode.
  - [x] Replace `MapLiteralEntry.Spread` bytecode exception with runtime `putAll`/merge logic.
- [x] Step 7: Class-scope member refs in bytecode.
  - [x] Support `ClassScopeMemberRef` without scope-map fallback.
- [x] Step 8: ObjDynamic member access in bytecode.
  - [x] Allow dynamic receiver field/method lookup without falling back to interpreter.
- [x] Step 9: Module-level bytecode execution.
  - [x] Compile `Script` bodies to bytecode instead of interpreting at module scope.
  - [x] Keep import/module slot seeding in frame-only flow.
- [x] Step 10: Bytecode for declaration statements in module scripts.
  - [x] Support `ClassDeclStatement`, `FunctionDeclStatement`, `EnumDeclStatement` in bytecode compilation.
  - [x] Keep a mixed execution path for declarations (module bytecode calls statement bodies via `CALL_SLOT`).
  - [x] Ensure module object member refs compile as instance access (not class-scope).
- [x] Step 11: Destructuring assignment bytecode.
  - [x] Handle `[a, b] = expr` (AssignRef target `ListLiteralRef`) without interpreter fallback.
- [x] Step 12: Optional member assign-ops and inc/dec in bytecode.
  - [x] Support `a?.b += 1` and `a?.b++` for `FieldRef` targets.
  - [x] Fix post-inc return value for object slots stored in scope frames.
  - [x] Handle optional receivers for member assign-ops and inc/dec without evaluating operands on null.
  - [x] Support class-scope and index optional inc/dec paths in bytecode.
- [x] Step 13: Qualified `this` value refs in bytecode.
  - [x] Compile `QualifiedThisRef` (`this@Type`) via `LOAD_THIS_VARIANT`.
  - [x] Add a JVM test that evaluates `this@Type` as a value inside nested classes.
- [x] Step 14: Fast local ref reads in bytecode.
  - [x] Support `FastLocalVarRef` reads with the same slot resolution as `LocalVarRef`.
  - [x] If `BoundLocalVarRef` is still emitted, map it to a direct slot read instead of failing.
  - [x] Add a JVM test that exercises fast-local reads in a bytecode-compiled function.
- [x] Step 15: Class-scope `?=` in bytecode.
  - [x] Handle `C.x ?= v` and `C?.x ?= v` for class-scope members without falling back.
  - [x] Add a JVM test for class-scope `?=` on static vars.
- [ ] Step 16: Remove dead `ToBoolStatement`.
  - [ ] Confirm no parser/compiler paths construct `ToBoolStatement` and delete it plus interpreter hooks.
  - [ ] Keep JVM tests green after removal.
- [x] Step 17: Callable property calls in bytecode.
  - [x] Support `CallRef` where the target is a `FieldRef` (e.g., `(obj.fn)()`), keeping compile-time resolution.
  - [x] Add a JVM test for a callable property call compiled to bytecode.
- [x] Step 18: Delegated member access in bytecode.
  - [x] Remove `containsDelegatedRefs` guard once bytecode emits delegated get/set/call correctly.
  - [x] Add JVM coverage for delegated member get/set/call in bytecode.
- [x] Step 19: Unknown receiver member access in bytecode.
  - [x] Reject Object/unknown receiver member calls without explicit cast or Dynamic.
  - [x] Add union-member dispatch with ordered type checks and runtime mismatch error.
  - [x] Add JVM tests for unknown receiver and union member access.

## Notes

- Keep imports bound to module frame slots; no scope map writes for imports.
- Avoid inline suspend lambdas in compiler hot paths; use explicit `object : Statement()`.
- Do not reintroduce bytecode fallback opcodes; all symbol resolution remains compile-time only.
