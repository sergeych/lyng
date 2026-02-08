# Bytecode Migration Plan

Goal: migrate :lynglib compiler/runtime so values live in frame slots and bytecode is the default execution path.

## Step 1: Imports as module slots (done)
- [x] Seed module slot plans from import bindings (lazy, unused imports do not allocate).
- [x] Avoid mutating scopes during compile-time imports; bind slots at runtime instead.
- [x] Make runtime member access honor extensions (methods + properties).
- [x] Ensure class members (ObjClass instances) resolve by slot id in bytecode runtime.
- [x] Expose `Iterator` in root scope so stdlib externs bind at runtime.

## Step 2: Class-scope member refs + qualified-this refs (pending)
- [ ] Bytecode-compile `ClassScopeMemberRef` (currently forced to AST in `Compiler.containsUnsupportedRef`).
- [ ] Bytecode-compile `QualifiedThisFieldSlotRef` / `QualifiedThisMethodSlotCallRef`.
- [ ] Ensure slot resolution uses class member ids, not scope lookup; no fallback opcodes.
- [ ] Add coverage for class static access + qualified-this access to keep JVM tests green.

## Step 3: Expand bytecode coverage for control flow + literals (pending)
- [ ] Add bytecode support for `TryStatement` (catch/finally) in `Compiler.containsUnsupportedForBytecode` and `BytecodeCompiler`.
- [ ] Support `WhenStatement` conditions beyond the current limited set.
- [ ] Add map literal spread support (currently throws in `BytecodeCompiler`).
- [ ] Remove remaining `BytecodeCompileException` cases for common member access (missing id paths).

## Known bytecode gaps (from current guards)
- [ ] `TryStatement` is always excluded by `Compiler.containsUnsupportedForBytecode`.
- [ ] `ClassScopeMemberRef` and qualified-this refs are excluded by `Compiler.containsUnsupportedRef`.
- [ ] `BytecodeCompiler` rejects map literal spreads and some argument expressions.
- [ ] Member access still fails when compile-time receiver class cannot be resolved.

## Validation
- [ ] `./gradlew :lynglib:jvmTest` (full suite) after each step; if failures pre-exist, run targeted tests tied to the change and record the gap in this file.
- [ ] Baseline full suite: currently 46 failures on `:lynglib:jvmTest` (run 2026-02-08); keep targeted tests green until the baseline is addressed.
