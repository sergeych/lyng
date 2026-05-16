# Changelog

This file tracks user-visible Lyng language/runtime/tooling changes.

History note:
- The project had periods where changelog maintenance lagged behind commits.
- Entries below are synchronized and curated for `1.5.x`.
- Earlier history may be incomplete and should be cross-checked with git tags/commits when needed.

## Unreleased

- No unreleased entries yet.

## 1.5.6 (2026-05-16)

### Process execution and CLI scripting
- Added `lyng.io.process` for coroutine-friendly external process execution.
- Added `sh(...)`, `exec(...)`, and `CommandRun` with captured output, streaming output, `wait()`, `check()`, and process status helpers.
- Documented safe shell-vs-argv usage and common script patterns for CLI automation.

### HTTP server and HTML rendering
- Added the minimal `lyng.io.http.server` API for embedded HTTP/1.1 services, local tools, test fixtures, and lightweight app backends.
- Added exact-path, regex, and path-template routing with named route parameters.
- Added `RequestContext` receiver sugar, JSON helpers (`jsonBody<T>()`, `respondJson(...)`), reusable routers, and WebSocket routes.
- Added `respondHtml { ... }` and the pure Lyng `lyng.io.html` builder DSL with escaped text, attributes, common tag helpers, and generic tag escape hatches.

### Language and serialization
- Added receiver-stack function types and context receiver extensions for DSL-style APIs.
- Added canonical JSON round-trip APIs with `Json.encode(...)` / `Json.decode(...)`.
- Added typed canonical JSON APIs with `Json.encodeAs(Type, value)` / `Json.decodeAs(Type, text)`.
- Extended database serialization support with typed canonical JSON encoding, SQL object expansion, decode annotations, and preserved declaration metadata.

### Runtime, docs, and samples
- Fixed nullable Elvis-with-`break` inference and callable return inference regressions.
- Fixed CLI bootstrap behavior for HTTP server scripts and CLI input handling.
- Expanded docs for HTTP, WebSocket, serialization, process execution, DSL receivers, and the generated site.
- Release metadata and README now point to `1.5.6`.

## 1.5.5 (2026-04-23)

### Concurrency and collections
- Added coroutine coordination primitives and helpers for everyday parallel code:
  - `Channel` for coroutine-to-coroutine communication
  - `LaunchPool` for bounded-concurrency task execution
  - `Iterable<Deferred>.joinAll()` to await a whole collection of deferreds in input order
  - `CompletableDeferred.completeExceptionally(...)` and `Deferred.cancelAndJoin()`
- Added docs and examples for the new concurrency APIs, including `joinAll()` coverage in iterable and parallelism references.

### Database and time APIs
- Added the portable `lyng.io.db` SQL contract and the first concrete providers:
  - `lyng.io.db.sqlite` on JVM and Linux Native
  - `lyng.io.db.jdbc` on JVM
- Added SQLite/JDBC release hardening:
  - nested transactions via savepoints
  - detached materialized rows
  - generated-key support through `ExecutionResult.getGeneratedKeys()`
  - schema-driven value conversion for `Bool`, `Decimal`, `Date`, `DateTime`, and `Instant`
  - portable SQLite linker/deployment fixes and documented runtime options
- Added `Date` to `lyng.time` and the core runtime as a first-class calendar-date type, plus conversions and arithmetic across `Instant`, `DateTime`, and `Date`.

### Language, stdlib, and tooling
- Added extensions on singleton `object` declarations, including object-scoped indexer overrides for bracket syntax.
- Added backtick string literals and formatter support.
- Added `lyng.legacy_digest` for SHA-1 compatibility work, `String.replace`, and `buffer.base64std`.
- Improved CLI/runtime behavior with `atExit` shutdown handlers, native release-binary work, and follow-up CLI packaging/import fixes.
- Expanded docs across the tutorial, stdlib references, database docs, networking docs, and release notes.

### Runtime/compiler stability and performance
- Extended exact-call and higher-order lambda inlining through the bytecode compiler, including compiled fast paths for simple lambdas, wrappers, captures, and common higher-order helpers.
- Fixed import caching and class/object bytecode dispatch on JVM.
- Fixed immutable `val` compound assignments so true mutating `*Assign` operations continue to work while fallback reassignments report the correct read-only error.
- Fixed closure/capture and import regressions across launched loops, singleton/object extensions, aliasing, transitive re-exports, and immutable capture escaping.
- Improved list-fill/list-append fast paths, nullable-let inference, Decimal/Complex interop, and related regression coverage.

### Release notes
- Release metadata, homepage samples, docs, and README now point to `1.5.5`.

## 1.5.4 (2026-04-03)

### Runtime and compiler stability
- Stabilized the recent `piSpigot` benchmark/compiler work for release.
- Fixed numeric-mix regressions introduced by overly broad int-coercion in bytecode compilation.
- Restored correct behavior for decimal arithmetic, mixed real/int flows, list literals, list size checks, and national-character script cases.
- Fixed plain-list index fast paths so they no longer bypass subclass behavior such as `ObservableList` hooks and flow notifications.
- Hardened local numeric compare fast paths to correctly handle primitive-coded frame slots.

### Performance and examples
- Added `piSpigot` benchmark/example coverage:
  - `examples/pi-test.lyng`
  - `examples/pi-bench.lyng`
  - JVM benchmark test for release-baseline verification
- Kept the safe list/index/runtime wins that improve the optimized `piSpigot` path without reintroducing type-unsound coercions.
- Changed the default `RVAL_FASTPATH` setting off on JVM/Android and in the benchmark preset after verification that it no longer helps the stabilized `piSpigot` workload.

### Release notes
- Full JVM and wasm test gates pass on the release tree.
- Benchmark findings and remaining post-release optimization targets are documented in `notes/pi_spigot_benchmark_baseline_2026-04-03.md`.

## 1.5.1 (2026-03-25)

### Language
- Added string interpolation:
  - `"$name"` identifier interpolation.
  - `"${expr}"` expression interpolation.
- Added literal-dollar forms in strings:
  - `"\$"` -> `$`
  - `"$$"` -> `$`
  - `\\$x` is parsed as backslash + interpolation of `x`.
- Added per-file interpolation opt-out via leading directive comment:
  - `// feature: interpolation: off`

### Docs and AI references
- Updated compiler-accurate AI language docs:
  - interpolation syntax and escaping
  - per-file feature switch behavior
- Refreshed tutorial examples and doctests to reflect new interpolation semantics.
- Added/reworked current proposal/reference materials for Lyng common-platform guidance.

### Compatibility notes
- Interpolation is enabled by default for normal string literals.
- Existing code that intentionally used `$name` as literal text should use `\$name`, `$$name`, or the file directive `// feature: interpolation: off`.

## 1.5.0 (2026-03-22)

### Major runtime/compiler direction
- Completed migration to bytecode-first/bytecode-only execution paths.
- Removed interpreter fallback behavior in core execution hot paths.
- Continued frame-slot-first local/capture model improvements and related diagnostics.

### Language features and semantics
- Added/finished `return` semantics including labeled non-local forms (`return@label`).
- Added abstract classes/members and `interface` support (as abstract-class-style construct).
- Completed and enabled multiple inheritance with C3 MRO by default.
- Added class properties with accessors (`get`/`set`) and restricted setter visibility (`private set`, `protected set`).
- Added late-initialized class `val` support with `Unset` protection rules.
- Added named arguments (`name: value`) and named splats (`...map`) with stricter validation.
- Added assign-if-null operator `?=`.
- Improved nullable/type-checking behavior (including `T is nullable` and related type checks).
- Added variadic function types (`...` in function type declarations) and tighter lambda type checks.

### Type system and collections
- Added immutable collections hierarchy (`ImmutableList`, `ImmutableSet`, `ImmutableMap`).
- Improved generic runtime binding/checking for explicit type arguments and bounds.
- Added smarter type-aware collection ops (`+=`, `-=`) and stronger declared-member type checks.

### Extern/Kotlin bridge
- Tightened extern declaration rules:
  - explicit extern members are required for extern class/object declarations.
- Improved extern generic class behavior and diagnostics.
- Extended bridge APIs for binding global functions/variables and object/member interop scenarios.

### Standard library and modules
- Added `lyng.observable` improvements (`ObservableList` hooks/events).
- Added `Random` stdlib API used by updated samples.
- Added/extended `lyngio.console` support and CLI integration for console interaction.
- Migrated time APIs to `kotlin.time` (`Instant` migration and related docs/tests).

### CLI, IDE, and docs/tooling
- CLI:
  - added first-class `fmt` command
  - preserved direct script fast-path invocation
  - improved command help/dispatch behavior
- IntelliJ plugin:
  - improved lightweight completion and documentation/inspection behavior
  - continued highlighter and Grazie/spellchecking integration work
- Docs:
  - substantial updates across tutorial/OOP/type/runtime references
  - expanded bytecode and advanced topics coverage

## Migration checklist for 1.5.x

- If you rely on literal `$...` strings:
  - replace with `\$...` or `$$...`, or
  - add `// feature: interpolation: off` at file top.
- Review any code relying on interpreter-era fallback behavior; 1.5.x assumes bytecode-first execution.
- For extern declarations, ensure members are explicitly declared where required.
- For named arguments/splats, verify call sites follow stricter ordering/duplication rules.
