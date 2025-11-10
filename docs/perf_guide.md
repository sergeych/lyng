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
- `SCOPE_POOL` — Scope frame pooling for calls (JVM‑first). OFF by default. Enable for benchmark A/B.
- `FIELD_PIC` — 2‑entry polymorphic inline cache for field reads/writes keyed by `(classId, layoutVersion)` (ON JVM default).
- `METHOD_PIC` — 2‑entry PIC for instance method calls keyed by `(classId, layoutVersion)` (ON JVM default).
- `PIC_DEBUG_COUNTERS` — Enable lightweight hit/miss counters via `PerfStats` (OFF by default).
- `PRIMITIVE_FASTOPS` — Fast paths for `(ObjInt, ObjInt)` arithmetic/comparisons and `(ObjBool, ObjBool)` logic (ON JVM default).
- `RVAL_FASTPATH` — Bypass `ObjRecord` in pure expression evaluation via `ObjRef.evalValue` (ON JVM default, OFF elsewhere).

See `src/commonMain/kotlin/net/sergeych/lyng/PerfFlags.kt` and `PerfDefaults.*.kt` for details and platform defaults.

## Where optimizations apply

- Locals: `FastLocalVarRef`, `LocalVarRef` per‑frame cache (PIC).
- Calls: small‑arity zero‑alloc paths (0–5 args), pooled builder (JVM), and child frame pooling (optional).
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
