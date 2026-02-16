# Mixed Compare Benchmark Baseline

Date: 2026-02-16

Benchmark:
- MixedCompareBenchmarkTest.benchmarkMixedCompareOps

Command:
`BENCHMARKS=true timeout 20s ./gradlew :lynglib:jvmTest --tests MixedCompareBenchmarkTest --rerun-tasks`

Result:
- mixed-compare elapsed=374 ms
