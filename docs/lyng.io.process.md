### lyng.io.process — Process execution and control for Lyng scripts

This module provides a way to run external processes and shell commands from Lyng scripts. It is designed to be multiplatform and uses coroutines for non-blocking execution.

> **Note:** `lyngio` is a separate library module. It must be explicitly added as a dependency to your host application and initialized in your Lyng scopes.
> The reference `lyng`/`jlyng` CLI installs this module in its import manager, so CLI scripts can use `import lyng.io.process` directly.

---

#### Add the library to your project (Gradle)

If you use this repository as a multi-module project, add a dependency on `:lyngio`:

```kotlin
dependencies {
    implementation("net.sergeych:lyngio:0.0.1-SNAPSHOT")
}
```

For external projects, ensure you have the appropriate Maven repository configured (see `lyng.io.fs` documentation).

---

#### Install the module into a Lyng session

The process module is not installed automatically. The preferred host runtime is `EvalSession`: create the session, get its underlying scope, install the module there, and execute scripts through the session. You can customize access control via `ProcessAccessPolicy`.

Kotlin (host) bootstrap example:

```kotlin
import net.sergeych.lyng.Scope
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.io.process.createProcessModule
import net.sergeych.lyngio.process.security.PermitAllProcessAccessPolicy

suspend fun bootstrapProcess() {
    val session = EvalSession()
    val scope: Scope = session.getScope()
    createProcessModule(PermitAllProcessAccessPolicy, scope)

    // In scripts (or via session.eval), import the module:
    session.eval("import lyng.io.process")
}
```

---

#### Shell-script shorthand

Most CLI scripts should start with the shorthand API:

```lyng
import lyng.io.process

val branch = sh("git branch --show-current").out.trim()
println("Branch: " + branch)
```

`sh(command)` starts `command` through the platform shell and returns an active `CommandRun`.
The process is already running when the handle is returned. The handle gives you both convenient capture properties and streaming line flows:

```lyng
val run = sh("git status --short")

println(run.out)      // capture all stdout as String
println(run.err)      // capture all stderr as String
println(run.code)     // known after out/err/wait/check, otherwise null
println(run.ok)       // true, false, or null
```

Use `.check()` when a non-zero exit code should fail the script:

```lyng
sh("lyng fmt --check examples/*.lyng").check()
sh("git diff --check").check()
```

Use `.wait()` when the exit code is data and should not raise:

```lyng
val code = sh("git diff --quiet").wait()
if (code == 0) {
    println("clean")
} else {
    println("changed")
}
```

Use `.lines` for output that may be large. It streams stdout instead of buffering the entire output:

```lyng
for (file in sh("git ls-files").lines) {
    if (file.endsWith(".lyng")) {
        println(file)
    }
}
```

You can combine it with normal iterable helpers:

```lyng
val count = sh("git ls-files").lines.count {
    it.endsWith(".lyng")
}

println("Lyng files: " + count)
```

Use `exec(executable, args)` when arguments come from data, filenames, or user input:

```lyng
val file = ARGV[0] as String
exec("git", ["add", file]).check()
```

`exec(...)` bypasses the shell, so arguments are passed as argv entries rather than parsed by shell quoting rules.

```lyng
val status = exec("git", ["status", "--short"]).out
println(status)
```

---

#### Capture vs streaming

`CommandRun` is active: it owns a running process. Choose the consumption style intentionally.

- `.out` captures stdout into memory, waits for the process to finish, and also drains stderr concurrently.
- `.err` captures stderr into memory, waits for the process to finish, and also drains stdout concurrently.
- `.check()` captures both streams, waits, and fails if the exit code is non-zero.
- `.lines` streams stdout line by line and does not buffer the whole output.
- `.errorLines` streams stderr line by line and does not buffer the whole error output.
- `.wait()` only waits and returns the exit code.

Treat a `CommandRun` as a one-shot stream owner. Pick one stdout consumption path (`out` or `lines`) and one stderr consumption path (`err` or `errorLines`). Do not collect the same stream twice; it is backed by the process pipe.

For small output, capture is simplest:

```lyng
val version = sh("git describe --tags --always").out.trim()
```

For large output, stream:

```lyng
for (line in sh("find . -type f").lines) {
    if (line.endsWith(".lyng")) {
        println(line)
    }
}
```

For commands that may write a lot to both stdout and stderr, consume both streams. One practical pattern is to collect stderr in a background task while processing stdout:

```lyng
val r = sh("some-command-that-writes-a-lot")

val errors: List<String> = []
val stderrTask = launch {
    for (line in r.errorLines) {
        errors.add(line)
    }
}

for (line in r.lines) {
    println("OUT: " + line)
}

val code = r.wait()
stderrTask.await()

if (code != 0) {
    println(errors.joinToString("\n"))
    exit(code)
}
```

As with ordinary shell programming, avoid using `.out` for unbounded output. It is meant for command results that comfortably fit in memory.

---

#### Common script patterns

Capture one small value:

```lyng
val currentCommit = exec("git", ["rev-parse", "--short", "HEAD"]).out.trim()
```

Run a command for its side effects and fail on error:

```lyng
exec("mkdir", ["-p", "build/reports"]).check()
sh("lyng fmt --check src/**/*.lyng").check()
```

Filter a long output stream:

```lyng
for (path in sh("git ls-files").lines) {
    if (path.endsWith(".lyng")) {
        println(path)
    }
}
```

Branch on a command status:

```lyng
if (exec("git", ["diff", "--quiet"]).wait() != 0) {
    println("working tree has changes")
}
```

Keep user-controlled text out of the shell:

```lyng
val file = ARGV[0] as String
exec("git", ["log", "--oneline", "--", file]).check()
```

---

#### `sh` vs `exec`

Use `sh(...)` when you intentionally want shell syntax:

```lyng
sh("git status --short | wc -l").out.trim()
sh("find . -name '*.lyng' | sort").check()
```

Use `exec(...)` when you already have structured arguments:

```lyng
exec("git", ["commit", "-m", message]).check()
exec("cp", [sourcePath, targetPath]).check()
```

This distinction matters for safety. Shell commands are parsed by `/bin/sh -c` on Unix-like systems or the platform shell on supported platforms. If you interpolate untrusted text into a shell string, it can change the command being run. Prefer `exec(...)` for user input and filenames.

---

#### Low-level process API

The shorthand API is built on top of `Process.execute(...)` and `Process.shell(...)`. Use the low-level API when you need direct process control, including explicit signals or forceful termination:

```lyng
import lyng.io.process

// Execute a process with arguments
val p = Process.execute("ls", ["-l", "/tmp"])
for (line in p.stdout()) {
    println("OUT: " + line)
}
val exitCode = p.waitFor()
println("Process exited with: " + exitCode)

// Run a shell command
val shellProcess = Process.shell("echo 'Hello from shell' | wc -w")
for (line in shellProcess.stdout()) {
    println("Word count: " + line.trim())
}
```

Signals and termination:

```lyng
val server = Process.shell("python3 -m http.server 8080")
delay(1000)
server.signal("SIGTERM")
val code = server.waitFor()
println("server exited: " + code)
```

---

#### Platform information

```lyng
import lyng.io.process

// Platform information
val details = Platform.details()
println("OS: " + details.name + " " + details.version + " (" + details.arch + ")")
if (details.kernelVersion != null) {
    println("Kernel: " + details.kernelVersion)
}

if (Platform.isSupported()) {
    println("Processes are supported!")
}
```

---

#### Executable script example

```lyng
#!/usr/bin/env lyng

import lyng.io.process

if (ARGV.size == 0) {
    println("usage: changed-files.lyng <extension>")
    exit(2)
}

val ext = ARGV[0] as String
var matches = 0

for (file in sh("git status --short").lines) {
    if (file.endsWith(ext)) {
        println(file)
        matches++
    }
}

println("matched: " + matches)
```

---

#### API Reference

##### `Process` (static methods)
- `execute(executable: String, args: List<String>): RunningProcess` — Start an external process.
- `shell(command: String): RunningProcess` — Run a command through the system shell (e.g., `/bin/sh` or `cmd.exe`).

##### Shorthand functions
- `sh(command: String): CommandRun` — Run a command through the system shell and return an active handle.
- `exec(executable: String, args: List<String> = []): CommandRun` — Run an executable with argv-style arguments and return an active handle.

##### `CommandRun`
- `command: String` — Original command display text.
- `lines: Flow<String>` — Streaming stdout lines. Use for large output.
- `errorLines: Flow<String>` — Streaming stderr lines. Use for large error output.
- `out: String` — Captured stdout. Captures both stdout and stderr concurrently and waits for completion.
- `err: String` — Captured stderr. Captures both stdout and stderr concurrently and waits for completion.
- `wait(): Int` — Wait for the command to exit and return the exit code.
- `check(): CommandRun` — Capture output, wait, and fail if the exit code is non-zero.
- `code: Int?` — Known exit code after `wait`, `check`, `out`, or `err`; otherwise `null`.
- `ok: Bool?` — `true` for exit code 0, `false` for non-zero, or `null` before the exit code is known.

##### `RunningProcess` (instance methods)
- `stdout(): Flow` — Standard output stream as a Lyng Flow of lines.
- `stderr(): Flow` — Standard error stream as a Lyng Flow of lines.
- `waitFor(): Int` — Wait for the process to exit and return the exit code.
- `signal(name: String)` — Send a signal to the process (e.g., `"SIGINT"`, `"SIGTERM"`, `"SIGKILL"`).
- `destroy()` — Forcefully terminate the process.

##### `Platform` (static methods)
- `details(): Map` — Get platform details. Returned map keys: `name`, `version`, `arch`, `kernelVersion`.
- `isSupported(): Bool` — True if process execution is supported on the current platform.

---

#### Security Policy

Process execution is a sensitive operation. `lyngio` uses `ProcessAccessPolicy` to control access to `execute` and `shell` operations.

- `ProcessAccessPolicy` — Interface for custom policies.
- `PermitAllProcessAccessPolicy` — Allows all operations.
- `ProcessAccessOp` (sealed) — Operations to check:
    - `Execute(executable, args)`
    - `Shell(command)`

Example of a restricted policy in Kotlin:

```kotlin
import net.sergeych.lyngio.fs.security.AccessDecision
import net.sergeych.lyngio.fs.security.AccessContext
import net.sergeych.lyngio.fs.security.Decision
import net.sergeych.lyngio.process.security.ProcessAccessOp
import net.sergeych.lyngio.process.security.ProcessAccessPolicy

val restrictedPolicy = object : ProcessAccessPolicy {
    override suspend fun check(op: ProcessAccessOp, ctx: AccessContext): AccessDecision {
        return when (op) {
            is ProcessAccessOp.Execute -> {
                if (op.executable == "ls") AccessDecision(Decision.Allow)
                else AccessDecision(Decision.Deny, "Only 'ls' is allowed")
            }
            is ProcessAccessOp.Shell -> AccessDecision(Decision.Deny, "Shell is forbidden")
        }
    }
}
createProcessModule(restrictedPolicy, scope)
```

---

#### Platform Support

- **JVM:** Full support using `ProcessBuilder`.
- **Native (Linux/macOS):** Support via POSIX.
- **Windows:** Support planned.
- **Android/JS/iOS/Wasm:** Currently not supported; `isSupported()` returns `false` and attempts to run processes will throw `UnsupportedOperationException`.
