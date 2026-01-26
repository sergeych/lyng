# Bytecode method call-site cache

Changes
- Added per-thread bytecode method call-site caches via BytecodeCallSiteCache expect/actuals.
- Bytecode VM now reuses per-function call-site maps to preserve method PIC hits across repeated bytecode executions.
- Removed unused methodCallSites property from BytecodeFunction.

Why
- Fixes JVM PIC invalidation test by allowing method PIC hits when bytecode bodies are invoked repeatedly (e.g., loop bodies compiled to bytecode statements).
- Avoids cross-thread mutable map sharing on native by using thread-local storage.

Tests
- ./gradlew :lynglib:jvmTest
- ./gradlew :lynglib:allTests -x :lynglib:jvmTest

Benchmark
- ./gradlew :lynglib:jvmTest --tests NestedRangeBenchmarkTest -Dbenchmarks=true
  - nested-happy elapsed=1266 ms
