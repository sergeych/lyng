# JVM-only Performance Optimization Plan (Saved)

Date: 2025-11-10 22:14 (local)

This document captures the agreed next optimization steps so we can restore the plan later if needed.

## Objectives
- Reduce overhead on the call/argument path.
- Extend and harden PIC performance (fields/methods/locals).
- Improve R-value fast paths and interpreter hot nodes (loops, ranges, lists).
- Make scope frame pooling thread-safe on JVM so it can be enabled by default later.
- Keep semantics correct and all JVM tests green.

## Prioritized tasks (now)
1) Call/argument path: fewer allocs, tighter fast paths
- Extend small-arity zero-alloc path to 6–8 args; benchmark with `CallMixedArityBenchmarkTest`.
- Splat handling: fast-path single-list splats; benchmark with `CallSplatBenchmarkTest`.
- Arg builder micro-optimizations: capacity hints, avoid redundant copies, inline simple branches.
- Optional-chaining fast return (`SKIP_ARGS_ON_NULL_RECEIVER`) coverage audit, add A/B bench.

2) Scope frame pooling: per-thread safety on JVM
- Replace global deque with ThreadLocal pool on JVM (and Android) actuals.
- Keep `frameId` uniqueness and pool size cap.
- Verify with `DeepPoolingStressJvmTest`, `CallPoolingBenchmarkTest`, and spot benches.
- Do NOT flip default yet; keep `SCOPE_POOL=false` unless explicitly approved.

## Next tasks (queued)
3) PICs: cheaper misses, broader hits
- Method PIC 2→3/4 entries (tiny LRU); validate with `PicInvalidationJvmTest`.
- Field PIC micro-fast path for read-then-write pairs.

4) Locals and slots
- Ensure `EMIT_FAST_LOCAL_REFS` coverage across compiler sites.
- Pre-size `slots`/`nameToSlot` when local counts are known; re-run `LocalVarBenchmarkTest`.

5) R-value fast path coverage
- Cover index reads on primitive lists, pure receivers, assignment RHS where safe.
- Add benches in `ExpressionBenchmarkTest`.

6) Collections & ranges
- Tight counted loop for `(Int..Int)` in `for`.
- Primitive-specialized `ObjList` ops (`map`, `filter`, `sum`, `contains`) under `PRIMITIVE_FASTOPS`.

7) Regex and string ops
- Cache compiled regex for string literals at compile time; tiny LRU for dynamic patterns under a new `REGEX_CACHE` flag.

8) JIT micro-tweaks
- Inline tiny helpers; prefer arrays for hot buffers; finalize hot classes where safe.

## Validation matrix
- Always re-run: `CallBenchmarkTest`, `CallMixedArityBenchmarkTest`, `PicBenchmarkTest`, `ExpressionBenchmarkTest`, `ArithmeticBenchmarkTest`, `CallPoolingBenchmarkTest`, `DeepPoolingStressJvmTest`.
- Use 3× medians where comparing flags; keep `:lynglib:jvmTest` green.

## Notes
- All risky changes remain flag-guarded and JVM-only where applicable.
- Documentation and perf tables updated after each cycle.
