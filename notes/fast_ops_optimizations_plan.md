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
