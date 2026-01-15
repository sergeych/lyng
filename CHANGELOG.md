## Changelog

### Unreleased

- Language: Refined `protected` visibility rules
  - Ancestor classes can now access `protected` members of their descendants, provided the ancestor also defines or inherits a member with the same name (indicating an override of a member known to the ancestor).
  - This allows patterns where a base class calls a `protected` method that is implemented in a subclass.
  - Fixed a regression where self-calls to unmangled members sometimes bypassed visibility checks incorrectly; these are now handled by the refined logic.

- Language: Added `return` statement
  - `return [expression]` exits the innermost enclosing callable (function or lambda).
  - Supports non-local returns using `@label` syntax (e.g., `return@outer 42`).
  - Named functions automatically provide their name as a label for non-local returns.
  - Labeled lambdas: lambdas can be explicitly labeled using `@label { ... }`.
  - Restriction: `return` is forbidden in shorthand function definitions (e.g., `fun f(x) = return x` is a syntax error).
  - Control Flow: `return` and `break` are now protected from being caught by user-defined `try-catch` blocks in Lyng.
  - Documentation: New `docs/return_statement.md` and updated `tutorial.md`.

- Language: stdlib improvements
  - Added `with(self, block)` function to `root.lyng` which executes a block with `this` set to the provided object.
- Language: Abstract Classes and Interfaces
  - Support for `abstract` modifier on classes, methods, and variables.
  - Introduced `interface` as a synonym for `abstract class`, supporting full state (constructors, fields, `init` blocks) and implementation by parts via MI.
  - New `closed` modifier (antonym to `open`) to prevent overriding class members.
  - Refined `override` logic: mandatory keyword when re-declaring members that exist in the ancestor chain (MRO).
  - MI Satisfaction: Abstract requirements are automatically satisfied by matching concrete members found later in the C3 MRO chain without requiring explicit proxy methods.
  - Integration: Updated highlighters (lynglib, lyngweb, IDEA plugin), IDEA completion, and Grazie grammar checking.
  - Documentation: Updated `docs/OOP.md` with sections on "Abstract Classes and Members", "Interfaces", and "Overriding and Virtual Dispatch".
- IDEA plugin: Improved natural language support and spellchecking
  - Disabled the limited built-in English and Technical dictionaries.
  - Enforced usage of the platform's standard Natural Languages (Grazie) and Spellchecker components.
  - Integrated `SpellCheckerManager` for word suggestions and validation, respecting users' personal and project dictionaries.
  - Added project-specific "learned words" support via `Lyng Formatter` settings and quick-fixes.
  - Enhanced fallback spellchecker for technical terms and Lyng-specific vocabulary.

- Language: Class properties with accessors
  - Support for `val` (read-only) and `var` (read-write) properties in classes.
  - Syntax: `val name [ : Type ] get() { body }` or `var name [ : Type ] get() { body } set(value) { body }`.
  - Laconic Expression Shorthand: `val prop get() = expression` and `var prop get() = read set(v) = write`.
  - Properties are pure accessors and do **not** have automatic backing fields.
  - Validation: `var` properties must have both accessors; `val` must have only a getter.
  - Integration: Updated TextMate grammar and IntelliJ plugin (highlighting + keywords).
  - Documentation: New "Properties" section in `docs/OOP.md`.

- Language: Restricted Setter Visibility
  - Support for `private set` and `protected set` modifiers on `var` fields and properties.
  - Allows members to be publicly readable but only writable from within the declaring class or its subclasses.
  - Enforcement at runtime: throws `AccessException` on unauthorized writes.
  - Supported only for declarations in class bodies (fields and properties).
  - Documentation: New "Restricted Setter Visibility" section in `docs/OOP.md`.

- Language: Late-initialized `val` fields in classes
  - Support for declaring `val` without an immediate initializer in class bodies.
  - Compulsory initialization: every late-init `val` must be assigned at least once within the class body or an `init` block.
  - Write-once enforcement: assigning to a `val` is allowed only if its current value is `Unset`.
  - Access protection: reading a late-init `val` before it is assigned returns the `Unset` singleton; using `Unset` for most operations throws an `UnsetException`.
  - Extension properties do not support late-init.
  - Documentation: New "Late-initialized `val` fields" and "The `Unset` singleton" sections in `docs/OOP.md`.

- Docs: OOP improvements
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
    - `protected` visible inside the declaring class and its transitive subclasses. Additionally, ancestor classes can access protected members of their descendants if it's an override of a member known to the ancestor. Unrelated contexts cannot access it (qualification/casts do not bypass).
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
  - Fix: Property accessors (`get`, `set`, `private set`, `protected set`) are now correctly indented relative to the property declaration.
  - Fix: Indentation now correctly carries over into blocks that start on extra‑indented lines (e.g., nested `if` statements or property accessor bodies).
  - Fix: Formatting Markdown files no longer deletes content in `.lyng` code fences and works correctly with injected files (resolves clobbering, `StringIndexOutOfBoundsException`, and `nonempty text is not covered by block` errors).

- CLI: Preserved legacy script invocation fast-paths:
  - `lyng script.lyng [args...]` executes the script directly.
  - `lyng -- -file.lyng [args...]` executes a script whose name begins with `-`.

- CLI: Fixed a regression where the root help banner could print before subcommands.
  - Root command no longer prints help when a subcommand (e.g., `fmt`) is invoked.
