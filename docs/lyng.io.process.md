### lyng.io.process — Process execution and control for Lyng scripts

This module provides a way to run external processes and shell commands from Lyng scripts. It is designed to be multiplatform and uses coroutines for non-blocking execution.

> **Note:** `lyngio` is a separate library module. It must be explicitly added as a dependency to your host application and initialized in your Lyng scopes.

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

#### Install the module into a Lyng Scope

The process module is not installed automatically. You must explicitly register it in the scope’s `ImportManager` using `createProcessModule`. You can customize access control via `ProcessAccessPolicy`.

Kotlin (host) bootstrap example:

```kotlin
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Script
import net.sergeych.lyng.io.process.createProcessModule
import net.sergeych.lyngio.process.security.PermitAllProcessAccessPolicy

// ... inside a suspend function or runBlocking
val scope: Scope = Script.newScope()
createProcessModule(PermitAllProcessAccessPolicy, scope)

// In scripts (or via scope.eval), import the module:
scope.eval("import lyng.io.process")
```

---

#### Using from Lyng scripts

```lyng
import lyng.io.process

// Execute a process with arguments
val p = Process.execute("ls", ["-l", "/tmp"])
for (line in p.stdout) {
    println("OUT: " + line)
}
val exitCode = p.waitFor()
println("Process exited with: " + exitCode)

// Run a shell command
val sh = Process.shell("echo 'Hello from shell' | wc -w")
for (line in sh.stdout) {
    println("Word count: " + line.trim())
}

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

#### API Reference

##### `Process` (static methods)
- `execute(executable: String, args: List<String>): RunningProcess` — Start an external process.
- `shell(command: String): RunningProcess` — Run a command through the system shell (e.g., `/bin/sh` or `cmd.exe`).

##### `RunningProcess` (instance methods)
- `stdout: Flow` — Standard output stream as a Lyng Flow of lines.
- `stderr: Flow` — Standard error stream as a Lyng Flow of lines.
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
