## Changelog

### Unreleased

- Language: Class properties with accessors
  - Support for `val` (read-only) and `var` (read-write) properties in classes.
  - Syntax: `val name [ : Type ] get() { body }` or `var name [ : Type ] get() { body } set(value) { body }`.
  - Laconic Expression Shorthand: `val prop get() = expression` and `var prop get() = read set(v) = write`.
  - Properties are pure accessors and do **not** have automatic backing fields.
  - Validation: `var` properties must have both accessors; `val` must have only a getter.
  - Integration: Updated TextMate grammar and IntelliJ plugin (highlighting + keywords).
  - Documentation: New "Properties" section in `docs/OOP.md`.

- Docs: Scopes and Closures guidance
  - New page: `docs/scopes_and_closures.md` detailing `ClosureScope` resolution order, recursion‑safe helpers (`chainLookupIgnoreClosure`, `chainLookupWithMembers`, `baseGetIgnoreClosure`), cycle prevention, and capturing lexical environments for callbacks (`snapshotForClosure`).
  - Updated: `docs/advanced_topics.md` (link to the new page), `docs/parallelism.md` (closures in `launch`/`flow`), `docs/OOP.md` (visibility from closures with preserved `currentClassCtx`), `docs/exceptions_handling.md` (compatibility alias `SymbolNotFound`).
  - Tutorial: added quick link to Scopes and Closures.

- IDEA plugin: Lightweight autocompletion (experimental)
  - Global completion: local declarations, in‑scope parameters, imported modules, and stdlib symbols.
  - Member completion: after a dot, suggests only members of the inferred receiver type (incl. chained calls like `Path(".." ).lines().` → `Iterator` methods). No global identifiers appear after a dot.
  - Inheritance-aware: direct class members first, then inherited (e.g., `List` includes `Collection`/`Iterable` methods).
  - Heuristics: handles literals (`"…"` → `String`, numbers → `Int/Real`, `[...]` → `List`, `{...}` → `Dict`) and static `Namespace.` members.
  - Performance: capped results, early prefix filtering, per‑document MiniAst cache, cancellation checks.
  - Toggle: Settings | Lyng Formatter → "Enable Lyng autocompletion (experimental)" (default ON).
  - Stabilization: DEBUG completion/Quick Doc logs are OFF by default; behavior aligned between IDE and isolated engine tests.

- Language: Named arguments and named splats
  - New call-site syntax for named arguments using colon: `name: value`.
    - Positional arguments must come before named; positionals after a named argument inside parentheses are rejected.
    - Trailing-lambda interaction: if the last parameter is already assigned by name (or via a named splat), a trailing `{ ... }` block is illegal.
  - Named splats: `...` can now expand a Map into named arguments.
    - Only string keys are allowed; non-string keys raise a clear error.
    - Duplicate assignment across named args and named splats is an error.
    - Ellipsis (variadic) parameters remain positional-only and cannot be named.
  - Rationale: `=` is assignment and an expression in Lyng; `:` at call sites avoids ambiguity. Declarations keep `name: Type`; call-site casts continue to use `as` / `as?`.
  - Documentation updated: proposals and declaring-arguments sections now cover named args/splats and error cases.
  - Tests added covering success cases and errors for named args/splats and trailing-lambda interactions.

- Tooling: Highlighters and TextMate bundle updated for named args
  - Website/editor highlighter (lyngweb + site) works with `name: value` and `...Map("k" => v)`; added JS tests covering punctuation/operator spans for `:` and `...`.
  - TextMate grammar updated to recognize named call arguments: `name: value` after `(` or `,` with `name` highlighted as `variable.parameter.named.lyng` and `:` as punctuation; excludes `::`.
  - TextMate bundle version bumped to 0.0.3; README updated with details and guidance.

- Multiple Inheritance (MI) completed and enabled by default:
  - Active C3 Method Resolution Order (MRO) for deterministic, monotonic lookup across complex hierarchies and diamonds.
  - Qualified dispatch:
    - `this@Type.member(...)` inside class bodies starts lookup at the specified ancestor.
    - Cast-based disambiguation: `(expr as Type).member(...)`, `(expr as? Type)?.member(...)` (works with existing safe-call `?.`).
  - Field inheritance (`val`/`var`) under MI:
    - Instance storage is disambiguated per declaring class; unqualified read/write resolves to the first match in MRO.
    - Qualified read/write targets the chosen ancestor’s storage.
  - Constructors and initialization:
    - Direct bases are initialized left-to-right; each ancestor is initialized at most once (diamond-safe de-duplication).
    - Header-specified constructor arguments are passed to direct bases.
  - Visibility enforcement under MI:
    - `private` visible only inside the declaring class body.
    - `protected` visible inside the declaring class and any of its transitive subclasses; unrelated contexts cannot access it (qualification/casts do not bypass).
  - Diagnostics improvements:
    - Missing member/field messages include receiver class and linearization order; hints for `this@Type` or casts when helpful.
    - Invalid `this@Type` reports that the qualifier is not an ancestor and shows the receiver lineage.
    - `as`/`as?` cast errors include actual and target type names.

- Documentation updated (docs/OOP.md and tutorial quick-start) to reflect MI with active C3 MRO.

Notes:
- Existing single-inheritance code continues to work; resolution reduces to the single base.
- If code previously relied on non-deterministic parent set iteration, C3 MRO provides a predictable order; disambiguate explicitly if needed using `this@Type`/casts.

# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

- CLI: Added `fmt` as a first-class Clikt subcommand.
  - Default behavior: formats files to stdout (no in-place edits by default).
  - Options:
    - `--check`: check only; print files that would change; exit with code 2 if any changes are needed.
    - `-i, --in-place`: write formatted result back to files.
    - `--spacing`: apply spacing normalization.
    - `--wrap`, `--wrapping`: enable line wrapping.
  - Mutually exclusive: `--check` and `--in-place` together now produce an error and exit with code 1.
  - Multi-file stdout prints headers `--- <path> ---` per file.
  - `lyng --help` shows `fmt`; `lyng fmt --help` displays dedicated help.

- CLI: Preserved legacy script invocation fast-paths:
  - `lyng script.lyng [args...]` executes the script directly.
  - `lyng -- -file.lyng [args...]` executes a script whose name begins with `-`.

- CLI: Fixed a regression where the root help banner could print before subcommands.
  - Root command no longer prints help when a subcommand (e.g., `fmt`) is invoked.
