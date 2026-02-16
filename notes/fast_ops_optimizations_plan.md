# Fast Ops Optimizations Plan (Draft)

Baseline
- See `notes/nested_range_baseline.md`

Candidates (not started)
1) Primitive comparisons
   - Emit fast CMP variants for known ObjString/ObjInt/ObjReal/ObjBool across all comparison operators.
2) Mixed numeric ops
   - Ensure INT+REAL arithmetic uses INT_TO_REAL + REAL op with no extra moves/boxing.
3) Boolean conversion
   - Skip OBJ_TO_BOOL when compiler already has a BOOL slot.
4) Range/loop hot path
   - Keep range iteration in INT ops, avoid accidental boxing.
   - Confirm loop-var slots avoid re-boxing in loop bodies.
5) String ops
   - Extend fast path for string comparisons in hot loops.
6) Box/unbox audit
   - Identify remaining BOX_OBJ / OBJ_TO_* in inner loops and eliminate when safe.
