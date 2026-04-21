AI State (for session restart)

Project: /home/sergeych/dev/lyng
Module focus: :lynglib

Current focus
- Enforce compile-time name/member resolution only; no runtime scope lookup or fallback.
- Closures capture frame slots directly; materialize `Scope` only for Kotlin interop or explicit dynamic helpers.
- Object members are allowed on unknown types; other members require a statically known receiver type or explicit cast.
- Type system is Kotlin-style: `T` non-null, `T?` nullable, `!!` asserts non-null; `void` is a singleton of class `Void`.
- Type expressions: unions/intersections with bounds, declaration-site variance (`in`/`out`), and structural equality.

Key recent changes
- Updated AI helper docs to reflect static typing, type expressions, and compile-time-only name resolution.
- Added stdlib random API: `Random` and deterministic `SeededRandom` with `nextInt`, `nextFloat`, and generic `next(range)`.
- Generalized primitive list optimization for compiler-generated `List.fill`:
  - `List.fill(size) { intExpr }` and `List.fill(size, capacity) { intExpr }` now both have bytecode fast paths.
  - Added `LIST_NEW_INT_CAP` / `LIST_FILL_INT_CAP` for the 3-arg capacity-preserving form.
- Fixed `ObjList.add(...)` to preserve primitive-int backing storage instead of forcing boxing through `.list`.
- `OptTest.testAddToArray2` no longer shows the old 10x anomaly for `List.fill(n, n + 10)` or append-to-extended-list.

Known failing tests
- None in :lynglib:jvmTest after Random/SeededRandom integration.

Last test run
- `./gradlew :lynglib:jvmTest` (PASS).
