# Bytecode Migration Plan

Goal: migrate the compiler so all values live in frames/bytecode, keeping JVM tests green after each step.

## Steps

- [x] Step 1: Module/import slots seeded into module frame; bytecode module resolution works across closures.
- [x] Step 2: Allow implicit/qualified `this` member refs to compile to bytecode.
  - [x] Enable bytecode for `ImplicitThisMethodCallRef`, `QualifiedThisMethodSlotCallRef`, `QualifiedThisFieldSlotRef`.
  - [x] Keep unsupported cases blocked: `ClassScopeMemberRef`, dynamic receivers, delegated members.
  - [x] JVM tests must be green before commit.
- [ ] Step 3: Bytecode support for `try/catch/finally`.
  - [ ] Implement bytecode emission for try/catch and finally blocks.
  - [ ] Preserve existing error/stack semantics.
  - [ ] JVM tests must be green before commit.

## Notes

- Keep imports bound to module frame slots; no scope map writes for imports.
- Avoid inline suspend lambdas in compiler hot paths; use explicit `object : Statement()`.
- Do not reintroduce bytecode fallback opcodes; all symbol resolution remains compile-time only.
