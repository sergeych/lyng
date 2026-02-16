# VM Fast-Path Plan (Non-Suspending Ops)

## Goal
Reduce suspend overhead by routing safe, local-only ops through non-suspending fast paths while preserving semantics for captured locals and dynamic objects.

## Completed
- [x] Local numeric/bool/conversion ops: add fast local variants and guard by non-captured locals.
- [x] Local jumps: `JMP_IF_TRUE/FALSE` and fused int jumps fast path.
- [x] **Object arithmetic ops** (`ADD_OBJ/SUB_OBJ/MUL_OBJ/DIV_OBJ/MOD_OBJ`): add fast-path for local numeric slots with fallback to slow path when non-numeric or captured.
- [x] Full `:lynglib:jvmTest` green after changes.

## In Progress / Next
- [ ] Object comparisons (`CMP_*_OBJ`) fast-path on local numeric slots (guarded, fallback to slow path).
- [ ] Local `MOVE_OBJ` safe fast-path (only if slot is not `FrameSlotRef`/`RecordSlotRef`, or add explicit unwrapping).
- [ ] Address ops (`LOAD_*_ADDR` / `STORE_*_ADDR`) non-suspend variants when addr already resolved.
- [ ] Confirm wasm/js behavior unchanged (if needed).

## Guardrails
- Never treat captured locals as fast locals; route them through slow path.
- Fast path must only execute when slot types are primitive numeric/bool and stored directly in frame.
- If fast-path preconditions fail, fall back to suspend `perform()`.

## Test Gate
- `./gradlew :lynglib:jvmTest`
- Benchmarks optional: `./gradlew :lynglib:jvmTest -Pbenchmarks=true --tests '*NestedRangeBenchmarkTest*'`

## A/B Benchmark (Representative)
- Baseline (fast-path stashed): 194 ms
- With fast-path changes: 186 ms
- Command: `./gradlew :lynglib:jvmTest -Pbenchmarks=true --tests '*RepresentativeBenchmarkTest*'`

## A/B Benchmark (Representative Object Ops)
- Baseline (fast-path stashed): 106 ms
- With fast-path changes: 108 ms
- Command: `./gradlew :lynglib:jvmTest -Pbenchmarks=true --tests '*RepresentativeObjectBenchmarkTest*'`

## A/B Benchmark (Representative Object Ops, extended comparisons)
- Baseline (ObjString only): 141 ms
- With ObjString + ObjInt/ObjReal compare ops: 137 ms
- Command: `./gradlew :lynglib:jvmTest -Pbenchmarks=true --tests '*RepresentativeObjectBenchmarkTest*'`

## A/B Benchmark (Representative Object Ops, object numeric compares)
- Baseline (no Obj* compare ops): 153 ms
- With ObjString + ObjInt/ObjReal compare ops: 149 ms
- Command: `./gradlew :lynglib:jvmTest -Pbenchmarks=true --tests '*RepresentativeObjectBenchmarkTest*'`

## A/B Benchmark (Trust type decls for Obj* compares)
- With ObjString + ObjInt/ObjReal compare ops + type-decl trust: 157 ms
- Command: `./gradlew :lynglib:jvmTest -Pbenchmarks=true --tests '*RepresentativeObjectBenchmarkTest*'`
