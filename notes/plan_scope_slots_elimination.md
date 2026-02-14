# Plan: Eliminate Scope Slots for Module + Extensions (Frame-Backed Only)

Goal (1 + 2)
- Module-level vars must be frame-backed just like locals; no special scope storage.
- Extension wrappers must use frame slots as well; no scope-slot storage.
- Scope remains only as a facade for Kotlin extern symbols (reflection/interop), not as primary storage.

Non-Goals (for now)
- Full reflection redesign (set aside).
- Removing scope slots entirely (interop/reflection may still need a facade).

Current Findings (high level)
- BytecodeCompiler still builds scope slots for module symbols and for extension wrappers via pendingScopeNameRefs.
- CmdRuntime reads/writes scope slots whenever slot < scopeSlotCount.
- RESOLVE_SCOPE_SLOT + addr slots are emitted for moving between scope and frame slots.

Design Direction
- Frame slots are the sole storage for module vars and extension wrappers.
- Scope slots are reserved for explicit extern/interop names only (not part of this change).
- Module symbols are captured like any other (closure capture via FrameSlotRef), no module slot plan or scope slot mapping.
- Persist the module frame inside ModuleScope so its lifetime matches the module.

Key Decisions
- Module scope is just another frame in the parent chain; do not seed or persist module vars through scope slots.
- Extension wrappers are treated like ordinary locals/globals in frame slots; no pending scope name refs.
- Compatibility: when `seedScope` is not a `ModuleScope`, module-level declarations still map to scope slots so `Scope.eval(...)` retains cross-call variables.

Work Plan

Phase A: Compiler changes (scope slot creation)
1) Stop adding module declarations to scope slots
   - In `BytecodeCompiler.collectScopeSlots`, when `moduleScopeId` matches a declaration, treat it as a local slot, not a scope slot.
   - Remove `isModuleDecl` special-casing that pushes into `scopeSlotMap`.

2) Remove synthetic scope slots for extension wrappers
   - Gate `pendingScopeNameRefs` (extension callable/getter/setter queueing) behind explicit scope-slot usage.
   - Ensure extension wrappers are resolved via local slot indices only (frame-backed).

3) Tighten scope-slot eligibility
   - `isModuleSlot(...)` should no longer route module names to scope slots.
   - Only names in `allowedScopeNames` / `scopeSlotNameSet` (extern/interop) should become scope slots.

4) ResolveSlot prefers frame slots for everything
   - Update `resolveSlot` to pick local slots for module/extension names.
   - Only return scopeSlotMap entries for explicit extern/interop names.

Phase B: Runtime changes (module frame)
1) Persist the module frame inside ModuleScope
   - Add `ModuleScope.ensureModuleFrame(fn)` that creates/reuses a `BytecodeFrame` with `fn.localCount`.
   - Store local slot metadata on ModuleScope for future interop (names/mutables/delegated).

2) Use the module frame when executing the module bytecode
   - `CmdFrame` should reuse the module frame when `scope0 is ModuleScope` and args are empty.
   - Avoid clobbering module locals with args.

3) Remove module seed path that depends on scope slots
   - Remove `moduleSlotPlan` application in `Script.execute`.
   - Keep `seedImportBindings` and `seedModuleLocals`, but write into the module frame (scopeSlotCount should be 0).

Phase C: Bytecode + ABI considerations
1) No new opcodes required for (1) + (2)
   - If we later need persistence across module reloads, introduce explicit sync opcodes (requires approval).

2) Migration safety
   - Keep existing scope-slot ops for interop only; do not delete opcodes yet.

Detailed Change List (by file)
- `lynglib/src/commonMain/kotlin/net/sergeych/lyng/bytecode/BytecodeCompiler.kt`
  - `collectScopeSlots`: treat module declarations as locals; do not map into `scopeSlotMap`.
  - Remove/guard `pendingScopeNameRefs` queuing for extension wrappers.
  - `isModuleSlot`: stop routing by moduleScopeId; only allow for explicit interop names.
  - `resolveSlot`: prefer local slots; avoid scope slot fallback for module/extension names.

- `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Script.kt`
  - Remove module slot plan application that assumes scope slots.
  - Ensure module vars survive via frame capture rather than scope seeding.

- `lynglib/src/commonMain/kotlin/net/sergeych/lyng/ModuleScope.kt`
  - Add module frame storage + metadata and `ensureModuleFrame(fn)`.

- `lynglib/src/commonMain/kotlin/net/sergeych/lyng/bytecode/CmdRuntime.kt`
  - Use module frame when `scope0 is ModuleScope` in `CmdFrame`.

Testing / Validation
- Disassemble compiled modules: verify no `RESOLVE_SCOPE_SLOT` for module vars or extension wrappers.
- Run `:lynglib:jvmTest` and at least one module script execution that references module vars across calls.
- Verify extension call wrappers still resolve and invoke correctly without scope-slot backing.

Risks / Open Questions
- Module persistence: if module vars were previously “persisted” via scope slots, confirm frame-backed storage matches desired lifetime.
- Extension wrapper lookup: ensure name resolution does not rely on scope slot indexing anywhere else.

Approval Gates
- Any new opcode or runtime sync step must be proposed separately and approved before implementation.
