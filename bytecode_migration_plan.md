# Bytecode migration plan (compiler -> frames/bytecode)

This is a step-by-step checklist to track remaining non-bytecode paths in the compiler.
Mark items as you implement them. Priorities are ordered by expected simplicity.

## Priority 1: Quick wins (local changes)
- [ ] Implement bytecode emission for `DelegatedVarDeclStatement`.
  - References: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/bytecode/BytecodeCompiler.kt:3284`, `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Compiler.kt:1667`
- [ ] Implement bytecode emission for `DestructuringVarDeclStatement`.
  - References: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/bytecode/BytecodeCompiler.kt:3285`, `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Compiler.kt:1667`
- [ ] Ensure `ExtensionPropertyDeclStatement` is handled in both value and no-value bytecode paths.
  - References: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/bytecode/BytecodeCompiler.kt:3332`

## Priority 2: Conservative wrapper guards to relax
- [ ] Allow wrapping `BreakStatement` / `ContinueStatement` / `ReturnStatement` where bytecode already supports them.
  - References: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Compiler.kt:1608`
- [ ] Revisit `containsLoopControl` as a hard blocker for wrapping (once label handling is verified).
  - References: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Compiler.kt:1542`

## Priority 3: Medium complexity statements
- [ ] Implement bytecode support for `TryStatement`.
  - References: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Compiler.kt:1698`, `lynglib/src/commonMain/kotlin/net/sergeych/lyng/bytecode/BytecodeCompiler.kt:3290`
- [ ] Expand `WhenStatement` condition coverage (remove "unsupported condition" paths).
  - References: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Compiler.kt:1699`

## Priority 4: Ref-level blockers
- [ ] Support dynamic member access in bytecode (`FieldRef` / `MethodCallRef` on `ObjDynamic`).
  - References: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Compiler.kt:1735`
- [ ] Implement bytecode for qualified/captured member refs:
  - `QualifiedThisMethodSlotCallRef`
  - `QualifiedThisFieldSlotRef`
  - `ClassScopeMemberRef`
  - References: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Compiler.kt:1759`

## Priority 5: Wrapping policy improvements
- [ ] Allow partial script wrapping (wrap supported statements even when others are unsupported).
  - References: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/Compiler.kt:1333`
- [ ] Re-evaluate tooling paths that disable bytecode:
  - `CompileTimeResolution.dryRun` (resolution-only)
  - `LyngLanguageTools.analyze` (diagnostics/mini-ast)
  - References: `lynglib/src/commonMain/kotlin/net/sergeych/lyng/resolution/CompileTimeResolution.kt:67`, `lynglib/src/commonMain/kotlin/net/sergeych/lyng/tools/LyngLanguageTools.kt:97`
