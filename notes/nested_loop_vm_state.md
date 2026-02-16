## Nested loop performance investigation state (2026-02-15)

### Key findings
- Bytecode for `NestedRangeBenchmarkTest` is fully int-local ops; no dynamic lookups or scopes in hot path.
- Loop vars now live directly in local int slots (`n1..n6`), removing per-iteration `MOVE_INT`.
- Per-instruction try/catch in VM was replaced with an outer try/catch loop; on JVM this improved the benchmark.
- Native slowdown is likely dominated by suspend/virtual dispatch overhead in VM, not allocations in int ops.

### Current bytecode shape (naiveCountHappyNumbers)
- Ops: `CONST_INT`, `CMP_GTE_INT`, `INC_INT`, `ADD_INT`, `CMP_EQ_INT`, `JMP*`, `RET`.
- All are `*Local` variants hitting `BytecodeFrame` primitive arrays.

### Changes made
- Loop vars are enforced read-only inside loop bodies at bytecode compile time (reassign, op=, ?=, ++/-- throw).
- Range loops reuse the loop var slot as the counter; no per-iteration move.
- VM loop now uses outer try/catch (no per-op try/catch).
- VM stats instrumentation was added temporarily, then removed for MP safety.

### Files changed
- `lynglib/src/commonMain/kotlin/net/sergeych/lyng/bytecode/BytecodeCompiler.kt`
  - loop var immutability checks
  - loop var slot reuse for range loops
  - skip break/result init when not needed
- `lynglib/src/commonMain/kotlin/net/sergeych/lyng/bytecode/CmdRuntime.kt`
  - outer try/catch VM loop (removed per-op try/catch)
  - stats instrumentation removed
- `lynglib/src/commonTest/kotlin/NestedRangeBenchmarkTest.kt`
  - temporary VM stats debug removed
  - slot dump remains for visibility
- `notes/bytecode_vm_notes.md`
  - note: opcode switch dispatch tested slower than virtual dispatch

### Benchmark snapshots (JVM)
- Before VM loop change: ~96–110 ms.
- With VM stats enabled: ~234–240 ms (stats overhead).
- After VM loop change, stats disabled: ~85 ms.
- 2026-02-15 baseline (fused int-compare jumps): 74 ms.
  - Command: `./gradlew :lynglib:jvmTest -Pbenchmarks=true --tests '*NestedRangeBenchmarkTest*'`
  - Notes: loop range checks use `JMP_IF_GTE_INT` (no CMP+bool temp).
- 2026-02-15 experiment (fast non-suspend cmds in hot path): 57 ms.
  - Command: `./gradlew :lynglib:jvmTest -Pbenchmarks=true --tests '*NestedRangeBenchmarkTest*'`
  - Notes: fast path for local int ops + local JMP_IF_FALSE/TRUE in VM.
- 2026-02-15 experiment (full fast-path sweep + capture-safe locals): 59 ms.
  - Command: `./gradlew :lynglib:jvmTest -Pbenchmarks=true --tests '*NestedRangeBenchmarkTest*'`
  - Notes: local numeric/bool/mixed-compare fast ops gated by non-captured locals.

### Hypothesis for Native slowdown
- Suspend/virtual dispatch per opcode dominates on K/N, even with no allocations in int ops.
- Next idea: a non-suspend fast path for hot opcodes, or a dual-path VM loop.
