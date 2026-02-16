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
5) String ops
   - Extend fast path for string comparisons in hot loops.
6) Box/unbox audit
   - Identify remaining BOX_OBJ / OBJ_TO_* in inner loops and eliminate when safe.
