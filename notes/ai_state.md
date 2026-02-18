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

Known failing tests
- Not checked in this session.

Last test run
- Not checked in this session.
