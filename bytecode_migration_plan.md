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
- [ ] Step 9: Module-level bytecode execution.
  - [ ] Compile `Script` bodies to bytecode instead of interpreting at module scope.
  - [ ] Keep import/module slot seeding in frame-only flow.

## Notes

- Keep imports bound to module frame slots; no scope map writes for imports.
- Avoid inline suspend lambdas in compiler hot paths; use explicit `object : Statement()`.
- Do not reintroduce bytecode fallback opcodes; all symbol resolution remains compile-time only.
