# Pi Spigot Benchmark Baseline

Date: 2026-04-03
Command:
`./gradlew :lynglib:jvmTest -Pbenchmarks=true --tests 'PiSpigotBenchmarkTest' --rerun-tasks`

Results for `n=200`:
- legacy-real-division: 1108 ms (3 iters, avg 369.33 ms)
- optimized-int-division-rval-off: 756 ms (3 iters, avg 252.00 ms)
- optimized-int-division-rval-on: 674 ms (3 iters, avg 224.67 ms)

Derived speedups:
- intDivSpeedup: 1.47x
- rvalSpeedup: 1.12x
- total: 1.64x

Notes:
- Bytecode still shows generic range iteration (`MAKE_RANGE`, `CALL_MEMBER_SLOT`, `ITER_PUSH`) for loop constructs in the legacy benchmark case.
- This baseline is captured before enabling counted-loop lowering for dynamic inline int ranges.

Optimization #1 follow-up:
- Attempt: broaden compiler loop lowering for dynamic int ranges and validate with `PiSpigotBenchmarkTest` bytecode dumps.
- Final result: success after switching loop-bound coercion to a runtime-checked int path for stable slots with missing metadata.
- Latest measured run after the working compiler change:
  - legacy-real-division: 783 ms (3 iters, avg 261.00 ms)
  - optimized-int-division-rval-off: 729 ms (3 iters, avg 243.00 ms)
  - optimized-int-division-rval-on: 593 ms (3 iters, avg 197.67 ms)
- Hot-op counts for optimized bytecode now show the generic range iterator path is gone from the main loops:
  - `makeRange=0`
  - `callMemberSlot=2`
  - `iterPush=0`
  - `getIndex=4`
  - `setIndex=4`
- The remaining member calls are non-loop overhead; the main improvement came from lowering `for` ranges to counted int loops.

Optimization #2 follow-up:
- Attempt: coerce stable integer operands into `INT` arithmetic during binary-op lowering so hot expressions stop falling back to `OBJ` math.
- Latest measured run after the arithmetic change:
  - legacy-real-division: 593 ms (3 iters, avg 197.67 ms)
  - optimized-int-division-rval-off: 542 ms (3 iters, avg 180.67 ms)
  - optimized-int-division-rval-on: 516 ms (3 iters, avg 172.00 ms)
- Compiled-code impact in the optimized case:
  - `boxes = n * 10 / 3` is now `UNBOX_INT_OBJ` + `MUL_INT` + `DIV_INT`
  - `j = boxes - k` is now `SUB_INT`
  - `denom = j * 2 + 1` is now `MUL_INT` + `ADD_INT`
  - `carriedOver = quotient * j` is now `MUL_INT`
- Remaining hot object arithmetic is centered on list-backed reminder values and derived sums:
  - `reminders[j] * 10`
  - `reminders[j] + carriedOver`
  - `sum / denom`, `sum % denom`, `sum / 10`
- Conclusion: loop lowering is fixed; the next likely win is preserving `List<Int>` element typing for `reminders` so indexed loads stay in int space.

Optimization #3 follow-up:
- Attempt: teach numeric-kind inference that `IndexRef` can be `INT`/`REAL` when the receiver list has a known element class.
- Compiler change:
  - `inferNumericKind()` now handles `IndexRef` and resolves the receiver slot or receiver-declared list element class before choosing `INT`/`REAL`.
- Latest measured run after the indexed-load inference change:
  - legacy-real-division: 656 ms (3 iters, avg 218.67 ms)
  - optimized-int-division-rval-off: 509 ms (3 iters, avg 169.67 ms)
  - optimized-int-division-rval-on: 403 ms (3 iters, avg 134.33 ms)
- Derived speedups vs legacy in this run:
  - intDivSpeedup: 1.29x
  - rvalSpeedup: 1.26x
  - total: 1.63x
- Compiled-code impact in the optimized case:
  - `carriedOver = quotient * j` stays in `INT` space (`ASSERT_IS` + `UNBOX_INT_OBJ` + `MUL_INT`) instead of plain object multiply.
  - Counted int loops remain intact (`MAKE_RANGE=0`, `ITER_PUSH=0`).
- Remaining bottlenecks in the optimized bytecode:
  - `GET_INDEX reminders[j]` still feeds `MUL_OBJ` / `ADD_OBJ`
  - `sum / denom`, `sum % denom`, and `sum / 10` still compile to object arithmetic
  - `suffix += pi[i]` remains `ADD_OBJ`, which is expected because it is string/object concatenation
- Conclusion:
  - The new inference produced a real VM-speed gain, especially with `RVAL_FASTPATH` enabled.
  - The next compiler win is stronger propagation from `List<Int>` indexed loads into the produced temporary slot so `sum` can stay typed as `INT` across the inner loop.

Optimization #4 follow-up:
- Attempt: preserve boxed-argument metadata through `compileCallArgs()` so `list.add(x)` retains `ObjInt` / `ObjReal` element typing.
- Compiler/runtime fixes:
  - `compileCallArgs()` now routes arguments through `ensureObjSlot()` + `emitMove()` instead of raw `BOX_OBJ`, preserving `slotObjClass` and `stableObjSlots`.
  - `CmdSetIndex` now reads `valueSlot` via `slotToObj()` so `SET_INDEX` can safely accept primitive slots.
  - Fast local unbox ops (`CmdUnboxIntObjLocal`, `CmdUnboxRealObjLocal`) now handle already-primitive source slots directly instead of assuming a raw object payload.
  - Plain assignment now coerces object-int RHS back into `INT` when the destination slot is currently compiled as `INT`, keeping loop-carried locals type-consistent.
- Latest measured run after the propagation + VM fixes:
  - legacy-real-division: 438 ms (3 iters, avg 146.00 ms)
  - optimized-int-division-rval-off: 238 ms (3 iters, avg 79.33 ms)
  - optimized-int-division-rval-on: 201 ms (3 iters, avg 67.00 ms)
- Derived speedups vs legacy in this run:
  - intDivSpeedup: 1.84x
  - rvalSpeedup: 1.18x
  - total: 2.18x
- Compiled-code impact in the optimized case:
  - `sum = reminders[j] + carriedOver` is now `GET_INDEX` + `UNBOX_INT_OBJ` + `ADD_INT`
  - `reminders[j] = sum % denom` is now `MOD_INT` + `SET_INDEX`
  - `q = sum / 10` is now `DIV_INT`
  - `carriedOver = quotient * j` is now `MUL_INT`
- Remaining hot object arithmetic in the optimized case:
  - `reminders[j] *= 10` still compiles as `GET_INDEX` + `MUL_OBJ` + `SET_INDEX`
  - `suffix += pi[i]` remains `ADD_OBJ`, which is expected string/object concatenation
- Conclusion:
  - The main remaining arithmetic bottleneck is the compound index assignment path for `reminders[j] *= 10`.
  - The next direct win is to specialize `AssignOpRef` on typed list elements so indexed compound assignment can lower to `UNBOX_INT_OBJ` + `MUL_INT` + boxed `SET_INDEX`.

Optimization #5 follow-up:
- Attempt: specialize typed `IndexRef` compound assignment so `List<Int>` element updates avoid object arithmetic.
- Compiler change:
  - `compileAssignOp()` now detects non-optional typed `List<Int>` index targets and lowers arithmetic assign-ops through `UNBOX_INT_OBJ` + `*_INT` + `SET_INDEX`.
- Latest measured run after the indexed compound-assignment change:
  - legacy-real-division: 394 ms (3 iters, avg 131.33 ms)
  - optimized-int-division-rval-off: 216 ms (3 iters, avg 72.00 ms)
  - optimized-int-division-rval-on: 184 ms (3 iters, avg 61.33 ms)
- Derived speedups vs legacy in this run:
  - intDivSpeedup: 1.82x
  - rvalSpeedup: 1.17x
  - total: 2.14x
- Compiled-code impact in the optimized case:
  - `reminders[j] *= 10` is now:
    - `GET_INDEX`
    - `UNBOX_INT_OBJ`
    - `MUL_INT`
    - `SET_INDEX`
  - The optimized inner loop no longer contains object arithmetic for the `reminders` state update path.
- Remaining hot object work in the optimized case:
  - `suffix += pi[i]` remains `ADD_OBJ` and is expected string/object concatenation
  - The legacy benchmark case still carries real/object work because it intentionally keeps the original `floor(sum / (denom * 1.0))` path
- Conclusion:
  - The inner arithmetic hot loop is now effectively int-lowered end-to-end in the optimized benchmark path.
  - Further wins will likely require reducing list access overhead itself (`GET_INDEX` / `SET_INDEX`) or changing the source algorithm/data layout, not more basic arithmetic lowering.

Optimization #6 follow-up:
- Attempt: move the direct `ObjList` index fast path out from behind `RVAL_FASTPATH` so the common plain-list case is fast by default.
- Runtime change:
  - `CmdGetIndex` and `CmdSetIndex` now always use direct `target.list[index]` / `target.list[index] = value` for exact `ObjList` receivers with `ObjInt` indices.
  - Subclasses such as `ObjObservableList` still use their overridden `getAt` / `putAt` logic, so semantics stay intact.
- Latest measured run after the default plain-list path:
  - legacy-real-division: 397 ms (3 iters, avg 132.33 ms)
  - optimized-int-division-rval-off: 138 ms (3 iters, avg 46.00 ms)
  - optimized-int-division-rval-on: 164 ms (3 iters, avg 54.67 ms)
- Derived speedups vs legacy in this run:
  - intDivSpeedup: 2.88x
  - rvalSpeedup: 0.84x
  - total: 2.42x
- Interpretation:
  - The stable fast baseline is now the `rval-off` case, because the direct plain-`ObjList` path no longer depends on `RVAL_FASTPATH`.
  - `RVAL_FASTPATH` no longer improves this benchmark and only reflects remaining unrelated runtime variance.
- Conclusion:
  - For `piSpigot`, the main VM list-access bottleneck is addressed in the default runtime path.
  - Further work on this benchmark should target algorithm/data-layout changes or string-result construction, not the old `RVAL_FASTPATH` gate.

Remaining optimization candidates:
- `suffix += pi[i]` still compiles as repeated `ADD_OBJ` string/object concatenation.
  - Best next option: build the suffix through a dedicated buffer/list-join path instead of per-iteration concatenation.
- The benchmark still performs many `GET_INDEX` / `SET_INDEX` operations even after the direct plain-`ObjList` fast path.
  - Best next option: reduce indexed access count at the source level or introduce a more specialized typed-list storage layout if this benchmark matters enough.
- The legacy benchmark variant intentionally keeps the real-number `floor(sum / (denom * 1.0))` path.
  - No release optimization needed there; it remains only as a regression/control case.
- `RVAL_FASTPATH` is no longer a useful tuning knob for this workload after the plain-list VM fast path.
  - Best next option: profile other workloads before changing or removing it globally.

Release stabilization note:
- The broad assignment-side `INT` coercion and subclass-bypassing list fast path were rolled back/narrowed to restore correctness across numeric-mix, decimal, list, observable-list, and wasm tests.
- Full release gates now pass:
  - `./gradlew test`
  - `./gradlew :lynglib:wasmJsNodeTest`
- Current release-safe benchmark on the stabilized tree:
  - legacy-real-division: 732 ms (3 iters, avg 244.00 ms)
  - optimized-int-division-rval-off: 545 ms (3 iters, avg 181.67 ms)
  - optimized-int-division-rval-on: 697 ms (3 iters, avg 232.33 ms)
- Interpretation:
  - The release baseline is now `optimized-int-division-rval-off` at 545 ms for the current correct/stable tree.
  - The removed coercion had been masking a real compiler typing gap; reintroducing it broadly is not release-safe.
- Highest-value remaining compiler optimization after release:
  - Recover typed int lowering for `j = boxes - k`, `denom = j * 2 + 1`, `sum = reminders[j] + carriedOver`, and `carriedOver = quotient * j` using a narrower proof than the removed generic arithmetic coercion.
