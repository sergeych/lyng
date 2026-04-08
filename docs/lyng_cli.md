### Lyng CLI (`lyng`)

The Lyng CLI is the reference command-line tool for the Lyng language. It lets you:

- Run Lyng scripts from files or inline strings (shebangs accepted)
- Use standard argument passing (`ARGV`) to your scripts.
- Resolve local file imports from the executed script's directory tree.
- Format Lyng source files via the built-in `fmt` subcommand.


#### Building on Linux

Requirements:
- JDK 17+ (for Gradle and the JVM distribution)
- GNU zip utilities (for packaging the native executable)
- upx tool (executable in-place compression)

The repository provides convenience scripts in `bin/` for local builds and installation into `~/bin`.

Note: In this repository the scripts are named `bin/local_release` and `bin/local_jrelease`. In some environments these may be aliased as `bin/release` and `bin/jrelease`. The steps below use the actual file names present here.


##### Option A: Native linuxX64 executable (`lyng`)

1) Build the native binary:

```
./gradlew :lyng:linkReleaseExecutableLinuxX64
```

2) Install and package locally:

```
bin/local_release
```

What this does:
- Copies the built executable to `~/bin/lyng` for easy use in your shell.
- Produces `distributables/lyng-linuxX64.zip` containing the `lyng` executable.


##### Option B: JVM distribution (`jlyng` launcher)

This creates a JVM distribution with a launcher script, packages it as a downloadable zip, and links it to `~/bin/jlyng`.

```
bin/local_jrelease
```

What this does:
- Runs `./gradlew :lyng:jvmDistZip` to build the JVM app distribution archive at `lyng/build/distributions/lyng-jvm.zip`.
- Copies the archive to `distributables/lyng-jvm.zip`.
- Unpacks that distribution under `~/bin/jlyng-jvm`.
- Creates a symlink `~/bin/jlyng` pointing to the launcher script.


#### Usage

Once installed, ensure `~/bin` is on your `PATH`. You can then use either the native `lyng` or the JVM `jlyng` launcher (both have the same CLI surface).


##### Running scripts

- Run a script by file name and pass arguments to `ARGV`:

```
lyng path/to/script.lyng arg1 arg2
```

- Run a script whose name starts with `-` using `--` to stop option parsing:

```
lyng -- -my-script.lyng arg1 arg2
```

- Execute inline code with `-x/--execute` and pass positional args to `ARGV`:
  - Inline execution does not scan the filesystem for local modules; only file-based execution does.

```
lyng -x "println(\"Hello\")" more args
```

- Print version/help:

```
lyng --version
lyng --help
```

##### Local imports for file execution

When you execute a script file, the CLI builds a temporary local import manager rooted at the directory that contains the entry script.

Formal structure:

- Root directory: the parent directory of the script passed to `lyng`.
- Scan scope: every `.lyng` file under that root directory, recursively.
- Entry script: the executed file itself is not registered as an importable module.
- Module name mapping: `relative/path/to/file.lyng` maps to import name `relative.path.to.file`.
- Package declaration: if a scanned file starts with `package ...` as its first non-blank line, that package name must exactly match the relative path mapping.
- Package omission: if there is no leading `package` declaration, the CLI uses the relative path mapping as the module name.
- Duplicates: if two files resolve to the same module name, CLI execution fails before script execution starts.
- Import visibility: only files inside the entry root subtree are considered. Parent directories and sibling projects are not searched.

Examples:

```
project/
  main.lyng
  util/answer.lyng
  math/add.lyng
```

`util/answer.lyng` is imported as `import util.answer`.

`math/add.lyng` is imported as `import math.add`.

Example contents:

```lyng
// util/answer.lyng
package util.answer

import math.add

fun answer() = plus(40, 2)
```

```lyng
// math/add.lyng
fun plus(a, b) = a + b
```

```lyng
// main.lyng
import util.answer

println(answer())
```

Rationale:

- The module name is deterministic from the filesystem layout.
- Explicit `package` remains available as a consistency check instead of a second, conflicting naming system.
- The import search space stays local to the executed script, which avoids accidental cross-project resolution.

### Use in shell scripts

Standard unix shebangs (`#!`) are supported, so you can make Lyng scripts directly executable on Unix-like systems. For example:

    #!/usr/bin/env lyng
    println("Hello, world!")


##### Formatting source: `fmt` subcommand

Format Lyng files with the built-in formatter.

Basic usage:

```
lyng fmt [OPTIONS] FILE...
```

Options:
- `--check` — Check-only mode. Prints file paths that would change and exits with code 2 if any changes are needed, 0 otherwise.
- `-i, --in-place` — Write formatted content back to the source files (off by default).
- `--spacing` — Apply spacing normalization.
- `--wrap`, `--wrapping` — Enable line wrapping.

Semantics and exit codes:
- Default behavior is to write formatted content to stdout. When multiple files are provided, the output is separated with `--- <path> ---` headers.
- `--check` and `--in-place` are mutually exclusive; using both results in an error and exit code 1.
- `--check` exits with 2 if any file would change, with 0 otherwise.
- Other errors (e.g., I/O issues) result in a non-zero exit code.

Examples:

```
# Print formatted content to stdout
lyng fmt src/file.lyng

# Format multiple files to stdout with headers
lyng fmt src/a.lyng src/b.lyng

# Check mode: list files that would change; exit 2 if changes are needed
lyng fmt --check src/**/*.lyng

# In-place formatting
lyng fmt -i src/**/*.lyng

# Enable spacing normalization and wrapping
lyng fmt --spacing --wrap src/file.lyng
```


#### Notes

- Both native and JVM distributions expose the same CLI interface. Use whichever best fits your environment.
- When executing scripts, all positional arguments after the script name are available in Lyng as `ARGV`.
- The interpreter recognizes shebang lines (`#!`) at the beginning of a script file and ignores them at runtime, so you can make Lyng scripts directly executable on Unix-like systems.
