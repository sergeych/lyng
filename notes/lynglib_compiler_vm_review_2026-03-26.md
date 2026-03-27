# :lynglib Compiler and VM Review (2026-03-26)

## Scope
- Reviewed `:lynglib` compiler and bytecode VM paths, focusing on compile/execution correctness.
- Read core files around `Compiler`, `Script`, `BytecodeCompiler`, `CmdRuntime`, `Scope`, `ModuleScope`, and capture/slot plumbing.
- Ran `./gradlew :lynglib:jvmTest` on 2026-03-26: PASS.

## Findings

### 1. High: local seeding uses the wrong slot index when checking for self-referential `FrameSlotRef`
- Status: fixed in worktree on 2026-03-26; covered by `SeedLocalsRegressionTest`.
- File: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/bytecode/SeedLocals.kt:39`
- The self-reference guard compares `FrameSlotRef` against `base + i`, but `FrameSlotRef` stores the underlying `BytecodeFrame` local slot index, not the VM absolute slot id.
- The same slot is then written using `setObjUnchecked(base + i, value)` at `SeedLocals.kt:42`, which means a self-reference is not filtered out before being reinserted.
- Impact: this can seed a local with a reference to itself, creating recursive `FrameSlotRef` chains. Reads then recurse through `BytecodeFrame.getObj()` / `FrameSlotRef.read()` instead of resolving to a value, which is a realistic path to stack overflow or non-terminating execution in bytecode-only helper execution (`executeBytecodeWithSeed`).
- Why this looks real: the equivalent check in `CmdFrame.applyCaptureRecords()` uses the local index directly (`CmdRuntime.kt:3867`), and `Script.seedModuleLocals()` also compares with the local index, not `scopeSlotCount + i` (`Script.kt:109`).
- Suggested fix: compare with `i`, not `base + i`, and add a regression test around `executeBytecodeWithSeed` seeding a scope that already contains a `FrameSlotRef` to the same local.

### 2. Design note: script-specific import/module preparation should stay explicit, not hidden in `execute(scope)`
- Status: resolved by API addition, without changing `Script.execute(scope)` semantics.
- File: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Script.kt:51-57`
- `shouldSeedModule` is only true when `execScope` is a `ModuleScope` or `thisObj === ObjVoid`.
- If a compiled script is executed against a non-module scope with a real receiver object, `seedModuleSlots()` is skipped entirely, so script imports and explicit module bindings are never installed into `moduleTarget`.
- Original concern: top-level bytecode that expects imported names or module globals can see different behavior depending on whether the host prepared the target scope explicitly.
- Resolution taken: do **not** change `Script.execute(scope)`, because in this codebase it is expected to run on exactly the provided scope and many embedding flows already rely on explicit scope setup via `Script.newScope()`, `Scope.eval(...)`, `addFn(...)`, `addConst(...)`, and direct import-manager configuration.
- New explicit APIs added instead:
  - `Script.importInto(scope, seedScope = scope)`
  - `Script.instantiateModule(seedScope = null, pos = script.pos)`
- This keeps existing execution behavior stable while giving hosts an opt-in way to apply a script's import/module bindings when they actually want that preparation step.
- Coverage added: `ScriptImportPreparationTest`.

### 3. Medium: missing module captures are silently converted into fresh `Unset` slots
- Status: fixed in worktree on 2026-03-27; covered by `CompilerVmReviewRegressionTest`.
- File: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/bytecode/CmdRuntime.kt:3950-3970`
- In `CmdFrame.buildCaptureRecords()`, the module-capture path first tries several lookups. If the requested `slotId` is missing but the capture has a `name`, it calls `target.applySlotPlan(mapOf(name to slotId))` and immediately returns `target.getSlotRecord(slotId)`.
- That record is a newly created placeholder from `Scope.applySlotPlan()` (`Scope.kt:460-471`) and defaults to `ObjUnset`.
- Impact: a compiler/runtime disagreement in capture resolution is masked as a normal capture of `Unset`, so the failure moves far away from closure creation and becomes data corruption or an unrelated later exception. This will be difficult to debug when it happens.
- Resolution taken: the VM now refuses to treat synthetic placeholder slots as real module/captured bindings. Reads and capture construction fail with a source-positioned `ScriptError` when the execution scope was not prepared with the script's required module/import bindings.

### 4. Medium: subject-less `when { ... }` still crashes through a raw Kotlin `TODO`
- Status: fixed in worktree on 2026-03-27; covered by `CompilerVmReviewRegressionTest`.
- File: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Compiler.kt:6447-6449`
- The unsupported branch uses `TODO("when without object is not yet implemented")`.
- Current docs explicitly say subject-less `when` is not implemented, so the language limitation itself is documented. The problem is the failure mode: the compiler throws a raw Kotlin `NotImplementedError` instead of a normal `ScriptError` or a feature diagnostic.
- Impact: IDE/embedding callers get an implementation exception rather than a source-positioned language error, which is especially bad across non-JVM targets and for editor tooling.
- Resolution taken: the parser now throws a normal `ScriptError` at the `when` position with an explicit "when without subject is not implemented" message.

## Risks Worth Checking Next

### 5. Medium risk: module frame growth only retargets records stored in `objects`/`localBindings`
- File: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/ModuleScope.kt:39-67`
- When `ensureModuleFrame()` replaces the old `BytecodeFrame` with a larger one, it retargets `FrameSlotRef`s only in `objects` and `localBindings`.
- Existing closures/capture records that already hold `FrameSlotRef(oldFrame, slot)` are not retargeted here.
- Impact: if a module scope survives across recompilation/re-execution with a larger local frame, previously exported closures can keep reading stale values from the old frame instance.
- Confidence is lower because this depends on module reuse across differing compiled shapes, but the reference-retargeting logic is clearly incomplete for that scenario.
- Suggested check: add a regression covering module re-execution with increased local count and an exported closure captured before the resize.

## Test Status
- `./gradlew :lynglib:jvmTest` passed during this review.
- `./gradlew :lynglib:jvmTest --tests ScriptImportPreparationTest --tests SeedLocalsRegressionTest` passed after the fixes/API additions.
- `./gradlew :lynglib:jvmTest --tests CompilerVmReviewRegressionTest --tests ScriptImportPreparationTest --tests SeedLocalsRegressionTest` passed after fixing findings 3 and 4.
- Finding 1 is covered directly; finding 2 is covered by explicit preparation API tests.

## Suggested Fix Order
1. Fix finding 1 first: it is a concrete slot-index bug with likely recursive failure modes. Done.
2. Keep `Script.execute(scope)` semantics stable and use explicit preparation APIs where script-owned import/module setup is needed. Done.
3. Tighten finding 3 next: fail fast on capture mismatches. Done.
4. Replace the raw `TODO` in finding 4 so unsupported syntax produces normal diagnostics. Done.
5. Decide whether finding 5 matters for current module-reload workflows; add a regression before changing behavior.
