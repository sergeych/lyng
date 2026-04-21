# Non-Suspending Call Optimization Plan

## Current state

Completed in the current phase:

- Higher-order lambda inlining is now metadata-driven through `CallSignature`.
- Built-in member methods (`let`, `also`, `apply`, `run`, `forEach`, `map`, `mapNotNull`, `associateBy`, `getOrPut`) publish inline metadata at declaration sites.
- Lyng extension wrappers now preserve and expose `callSignature`, so extension methods such as `Iterable.filter` use the same inlining path as built-in members.
- `BytecodeCompiler` no longer relies on a backend hardcoded name table for these higher-order inlining cases.
- JVM tests are green after the metadata move.

Primary motivation remains unchanged: suspend call overhead is still significant, and lambda inlining only removes part of it.

## Constraints

- Keep source positions, stack traces, and throw-site reporting correct.
- Do not reintroduce one-off special cases tied to specific stdlib method names.
- Prefer declaration metadata and reusable compiler/runtime mechanisms.
- Preserve Kotlin Multiplatform compatibility in `commonMain`.
- Avoid changing public language semantics just to optimize the runtime path.

## Why this is the next step

Lambda inlining helps when the callee body is directly available at the call site.
The next large remaining cost is calling compiled functions through suspend entry points even when the generated body never suspends.

That suggests a second optimization track:

1. detect bytecode callables that are safe to execute through a non-suspending fast path;
2. route direct calls to that path when the caller can prove it is safe;
3. keep the suspend path as the fallback for correctness.

## Proposed phases

### Phase 1: Define "non-suspending compiled callable"

Add explicit metadata on compiled functions / lambdas indicating whether their bytecode body may suspend.

Requirements:

- Computed once during bytecode generation.
- Conservative: false negatives are acceptable; false positives are not.
- Must account for:
  - direct suspend-capable call opcodes;
  - flow / coroutine constructs;
  - delegated runtime helpers that may suspend;
  - nested lambda creation if invocation may suspend.

Likely implementation direction:

- store `maySuspend` or `fastOnly`-adjacent metadata on `CmdFunction` or the callable wrapper;
- derive it from emitted bytecode opcodes and embedded lambda constants.

### Phase 2: Add a direct non-suspending invoke path

For bytecode callables proven non-suspending, add an execution entry point that avoids suspend machinery for ordinary direct calls.

Requirements:

- Reuse as much of the existing fast frame setup as possible.
- Keep exception translation and source mapping identical to the suspend path.
- Do not depend on JVM-only tricks.

Potential direction:

- extend `BytecodeCallable` with a capability query or richer fast-call API;
- let call sites choose among:
  - inline body
  - non-suspending compiled call
  - existing suspend call

### Phase 3: Teach bytecode call sites to use it

Apply the new path only where the callee is known precisely.

Initial targets:

- direct lambda invocation where exact lambda ref is known but inlining is not possible;
- direct local function calls where the binding resolves to a compiled callable;
- extension wrapper calls where wrapper binding is known and non-suspending.

Do not start with dynamic dispatch or reflective calls.

### Phase 4: Validate behavioral fidelity

Must explicitly verify:

- thrown exceptions still report the same Lyng source positions;
- stack traces remain useful enough for debugging;
- optional calls / null propagation are unchanged;
- captures and implicit `this` still bind correctly.

### Phase 5: Measure before broadening

Benchmark after each widening step, especially:

- `OptTest.testAddToArray`
- iterable pipeline samples using `filter` / `map`
- direct lambda call microbenchmarks
- closure-heavy samples with captures

## Open technical questions

1. Where should non-suspending capability live?
   - `CmdFunction`
   - `BytecodeStatement`
   - callable wrapper object
   - `CallSignature`-adjacent metadata

2. Should the compiler emit a separate opcode for known non-suspending compiled calls, or should runtime dispatch pick the fast path from a normal call opcode?

3. Can we preserve the current error/stack behavior if we bypass suspend wrappers entirely, or do we need a thin compatibility layer?

4. Should capture-free and capture-heavy compiled lambdas share the same direct-call mechanism, or should captured callables stay on the safer path initially?

## Suggested order of execution

1. Add conservative `maySuspend` analysis for compiled bytecode functions.
2. Expose a non-suspending direct-call capability on compiled callables.
3. Use it for exact direct lambda calls first.
4. Extend to exact local function calls.
5. Re-measure.
6. Only then consider broader dispatch sites.

## Validation checklist

- `./gradlew :lynglib:compileKotlinJvm --console=plain`
- `./gradlew :lynglib:jvmTest --tests net.sergeych.lyng.OptTest.testAddToArray --console=plain`
- `./gradlew :lynglib:jvmTest --tests StdlibTest.testIterableFilter --tests CompilerVmReviewRegressionTest --console=plain`
- `./gradlew :lynglib:jvmTest --console=plain`

## Notes from the completed phase

Relevant current commits before this follow-up work:

- `3be2892` Use fast compiled callbacks in dynamic and flow helpers
- `1d5caaa` Broaden lambda method inlining with captures
- `0c3242c` Generalize higher-order lambda inlining
- `f4ab2eb` Extend lambda inlining to getOrPut and implicit it calls

Current working tree phase adds:

- metadata-driven higher-order inlining through member and extension signatures;
- extension wrapper signature propagation;
- removal of the compiler-side higher-order name table fallback.
