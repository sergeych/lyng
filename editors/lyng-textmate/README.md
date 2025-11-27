Lyng TextMate grammar
======================

This folder contains a TextMate grammar for the Lyng language so you can get syntax highlighting quickly in:

- JetBrains IDEs (IntelliJ IDEA, Fleet, etc.) via “TextMate Bundles”
- VS Code (via “Install from VSIX” or by adding as an extension folder)

Files
-----
- `package.json` — VS Code–style wrapper that JetBrains IDEs can import as a TextMate bundle.
- `syntaxes/lyng.tmLanguage.json` — the grammar. It highlights:
  - Line and block comments (`//`, `/* */`)
  - Shebang line at file start (`#!...`)
  - Strings: single and double quotes with escapes
  - Char literals `'x'` with escapes
  - Numbers: decimal with underscores and exponents, and hex (`0x...`)
  - Keywords (control and declarations), boolean operator words (`and`, `or`, `not`, `in`, `is`, `as`, `as?`)
  - Composite textual operators: `not in`, `not is`
  - Constants: `true`, `false`, `null`, `this`
  - Annotations: `@name` (Unicode identifiers supported)
  - Labels: `name:` (Unicode identifiers supported)
  - Declarations: highlights declared names in `fun|fn name`, `class|enum Name`, `val|var name`
  - Types: built-ins (`Int|Real|String|Bool|Char|Regex`) and Capitalized identifiers (heuristic)
  - Operators including ranges (`..`, `..<`, `...`), null-safe (`?.`, `?[`, `?(`, `?{`, `?:`, `??`), arrows (`->`, `=>`, `::`), match operators (`=~`, `!~`), bitwise, arithmetic, etc.
  - Shuttle operator `<=>`
  - Division operator `/` (note: Lyng has no regex literal syntax; `/` is always division)
  - Named arguments at call sites `name: value` (the `name` part is highlighted as `variable.parameter.named.lyng` and the `:` as punctuation). The rule is anchored to `(` or `,` and excludes `::` to avoid conflicts.

Install in IntelliJ IDEA (and other JetBrains IDEs)
---------------------------------------------------
1. Open Settings / Preferences → Editor → TextMate Bundles.
2. Click “+” and select this folder `editors/lyng-textmate/` (the folder that contains `package.json`).
3. Ensure `*.lyng` is associated with the Lyng grammar (IntelliJ usually picks this up from `fileTypes`).
4. Optional: customize colors with Settings → Editor → Color Scheme → TextMate.

Enable Markdown code-fence highlighting in IntelliJ
--------------------------------------------------
1. Settings / Preferences → Languages & Frameworks → Markdown → Code style → Code fences → Languages.
2. Add mapping: language id `lyng` → “Lyng (TextMate)”.
3. Now blocks like
   ```
   ```lyng
   // Lyng code here
   ```
   ```
   will be highlighted.

Install in VS Code
------------------
Fastest local install without packaging:
1. Copy or symlink this folder somewhere stable (or keep it in your workspace).
2. Use “Developer: Install Extension from Location…” (Insiders) or package with `vsce package` and install the resulting `.vsix`.
3. VS Code will auto-associate `*.lyng` via this extension; if needed, check File Associations.

Notes and limitations
---------------------
- Type highlighting is heuristic (Capitalized identifiers). The IntelliJ plugin will use language semantics and avoid false positives.
- If your language adds or changes tokens, please update patterns in `lyng.tmLanguage.json`. The Kotlin sources in `lynglib/src/commonMain/kotlin/net/sergeych/lyng/highlight/` are a good reference for token kinds.
- Labels `name:` at statement level remain supported and are kept distinct from named call arguments by context. The grammar prefers named-argument matching when a `name:` appears right after `(` or `,`.

Lyng specifics
--------------
- There are no regex literal tokens in Lyng at the moment; the slash character `/` is always treated as the division operator. The grammar intentionally does not define a `/.../` regex rule to avoid mis-highlighting lines like `a / b`.

Contributing
------------
Pull requests to refine patterns and add tests/samples are welcome. You can place test snippets in `sample_texts/` and visually verify.
