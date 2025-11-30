# Lyng formatter (core, CLI, and IDE)

This document describes the Lyng code formatter included in this repository. The formatter lives in the core library (`:lynglib`), is available from the CLI (`lyng fmt`), and is used by the IntelliJ plugin.

## Core library

Package: `net.sergeych.lyng.format`

- `LyngFormatConfig`
  - `indentSize` (default 4)
  - `useTabs` (default false)
  - `continuationIndentSize` (default 8)
  - `maxLineLength` (default 120)
  - `applySpacing` (default false)
  - `applyWrapping` (default false)
- `LyngFormatter`
  - `reindent(text, config)` — recomputes indentation from scratch (braces, `else/catch/finally` alignment, continuation indent under `(` `)` and `[` `]`), idempotent.
  - `format(text, config)` — runs `reindent` and, depending on `config`, optionally applies:
    - a safe spacing pass (commas/operators/colons/keyword parens; member access `.` remains tight; no changes to strings/comments), and
    - a controlled wrapping pass for long call arguments (no trailing commas).

Both passes are designed to be idempotent. Extensive tests live under `:lynglib/src/commonTest/.../format`.

## CLI formatter

```
lyng fmt [--check] [--in-place|-i] [--spacing] [--wrap] <file1.lyng> [file2.lyng ...]
```

- Defaults: indent-only; spacing and wrapping are OFF unless flags are provided.
- `--check` prints files that would change and exits with code 2 if any changes are detected.
- `--in-place`/`-i` rewrites files in place (default if not using `--check`).
- `--spacing` enables the safe spacing pass (commas/operators/colons/keyword parens).
- `--wrap` enables controlled wrapping of long call argument lists (respects `maxLineLength`, no trailing commas).

Examples:

```
# check formatting without modifying files
lyng fmt --check docs/samples/fs_sample.lyng

# format in place with spacing rules enabled
lyng fmt --spacing -i docs/samples/fs_sample.lyng

# format in place with spacing + wrapping
lyng fmt --spacing --wrap -i src/**/*.lyng
```

## IntelliJ plugin

- Indentation: always enabled, idempotent; the plugin computes per-line indent via the core formatter.
- Spacing/wrapping: optional and OFF by default.
  - Settings/Preferences → Lyng Formatter provides toggles:
    - "Enable spacing normalization (commas/operators/colons/keyword parens)"
    - "Enable line wrapping (120 cols) [experimental]"
  - Reformat Code applies: indentation first, then spacing, then wrapping if toggled.

## Design notes

- Single source of truth: The core formatter is used by CLI and IDE to keep behavior consistent.
- Stability first: Spacing/wrapping are gated by flags/toggles; indentation from scratch is always safe and idempotent.
- Non-destructive: The formatter carefully avoids changing string/char literals and comment contents.
