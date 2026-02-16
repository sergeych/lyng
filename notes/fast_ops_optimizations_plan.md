# Fast Ops Optimizations Plan (Draft)

Baseline
- See `notes/nested_range_baseline.md`

Candidates (not started)
1) Primitive comparisons (done)
   - Emit fast CMP variants for known ObjString/ObjInt/ObjReal using temp/stable slots.
   - MixedCompareBenchmarkTest: 374 ms -> 347 ms.
2) Mixed numeric ops (done)
   - Allow INT+REAL arithmetic to use primitive REAL ops (no obj fallback).
   - MixedCompareBenchmarkTest: 347 ms -> 275 ms.
3) Boolean conversion (done; do not revert without review)
   - Skip redundant OBJ_TO_BOOL in logical AND/OR when compiler already emits BOOL.
   - MixedCompareBenchmarkTest: 275 ms -> 249 ms.
4) Range/loop hot path (done)
   - Reuse a cached ObjVoid slot for if-statements in statement context (avoids per-iteration CONST_OBJ).
   - MixedCompareBenchmarkTest: 249 ms -> 247 ms.
5) String ops (done)
   - Mark GET_INDEX results as stable only for closed ObjString elements to enable fast compares.
   - MixedCompareBenchmarkTest: 247 ms -> 240 ms.
6) Box/unbox audit (done)
   - Unbox ObjInt/ObjReal in assign-op when target is INT/REAL to avoid boxing + obj ops.
   - MixedCompareBenchmarkTest: 240 ms -> 234 ms.
7) Mixed compare coverage
   - Emit CMP_*_REAL when one operand is known ObjReal in more expression forms (not just assign-op).
   - Verify with disassembly that fast cmp opcodes are emitted.
8) Range-loop invariant hoist
   - Cache range end/step into temps once per loop; avoid repeated slot reads/boxing in body.
   - Confirm no extra CONST_OBJ in hot path.
9) Boxing elision pass
   - Remove redundant BOX_OBJ when value feeds only primitive ops afterward (local liveness).
   - Ensure no impact on closures/escaping values.
10) Closed-type fast paths expansion
   - Apply closed-type trust for ObjBool/ObjInt/ObjReal/ObjString in ternaries and conditional chains.
   - Guard with exact non-null temp/slot checks only.
11) VM hot op micro-optimizations
   - Reduce frame reads/writes in ADD_INT, MUL_REAL, CMP_*_INT/REAL when operands are temps.
   - Compare against baseline; revert if regression after 10-run median.
