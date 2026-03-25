# Changelog

This file tracks user-visible Lyng language/runtime/tooling changes.

History note:
- The project had periods where changelog maintenance lagged behind commits.
- Entries below are synchronized and curated for `1.5.x`.
- Earlier history may be incomplete and should be cross-checked with git tags/commits when needed.

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
