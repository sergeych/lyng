
This document explains how to enable and measure the performance optimizations added to the Lyng interpreter. The focus is JVM‑first with safe, flag‑guarded rollouts and quick A/B testing. Other targets (JS/Wasm/Native) keep conservative defaults until validated.

## Overview

Optimizations are controlled by runtime‑mutable flags in `net.sergeych.lyng.PerfFlags`, initialized from platform‑specific static defaults `net.sergeych.lyng.PerfDefaults` (KMP `expect/actual`).

- JVM/Android defaults are aggressive (e.g. `RVAL_FASTPATH=true`).
- Non‑JVM defaults are conservative (e.g. `RVAL_FASTPATH=false`).

All flags are `var` and can be flipped at runtime (e.g., from tests or host apps) for A/B comparisons.

## Key flags

- `LOCAL_SLOT_PIC` — Runtime cache in `LocalVarRef` to avoid repeated name→slot lookups per frame (ON JVM default).
- `EMIT_FAST_LOCAL_REFS` — Compiler emits `FastLocalVarRef` for identifiers known to be locals/params (ON JVM default).
- `ARG_BUILDER` — Efficient argument building: small‑arity no‑alloc and pooled builder on JVM (ON JVM default).
- `SKIP_ARGS_ON_NULL_RECEIVER` — Early return on optional‑null receivers before building args (semantics‑compatible). A/B only.
- `SCOPE_POOL` — Scope frame pooling for calls (JVM, per‑thread ThreadLocal pool). ON by default on JVM; togglable at runtime.
- `FIELD_PIC` — 2‑entry polymorphic inline cache for field reads/writes keyed by `(classId, layoutVersion)` (ON JVM default).
- `METHOD_PIC` — 2‑entry PIC for instance method calls keyed by `(classId, layoutVersion)` (ON JVM default).
- `PIC_DEBUG_COUNTERS` — Enable lightweight hit/miss counters via `PerfStats` (OFF by default).
- `PRIMITIVE_FASTOPS` — Fast paths for `(ObjInt, ObjInt)` arithmetic/comparisons and `(ObjBool, ObjBool)` logic (ON JVM default).
- `RVAL_FASTPATH` — Bypass `ObjRecord` in pure expression evaluation via `ObjRef.evalValue` (ON JVM default, OFF elsewhere).

See `src/commonMain/kotlin/net/sergeych/lyng/PerfFlags.kt` and `PerfDefaults.*.kt` for details and platform defaults.

## Where optimizations apply

- Locals: `FastLocalVarRef`, `LocalVarRef` per‑frame cache (PIC).
- Calls: small‑arity zero‑alloc paths (0–8 args), pooled builder (JVM), and child frame pooling (optional).
- Properties/methods: Field/Method PICs with receiver shape `(classId, layoutVersion)` and handle‑aware caches.
- Expressions: R‑value fast paths in hot nodes (`UnaryOpRef`, `BinaryOpRef`, `ElvisRef`, logical ops, `RangeRef`, `IndexRef` read, `FieldRef` receiver eval, `ListLiteralRef` elements, `CallRef` callee, `MethodCallRef` receiver, assignment RHS).
- Primitives: Direct boolean/int ops where safe.

## Running JVM micro‑benchmarks

Each benchmark prints timings with `[DEBUG_LOG]` and includes correctness assertions to prevent dead‑code elimination.

Run individual tests to avoid multiplatform matrices:

```
./gradlew :lynglib:jvmTest --tests LocalVarBenchmarkTest
./gradlew :lynglib:jvmTest --tests CallBenchmarkTest
./gradlew :lynglib:jvmTest --tests CallMixedArityBenchmarkTest
./gradlew :lynglib:jvmTest --tests CallSplatBenchmarkTest
./gradlew :lynglib:jvmTest --tests PicBenchmarkTest
./gradlew :lynglib:jvmTest --tests PicInvalidationJvmTest
./gradlew :lynglib:jvmTest --tests ArithmeticBenchmarkTest
./gradlew :lynglib:jvmTest --tests ExpressionBenchmarkTest
./gradlew :lynglib:jvmTest --tests CallPoolingBenchmarkTest
./gradlew :lynglib:jvmTest --tests MethodPoolingBenchmarkTest
./gradlew :lynglib:jvmTest --tests MixedBenchmarkTest
./gradlew :lynglib:jvmTest --tests DeepPoolingStressJvmTest
```

Typical output (example):

```
[DEBUG_LOG] [BENCH] mixed-arity x200000 [ARG_BUILDER=ON]: 85.7 ms
```

Lower time is better. Run the same bench with a flag OFF vs ON to compare.

## Toggling flags in tests

Flags are mutable at runtime, e.g.:

```kotlin
PerfFlags.ARG_BUILDER = false
val r1 = (Scope().eval(script) as ObjInt).value
PerfFlags.ARG_BUILDER = true
val r2 = (Scope().eval(script) as ObjInt).value
```

Reset flags at the end of a test to avoid impacting other tests.

## PIC diagnostics (optional)

Enable counters:

```kotlin
PerfFlags.PIC_DEBUG_COUNTERS = true
PerfStats.resetAll()
```

Available counters in `PerfStats`:

- Field PIC: `fieldPicHit`, `fieldPicMiss`, `fieldPicSetHit`, `fieldPicSetMiss`
- Method PIC: `methodPicHit`, `methodPicMiss`
- Locals: `localVarPicHit`, `localVarPicMiss`, `fastLocalHit`, `fastLocalMiss`
- Primitive ops: `primitiveFastOpsHit`

Print a summary at the end of a bench/test as needed. Remember to turn counters OFF after the test.

## Guidance per flag (JVM)

- Keep `RVAL_FASTPATH = true` unless debugging a suspected expression‑semantics issue.
- Use `SCOPE_POOL = true` only for benchmarks or once pooling passes the deep stress tests and broader validation; currently OFF by default.
- `FIELD_PIC` and `METHOD_PIC` should remain ON; they are validated with invalidation tests.
- `ARG_BUILDER` should remain ON; switch OFF only to get a baseline.

## Notes on correctness & safety

- Optional chaining semantics are preserved across fast paths.
- Visibility/mutability checks are enforced even on PIC fast‑paths.
- `frameId` is regenerated on each pooled frame borrow; stress tests verify no leakage under deep nesting/recursion.

## Cross‑platform

- Non‑JVM defaults keep `RVAL_FASTPATH=false` for now; other low‑risk flags may be ON.
- Once JVM path is fully validated and measured, add lightweight benches for JS/Wasm/Native and enable flags incrementally.

## Troubleshooting

- If a benchmark shows regressions, flip related flags OFF to isolate the source (e.g., `ARG_BUILDER`, `RVAL_FASTPATH`, `FIELD_PIC`, `METHOD_PIC`).
- Use `PIC_DEBUG_COUNTERS` to observe inline cache effectiveness.
- Ensure tests do not accidentally keep flags ON for subsequent tests; reset after each test.


## JVM micro-benchmark results (3× medians; OFF → ON)

Date: 2025-11-10 23:04 (local)

| Flag               | Benchmark/Test                              | OFF median (ms) | ON median (ms) | Speedup | Notes |
|--------------------|----------------------------------------------|-----------------:|----------------:|:-------:|-------|
| ARG_BUILDER        | CallMixedArityBenchmarkTest                   |           788.02 |          668.79 |  1.18×  | Clear win on mixed arity |
| ARG_BUILDER        | CallBenchmarkTest (simple calls)              |           423.87 |          425.47 |  1.00×  | Neutral on repeated simple calls |
| FIELD_PIC          | PicBenchmarkTest::benchmarkFieldGetSetPic     |           113.575 |          106.017 |  1.07×  | Small but consistent win |
| METHOD_PIC         | PicBenchmarkTest::benchmarkMethodPic          |           251.068 |          149.439 |  1.68×  | Large consistent win |
| RVAL_FASTPATH      | ExpressionBenchmarkTest                       |           514.491 |          426.800 |  1.21×  | Consistent win in expression chains |
| PRIMITIVE_FASTOPS  | ArithmeticBenchmarkTest (int-sum)             |           243.420 |          128.146 |  1.90×  | Big win for integer addition |
| PRIMITIVE_FASTOPS  | ArithmeticBenchmarkTest (int-cmp)             |           210.385 |          168.534 |  1.25×  | Moderate win for comparisons |
| SCOPE_POOL         | CallPoolingBenchmarkTest                      |           505.778 |          366.737 |  1.38×  | Single-threaded bench; per-thread ThreadLocal pool; default ON on JVM |

Notes:
- All results obtained from `[DEBUG_LOG] [BENCH]` outputs with three repeated Gradle test invocations per configuration; medians reported.
- JVM defaults (current): `ARG_BUILDER=true`, `PRIMITIVE_FASTOPS=true`, `RVAL_FASTPATH=true`, `FIELD_PIC=true`, `METHOD_PIC=true`, `SCOPE_POOL=true` (per‑thread ThreadLocal pool), `REGEX_CACHE=true`.


## Concurrency (multi‑core) pooling results (3× medians; OFF → ON)

Date: 2025-11-10 22:56 (local)

| Flag       | Benchmark/Test                      | OFF median (ms) | ON median (ms) | Speedup | Notes |
|------------|--------------------------------------|-----------------:|----------------:|:-------:|-------|
| SCOPE_POOL | ConcurrencyCallBenchmarkTest (JVM)   |           521.102 |          201.374 |  2.59×  | Multithreaded workload on `Dispatchers.Default` with per‑thread ThreadLocal pool; workers=8, iters=15000/worker. |

Methodology:
- The test toggles `PerfFlags.SCOPE_POOL` within a single run and executes the same script across N worker coroutines scheduled on `Dispatchers.Default`.
- We executed the test three times via Gradle and computed medians from the printed `[DEBUG_LOG]` timings:
  - OFF runs (ms): 532.442 | 521.102 | 474.386 → median 521.102
  - ON runs (ms):  218.683 | 201.374 | 198.737 → median 201.374
- Speedup = OFF/ON.

Reproduce:
```
./gradlew :lynglib:jvmTest --tests "ConcurrencyCallBenchmarkTest" --rerun-tasks
```


## Next optimization steps (JVM)

Date: 2025-11-10 23:04 (local)

- PICs
  - Widen METHOD_PIC to 3–4 entries with tiny LRU; keep invalidation on layout change; re-run `PicInvalidationJvmTest`.
  - Micro fast-path for FIELD_PIC read-then-write pairs (`x = x + 1`) to reuse the resolved slot within one step.
- Locals and slots
  - Pre-size `Scope` slot structures when compiler knows local/param counts; audit `EMIT_FAST_LOCAL_REFS` coverage.
  - Re-run `LocalVarBenchmarkTest` to quantify gains.
- RVAL_FASTPATH coverage
  - Cover primitive `ObjList` index reads, pure receivers in `FieldRef`, and assignment RHS where safe; add micro-benches to `ExpressionBenchmarkTest`.
- Collections and ranges
  - Specialize `(Int..Int)` loops into tight counted loops (no intermediary objects).
  - Add primitive-specialized `ObjList` ops (`map`, `filter`, `sum`, `contains`) under `PRIMITIVE_FASTOPS`.
- Regex and strings
  - Cache compiled regex for string literals at compile time; add a tiny LRU for dynamic patterns behind `REGEX_CACHE`.
  - Add `RegexBenchmarkTest` for repeated matches.
- JIT friendliness (Kotlin/JVM)
  - Inline tiny helpers in hot paths, prefer arrays for internal buffers, finalize hot data structures where safe.

Validation matrix
- Always re-run: `CallBenchmarkTest`, `CallMixedArityBenchmarkTest`, `PicBenchmarkTest`, `ExpressionBenchmarkTest`, `ArithmeticBenchmarkTest`, `CallPoolingBenchmarkTest`, `DeepPoolingStressJvmTest`, `ConcurrencyCallBenchmarkTest` (3× medians when comparing).
- Keep full `:lynglib:jvmTest` green after each change.



## PIC update (4‑way METHOD_PIC) — JVM (3× medians; OFF → ON)

Date: 2025-11-11 00:16 (local)

| Flag      | Benchmark/Test                               | OFF median (ms) | ON median (ms) | Speedup | Notes |
|-----------|-----------------------------------------------|-----------------:|----------------:|:-------:|-------|
| FIELD_PIC | PicBenchmarkTest::benchmarkFieldGetSetPic      |          207.578 |         106.481 |  1.95×  | Read→write loop; micro fast‑path groundwork present |
| METHOD_PIC| PicBenchmarkTest::benchmarkMethodPic           |          273.478 |         182.226 |  1.50×  | 4‑way PIC with move‑to‑front (was 2‑way before) |

Medians computed from three Gradle runs in this session; see `[DEBUG_LOG] [BENCH]` lines in test output.


## Locals/slots capacity (pre‑sizing hints) — JVM (3× medians; OFF → ON)

Date: 2025-11-11 13:19 (local)

| Optimization            | Benchmark/Test              | OFF config                         | ON config                          | OFF median (ms) | ON median (ms) | Speedup | Notes |
|-------------------------|-----------------------------|------------------------------------|------------------------------------|-----------------:|----------------:|:-------:|-------|
| Locals pre‑sizing + PIC | LocalVarBenchmarkTest       | LOCAL_SLOT_PIC=OFF, FAST_LOCAL=OFF | LOCAL_SLOT_PIC=ON, FAST_LOCAL=ON   |          472.129 |         370.871 |  1.27×  | Compiler hint `params+4`; slot pre‑size; semantics unchanged |

Methodology:
- Each configuration executed three times via `:lynglib:jvmTest --tests "…" --rerun-tasks`; medians reported.
- Locals improvement stacks with per‑thread `SCOPE_POOL` and ARG fast paths.




## RVAL fast paths update — JVM (IndexRef and FieldRef) [3× medians; OFF → ON]

Date: 2025-11-11 13:19 (local)

New micro-benchmarks have been added to quantify the latest `RVAL_FASTPATH` extensions:
- Primitive `ObjList` index-read fast path in `IndexRef`.
- Conservative “pure receiver” evaluation in `FieldRef` (monomorphic, immutable receiver), preserving visibility/mutability checks and optional chaining semantics.

Benchmarks to run (each 3× OFF → ON):
- `ExpressionBenchmarkTest::benchmarkListIndexReads`
- `ExpressionBenchmarkTest::benchmarkFieldReadPureReceiver`

Reproduce (3× each; collect `[DEBUG_LOG] [BENCH]` lines and compute medians):
```
./gradlew :lynglib:jvmTest --tests "ExpressionBenchmarkTest.benchmarkListIndexReads" --rerun-tasks
./gradlew :lynglib:jvmTest --tests "ExpressionBenchmarkTest.benchmarkListIndexReads" --rerun-tasks
./gradlew :lynglib:jvmTest --tests "ExpressionBenchmarkTest.benchmarkListIndexReads" --rerun-tasks

./gradlew :lynglib:jvmTest --tests "ExpressionBenchmarkTest.benchmarkFieldReadPureReceiver" --rerun-tasks
./gradlew :lynglib:jvmTest --tests "ExpressionBenchmarkTest.benchmarkFieldReadPureReceiver" --rerun-tasks
./gradlew :lynglib:jvmTest --tests "ExpressionBenchmarkTest.benchmarkFieldReadPureReceiver" --rerun-tasks
```

Once collected, add medians and speedups to the table below:

| Flag          | Benchmark/Test                                  | OFF median (ms) | ON median (ms) | Speedup | Notes |
|---------------|---------------------------------------------------|-----------------:|----------------:|:-------:|-------|
| RVAL_FASTPATH | ExpressionBenchmarkTest::benchmarkListIndexReads  |           305.243 |          230.942 |  1.32×  | Fast path in `IndexRef` for `ObjList` + `ObjInt` index |
| RVAL_FASTPATH | ExpressionBenchmarkTest::benchmarkFieldReadPureReceiver |           266.222 |          190.720 |  1.40×  | Pure-receiver evaluation in `FieldRef` (monomorphic, immutable) |

Notes:
- Both benches toggle `PerfFlags.RVAL_FASTPATH` within a single run to produce OFF and ON timings under identical conditions.
- Correctness assertions ensure the loops are not optimized away.
- All semantics (visibility/mutability checks, optional chaining) remain intact; fast paths only skip interim `ObjRecord` traffic when safe.


## ARG_BUILDER — splat fast‑path (3× medians; OFF → ON)

Date: 2025-11-11 13:12 (local)

Environment: Gradle 8.7; JVM (JDK as configured by toolchain); single‑threaded test execution; stdout enabled.

| Flag        | Benchmark/Test                    | OFF median (ms) | ON median (ms) | Speedup | Notes |
|-------------|-----------------------------------|-----------------:|----------------:|:-------:|-------|
| ARG_BUILDER | CallSplatBenchmarkTest (splat)    |          613.689 |         463.593 |  1.32×  | Single‑splat fast‑path returns underlying list directly; avoids intermediate copies |

Inputs (3×):
- OFF runs (ms): 613.689 | 629.604 | 612.361 → median 613.689
- ON runs (ms):  453.752 | 463.593 | 468.844 → median 463.593

Reproduce (3×):
```
./gradlew :lynglib:jvmTest --tests "CallSplatBenchmarkTest" --rerun-tasks
```



## Phase A consolidation (JVM) — 3× medians updated

Date: 2025-11-11 13:48 (local)
Environment:
- JDK: OpenJDK 20.0.2.1 (Amazon Corretto 20.0.2.1+10-FR)
- Gradle: 8.7
- OS/Arch: macOS 14.8.1 (aarch64)

### ARG_BUILDER

| Benchmark/Test                   | OFF median (ms) | ON median (ms) | Speedup | Notes |
|----------------------------------|-----------------:|----------------:|:-------:|-------|
| CallMixedArityBenchmarkTest      |          866.681 |         717.439 |  1.21×  | Small-arity 0–8 fast path + builder; correctness preserved |
| CallSplatBenchmarkTest (splat)   |          600.880 |         459.706 |  1.31×  | Single-splat fast path returns underlying list; avoids copies |

Inputs (3×):
- Mixed arity OFF: 874.088291 | 866.680959 | 858.577125 → median 866.680959
- Mixed arity ON:  731.308625 | 706.440125 | 717.438542 → median 717.438542
- Splat OFF: 600.268625 | 607.849416 | 600.879666 → median 600.879666
- Splat ON:  459.706375 | 449.950166 | 461.815167 → median 459.706375

### RVAL_FASTPATH (new coverage)

| Benchmark/Test                                   | OFF median (ms) | ON median (ms) | Speedup | Notes |
|--------------------------------------------------|-----------------:|----------------:|:-------:|-------|
| ExpressionBenchmarkTest::benchmarkListIndexReads |          299.366 |         218.812 |  1.37×  | IndexRef fast path for ObjList + ObjInt |
| ExpressionBenchmarkTest::benchmarkFieldReadPureReceiver |          268.315 |         186.032 |  1.44×  | Pure-receiver evaluation in FieldRef (monomorphic, immutable) |

Inputs (3×):
- ListIndex OFF: 291.344 | 310.717167 | 299.365709 → median 299.365709
- ListIndex ON:  217.795375 | 221.504166 | 218.812042 → median 218.812042
- FieldRead OFF: 267.2775 | 274.355208 | 268.315125 → median 268.315125
- FieldRead ON:  189.599333 | 186.031791 | 182.069167 → median 186.031791

### Locals/slots capacity (precise hints)

| Benchmark/Test             | OFF config                         | ON config                          | OFF median (ms) | ON median (ms) | Speedup | Notes |
|---------------------------|------------------------------------|------------------------------------|-----------------:|----------------:|:-------:|-------|
| LocalVarBenchmarkTest     | LOCAL_SLOT_PIC=OFF, FAST_LOCAL=OFF | LOCAL_SLOT_PIC=ON, FAST_LOCAL=ON   |          446.018 |         347.964 |  1.28×  | Precise capacity hints + fast-locals coverage |

Inputs (3×):
- Locals OFF: 470.575041 | 441.89625 | 446.017833 → median 446.017833
- Locals ON:  370.664208 | 345.615541 | 347.964291 → median 347.964291

Methodology:
- Each test executed three times via Gradle with stdout enabled; medians computed from `[DEBUG_LOG] [BENCH]` lines.
- Full JVM tests and stress benches remain green in this cycle.



## Phase B — List ops specialization (PRIMITIVE_FASTOPS) — 3× medians (OFF → ON)

Date: 2025-11-11 13:48 (local)
Environment:
- JDK: OpenJDK 20.0.2.1 (Amazon Corretto 20.0.2.1+10-FR)
- Gradle: 8.7
- OS/Arch: macOS 14.8.1 (aarch64)

| Optimization        | Benchmark/Test                          | OFF median (ms) | ON median (ms) | Speedup | Notes |
|---------------------|------------------------------------------|-----------------:|----------------:|:-------:|-------|
| PRIMITIVE_FASTOPS   | ListOpsBenchmarkTest::benchmarkSumInts   |          324.805 |         144.908 |  2.24×  | ObjList.sum fast path for int lists; generic fallback preserved |
| PRIMITIVE_FASTOPS   | ListOpsBenchmarkTest::benchmarkContainsInts |          440.414 |         415.476 |  1.06×  | ObjList.contains fast path when searching ObjInt in int list |

Inputs (3×):
- list-sum OFF: 332.863417 | 323.491625 | 324.804083 → median 324.804083
- list-sum ON:  144.907833 | 148.870792 | 126.418542 → median 144.907833
- list-contains OFF: 440.413709 | 440.368333 | 441.4365 → median 440.413709
- list-contains ON:  416.465292 | 412.283291 | 415.475833 → median 415.475833

Methodology:
- Each test executed three times via Gradle; medians computed from `[DEBUG_LOG] [BENCH]` lines.
- Changes are fully guarded by `PerfFlags.PRIMITIVE_FASTOPS`; semantics preserved (null on empty sum; generic fallback on mixed types).



### Phase B — Ranges for-in lowering (PRIMITIVE_FASTOPS) — 3× medians (OFF → ON)

Date: 2025-11-11 13:48 (local)
Environment:
- JDK: OpenJDK 20.0.2.1 (Amazon Corretto 20.0.2.1+10-FR)
- Gradle: 8.7
- OS/Arch: macOS 14.8.1 (aarch64)

| Optimization        | Benchmark/Test                          | OFF median (ms) | ON median (ms) | Speedup | Notes |
|---------------------|------------------------------------------|-----------------:|----------------:|:-------:|-------|
| PRIMITIVE_FASTOPS   | RangeBenchmarkTest::benchmarkIntRangeForIn |         1705.299 |         788.974 |  2.16×  | Tight counted loop for (Int..Int) for-in; preserves semantics |

Inputs (3×):
- range-for-in OFF: 1705.298958 | 1684.357708 | 1735.880917 → median 1705.298958
- range-for-in ON:  794.178458 | 778.741834 | 788.973625 → median 788.973625

Methodology:
- Each configuration executed three times via Gradle; medians computed from `[DEBUG_LOG] [BENCH]` lines.
- Lowering is guarded by `PerfFlags.PRIMITIVE_FASTOPS` and applies only when the source is an `ObjRange` with int bounds; otherwise falls back to generic iteration.



## Phase B — Regex caching (REGEX_CACHE) — 3× medians (OFF → ON)

Date: 2025-11-11 13:48 (local)
Environment:
- JDK: OpenJDK 20.0.2.1 (Amazon Corretto 20.0.2.1+10-FR)
- Gradle: 8.7
- OS/Arch: macOS 14.8.1 (aarch64)

| Flag         | Benchmark/Test                                  | OFF median (ms) | ON median (ms) | Speedup | Notes |
|--------------|---------------------------------------------------|-----------------:|----------------:|:-------:|-------|
| REGEX_CACHE  | RegexBenchmarkTest::benchmarkLiteralPatternMatches |          378.246 |         275.890 |  1.37×  | Caches compiled regex for identical literal pattern per iteration |
| REGEX_CACHE  | RegexBenchmarkTest::benchmarkDynamicPatternMatches |          514.944 |         229.006 |  2.25×  | Two dynamic patterns alternate; cache size sufficient to retain both |

Inputs (1× here; can extend to 3× on request):
- regex-literal OFF: 378.245916; ON: 275.889541
- regex-dynamic OFF: 514.944167; ON: 229.005834

Methodology:
- Each benchmark toggles `PerfFlags.REGEX_CACHE` inside a single test and prints `[DEBUG_LOG]` timings for OFF and ON runs under identical conditions. We recorded one set of OFF/ON timings here; we can extend to 3× medians if required for publication.
- The cache is a tiny size-bounded map (64 entries) activated only when `PerfFlags.REGEX_CACHE` is true. Defaults remain OFF.




## JIT tweaks (Round 1) — quick gains snapshot (locals, ranges, list ops)

Date: 2025-11-11 21:05 (local)

Scope: fast confirmation of overall gain using current configuration; focused on locals, ranges, and list ops. Each test prints OFF → ON timings in a single run. We executed the benches via Gradle with stdout enabled and single test fork.

Environment:
- Gradle: 8.7 (stdout enabled, maxParallelForks=1)
- JVM: as configured by toolchain for this project
- OS/Arch: per developer machine (unchanged from prior sections)

Reproduce:
```
./gradlew :lynglib:jvmTest --tests LocalVarBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests RangeBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests ListOpsBenchmarkTest --rerun-tasks
```

Results (representative runs; OFF → ON):
- Local variables — LOCAL_SLOT_PIC + EMIT_FAST_LOCAL_REFS
  - Run 1: 468.407 ms → 367.277 ms (≈ 1.28×)
  - Run 2: 447.031 ms → 346.126 ms (≈ 1.29×)
- Ranges for‑in — PRIMITIVE_FASTOPS (tight counted loop for (Int..Int))
  - 1731.780 ms → 799.023 ms (≈ 2.17×)
- List ops — PRIMITIVE_FASTOPS
  - sum(int list): 318.943 ms → 148.571 ms (≈ 2.15×)
  - contains(int in int list): 440.013 ms → 412.450 ms (≈ 1.07×)

Summary: All three areas improved with optimizations ON; no regressions observed in these runs. For publication‑grade stability, run each test 3× and report medians (see sections below for methodology and previous median tables).


## Additional tweaks — verification snapshot (Index write fast‑path, List literal pre‑size, Regex LRU)

Date: 2025-11-11 21:31 (local)

Scope: Implemented three semantics‑neutral optimizations and verified they are green across targeted and broader JVM benches.

What changed (guarded by flags where applicable):
- RVAL_FASTPATH: Index write fast‑path
  - `IndexRef.setAt`: direct path for `ObjList` + `ObjInt` (`list[i] = value`) mirrors the read fast‑path. Optional chaining semantics preserved; bounds exceptions propagate unchanged.
- RVAL_FASTPATH: List literal pre‑sizing
  - `ListLiteralRef.get`: pre‑counts element entries and uses `ArrayList` with capacity hint; for spreads of `ObjList`, uses `ensureCapacity` before bulk add. Evaluation order unchanged.
- REGEX_CACHE: LRU‑like behavior
  - `RegexCache`: emulates access‑order LRU within a tiny bounded map (`MAX=64`) by moving accessed entries to the tail; improves alternating‑pattern scenarios. Only active when `PerfFlags.REGEX_CACHE` is true.

Reproduce quick verification (1× runs):
```
./gradlew :lynglib:jvmTest --tests ExpressionBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests ListOpsBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests RegexBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests PicBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests PicInvalidationJvmTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests LocalVarBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests ConcurrencyCallBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests DeepPoolingStressJvmTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests MultiThreadPoolingStressJvmTest --rerun-tasks
```

Observation: All listed tests green in this cycle; no behavioral regressions observed. For the new paths (index write, list literal), performance was neutral‑to‑positive in smoke runs; Regex benches remained positive or neutral with the LRU behavior. For publication‑grade medians, extend to 3× per test as in earlier sections.


## Sanity matrix (JVM) — quick OFF→ON runs

Date: 2025-11-11 21:59 (local)

Scope: Final Round 1 sanity sweep across JVM micro‑benches and stress tests to confirm that optimizations ON do not regress performance vs OFF in representative scenarios. Each benchmark prints `[DEBUG_LOG] [BENCH]` timings for OFF → ON within a single run. This section records a quick pass confirmation (not 3× medians) and reproduction commands.

Environment:
- Gradle: 8.7 (stdout enabled, maxParallelForks=1)
- JVM: as configured by the project toolchain
- OS/Arch: macOS 14.x (aarch64)

Benches covered (all green; no regressions observed in these runs):
- Calls/Args: `CallBenchmarkTest`, `CallMixedArityBenchmarkTest` (ARG_BUILDER)
- PICs: `PicBenchmarkTest` (field/method); `PicInvalidationJvmTest` correctness reconfirmed
- Expressions/Arithmetic: `ExpressionBenchmarkTest`, `ArithmeticBenchmarkTest` (RVAL_FASTPATH, PRIMITIVE_FASTOPS)
- Ranges: `RangeBenchmarkTest` (PRIMITIVE_FASTOPS counted loop)
- List ops: `ListOpsBenchmarkTest` (PRIMITIVE_FASTOPS specializations)
- Regex: `RegexBenchmarkTest` (REGEX_CACHE with LRU behavior)
- Locals: `LocalVarBenchmarkTest` (LOCAL_SLOT_PIC + FAST_LOCAL)
- Concurrency/Pooling: `ConcurrencyCallBenchmarkTest`, `DeepPoolingStressJvmTest`, `MultiThreadPoolingStressJvmTest` (SCOPE_POOL per‑thread)

Reproduce (examples):
```
./gradlew :lynglib:jvmTest --tests CallBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests CallMixedArityBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests PicBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests PicInvalidationJvmTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests ExpressionBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests ArithmeticBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests RangeBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests ListOpsBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests RegexBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests LocalVarBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests ConcurrencyCallBenchmarkTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests DeepPoolingStressJvmTest --rerun-tasks
./gradlew :lynglib:jvmTest --tests MultiThreadPoolingStressJvmTest --rerun-tasks
```

Summary:
- All listed tests passed in this sanity sweep.
- For each benchmark’s OFF → ON printouts examined during this pass, ON was equal or faster than OFF; no ON<OFF regressions were observed.
- For publication‑grade numbers, use the 3× medians methodology outlined earlier in this document. The existing median tables in previous sections remain representative, and the additional tweaks (Index write, List literal pre‑size, Regex LRU, Field PIC 4‑way + read→write reuse, mixed Int/Real fast‑ops) remained neutral‑to‑positive.

