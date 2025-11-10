# Lyng Performance Guide (JVM‑first)

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
- JVM defaults (current): `ARG_BUILDER=true`, `PRIMITIVE_FASTOPS=true`, `RVAL_FASTPATH=true`, `FIELD_PIC=true`, `METHOD_PIC=true`, `SCOPE_POOL=true` (per‑thread ThreadLocal pool).


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
