## Pi Spigot JVM Baseline

Saved on April 4, 2026 before the `List<Int>` indexed-access follow-up fix.

Benchmark target:
- [examples/pi-bench.py](/home/sergeych/dev/lyng/examples/pi-bench.py)
- [examples/pi-bench.lyng](../examples/pi-bench.lyng)

Execution path:
- Python: `python3 examples/pi-bench.py`
- Lyng JVM: `./gradlew :lyng:runJvm --args='/home/sergeych/dev/lyng/examples/pi-bench.lyng'`
- Constraint: do not use Kotlin/Native `lyng` CLI for perf comparisons

Baseline measurements:
- Python full script: `167 ms`
- Lyng JVM full script: `1.287097604 s`
- Python warm function average over 5 runs: `126.126 ms`
- Lyng JVM warm function average over 5 runs: about `1071.6 ms`

Baseline ratio:
- Full script: about `7.7x` slower on Lyng JVM
- Warm function only: about `8.5x` slower on Lyng JVM

Primary finding at baseline:
- The hot `reminders[j]` accesses in `piSpigot` were still lowered through boxed object index ops and boxed arithmetic.
- Newly added `GET_INDEX_INT` and `SET_INDEX_INT` only reached `pi`, not `reminders`.
- Root cause: initializer element inference handled list literals, but not `List.fill(boxes) { 2 }`, so `reminders` did not become known `List<Int>` at compile time.

## After Optimizations 1-4

Follow-up change:
- propagate inferred lambda return class into bytecode compilation
- infer `List.fill(...)` element type from the fill lambda
- lower `reminders[j]` reads and writes to `GET_INDEX_INT` and `SET_INDEX_INT`
- add primitive-backed `ObjList` storage for all-int lists
- lower `List.fill(Int) { Int }` to `LIST_FILL_INT`
- stop boxing the integer index inside `GET_INDEX_INT` / `SET_INDEX_INT`

Verification:
- `piSpigot` disassembly now contains typed ops for `reminders`, for example:
  - `GET_INDEX_INT s5(reminders), s10(j), ...`
  - `SET_INDEX_INT s5(reminders), s10(j), ...`

Post-change measurements using `jlyng`:
- Full script: `655.819559 ms`
- Warm 5-run total: `1.430945810 s`
- Warm average per run: about `286.2 ms`

Observed improvement vs baseline:
- Full script: about `1.96x` faster (`1.287 s -> 0.656 s`)
- Warm function: about `3.74x` faster (`1071.6 ms -> 286.2 ms`)

Residual gap vs Python baseline:
- Full script: Lyng JVM is still about `3.9x` slower than Python (`655.8 ms` vs `167 ms`)
- Warm function: Lyng JVM is still about `2.3x` slower than Python (`286.2 ms` vs `126.126 ms`)

Current benchmark-test snapshot (`n=200`, JVM test harness):
- `optimized-int-division-rval-off`: `135 ms`
- `optimized-int-division-rval-on`: `125 ms`
- `piSpigot` bytecode now contains:
  - `LIST_FILL_INT` for both `pi` and `reminders`
  - `GET_INDEX_INT` / `SET_INDEX_INT` for the hot indexed loop
