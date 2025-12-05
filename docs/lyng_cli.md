### Lyng CLI (`lyng`)

The Lyng CLI is the reference command-line tool for the Lyng language. It lets you:

- Run Lyng scripts from files or inline strings (shebangs accepted)
- Use standard argument passing (`ARGV`) to your scripts.
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

This creates a JVM distribution with a launcher script and links it to `~/bin/jlyng`.

```
bin/local_jrelease
```

What this does:
- Runs `./gradlew :lyng:installJvmDist` to build the JVM app distribution to `lyng/build/install/lyng-jvm`.
- Copies the distribution under `~/bin/jlyng-jvm`.
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

```
lyng -x "println(\"Hello\")" more args
```

- Print version/help:

```
lyng --version
lyng --help
```

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
