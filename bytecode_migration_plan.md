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
- [x] Step 16: Remove dead `ToBoolStatement`.
  - [x] Confirm no parser/compiler paths construct `ToBoolStatement` and delete it plus interpreter hooks.
  - [x] Keep JVM tests green after removal.
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
- [x] Step 20: Bytecode support for `NopStatement`.
  - [x] Allow `NopStatement` in `containsUnsupportedForBytecode`.
  - [x] Emit `ObjVoid` directly in bytecode for `NopStatement` in statement/value contexts.
  - [x] Add a JVM test that exercises a code path returning `NopStatement` in bytecode (e.g., static class member decl in class body).
- [x] Step 21: Union mismatch path in bytecode.
  - [x] Replace `UnionTypeMismatchStatement` branch with a bytecode-compilable throw path (no custom `StatementRef` that blocks bytecode).
  - [x] Add a JVM test that forces the union mismatch at runtime and asserts the error message.
- [x] Step 22: Delegated local slots in bytecode.
  - [x] Support reads/writes/assign-ops/inc/dec for delegated locals (`LocalSlotRef.isDelegated`) in `BytecodeCompiler`.
  - [x] Remove `containsDelegatedRefs` guard once delegated locals are bytecode-safe.
  - [x] Add JVM tests that use delegated locals inside bytecode-compiled functions.
- [x] Step 23: Refactor delegated locals to keep delegate objects in frame slots.
  - [x] Add bytecode ops to bind/get/set delegated locals without scope storage.
  - [x] Store delegated locals in frame slots and compile get/set/assign ops with new ops.
  - [x] Preserve reflection facade by syncing delegated locals into scope only when needed.
- [x] Step 24: Remove `ASSIGN_SCOPE_SLOT` now that delegated locals are always frame-backed.
  - [x] Force delegated locals into local slots (even module) and avoid scope-slot resolution.
  - [x] Drop opcode/runtime support for `ASSIGN_SCOPE_SLOT`.

## Frame-Only Execution (new, before interpreter removal)

- [x] Step 24A: Bytecode capture tables for lambdas (compiler only).
  - [x] Emit per-lambda capture tables containing (ownerFrameKind, ownerSlotId).
  - [x] Create captures only when detected; do not allocate scope slots.
  - [x] Add disassembler output for capture tables.
  - [x] JVM tests must be green before commit.
- [x] Step 24B: Frame-slot captures in bytecode runtime.
  - [x] Build lambdas from bytecode + capture table (no capture names).
  - [x] Read captured values via `FrameSlotRef` only.
  - [x] Forbid `resolveCaptureRecord` in bytecode paths; keep only in interpreter.
  - [x] JVM tests must be green before commit.
- [x] Step 24C: Remove scope local mirroring in bytecode execution.
  - [x] Remove/disable any bytecode runtime code that writes locals into Scope for execution.
  - [x] Keep Scope creation only for reflection/Kotlin interop paths.
  - [x] JVM tests must be green before commit.
- [x] Step 24D: Eliminate `ClosureScope` usage on bytecode execution paths.
  - [x] Avoid `ClosureScope` in bytecode-related call paths (Block/Lambda/ObjDynamic/ObjProperty).
  - [x] Keep interpreter path using `ClosureScope` until interpreter removal.
  - [x] JVM tests must be green before commit.
- [x] Step 24E: Isolate interpreter-only capture logic.
  - [x] Mark `resolveCaptureRecord` paths as interpreter-only.
  - [x] Guard or delete any bytecode path that tries to sync captures into scopes.
  - [x] JVM tests must be green before commit.

## Interpreter Removal (next)

- [ ] Step 25: Replace Statement-based declaration calls in bytecode.
  - [x] Add bytecode const/op for enum declarations (no `Statement` objects in constants).
  - [x] Add bytecode const/op for class declarations (no `Statement` objects in constants).
  - [x] Add bytecode const/op for function declarations (no `Statement` objects in constants).
  - [x] Replace `emitStatementCall` usage for `EnumDeclStatement`.
  - [x] Replace `emitStatementCall` usage for `ClassDeclStatement`.
  - [x] Replace `emitStatementCall` usage for `FunctionDeclStatement`.
  - [x] Add JVM disasm coverage to ensure module init has no `CALL_SLOT` to `Callable@...` for declarations.
- [ ] Step 26: Bytecode-backed lambdas (remove `ValueFnRef` runtime execution).
  - [x] Compile lambda bodies to bytecode and emit an opcode to create a callable from bytecode + capture plan.
  - [x] Remove `containsValueFnRef` helper now that lambdas are bytecode-backed.
  - [x] Remove `forceScopeSlots` branches once no bytecode paths depend on scope slots.
  - [x] Add JVM tests for captured locals and delegated locals inside lambdas on the bytecode path.
- [x] Step 27: Remove interpreter opcodes and constants from bytecode runtime.
  - [x] Delete `BytecodeConst.ValueFn`, `CmdMakeValueFn`, and `MAKE_VALUE_FN`.
  - [x] Delete `BytecodeConst.StatementVal`, `CmdEvalStmt`, and `EVAL_STMT`.
  - [x] Add bytecode-backed `::class` via `ClassOperatorRef` + `GET_OBJ_CLASS` to avoid ValueFn for class operator.
  - [x] Add a bytecode fallback reporter hook for lambdas to locate remaining non-bytecode cases.
  - [x] Remove `emitStatementCall`/`emitStatementEval` once unused.
- [ ] Step 28: Scope as facade only.
  - [x] Audit bytecode execution paths for `Statement.execute` usage and remove remaining calls.
  - [x] Keep scope sync only for reflection/Kotlin interop, not for execution.
  - [x] Replace bytecode entry seeding from Scope with frame-only arg/local binding.

## Notes

- Keep imports bound to module frame slots; no scope map writes for imports.
- Avoid inline suspend lambdas in compiler hot paths; use explicit `object : Statement()`.
- Do not reintroduce bytecode fallback opcodes; all symbol resolution remains compile-time only.
