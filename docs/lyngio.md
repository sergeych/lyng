### lyngio — Extended I/O and System Library for Lyng

`lyngio` is a separate library that extends the Lyng core (`lynglib`) with powerful, multiplatform, and secure I/O capabilities.

#### Why a separate module?

1. **Security:** I/O and process execution are sensitive operations. By keeping them in a separate module, we ensure that the Lyng core remains 100% safe by default. You only enable what you explicitly need.
2. **Footprint:** Not every script needs filesystem or process access. Keeping these as a separate module helps minimize the dependency footprint for small embedded projects.
3. **Control:** `lyngio` provides fine-grained security policies (`FsAccessPolicy`, `ProcessAccessPolicy`, `ConsoleAccessPolicy`) that allow you to control exactly what a script can do.

#### Included Modules

- **[lyng.io.fs](lyng.io.fs.md):** Async filesystem access. Provides the `Path` class for file/directory operations, streaming, and globbing.
- **[lyng.io.process](lyng.io.process.md):** External process execution and shell commands. Provides `Process`, `RunningProcess`, and `Platform` information.
- **[lyng.io.console](lyng.io.console.md):** Rich console/TTY access. Provides `Console` capability detection, geometry, output, and iterable events.

---

#### Quick Start: Embedding lyngio

##### 1. Add Dependencies (Gradle)

```kotlin
repositories {
    maven("https://gitea.sergeych.net/api/packages/SergeychWorks/maven")
}

dependencies {
    // Both are required for full I/O support
    implementation("net.sergeych:lynglib:0.0.1-SNAPSHOT")
    implementation("net.sergeych:lyngio:0.0.1-SNAPSHOT")
}
```

##### 2. Initialize in Kotlin (JVM or Native)

To use `lyngio` modules in your scripts, you must install them into your Lyng scope and provide a security policy.

```kotlin
import net.sergeych.lyng.Script
import net.sergeych.lyng.io.fs.createFs
import net.sergeych.lyng.io.process.createProcessModule
import net.sergeych.lyng.io.console.createConsoleModule
import net.sergeych.lyngio.fs.security.PermitAllAccessPolicy
import net.sergeych.lyngio.process.security.PermitAllProcessAccessPolicy
import net.sergeych.lyngio.console.security.PermitAllConsoleAccessPolicy

suspend fun runMyScript() {
    val scope = Script.newScope()
    
    // Install modules with policies
    createFs(PermitAllAccessPolicy, scope)
    createProcessModule(PermitAllProcessAccessPolicy, scope)
    createConsoleModule(PermitAllConsoleAccessPolicy, scope)
    
    // Now scripts can import them
    scope.eval("""
        import lyng.io.fs
        import lyng.io.process
        import lyng.io.console
        
        println("Working dir: " + Path(".").readUtf8())
        println("OS: " + Platform.details().name)
        println("TTY: " + Console.isTty())
    """)
}
```

---

#### Security Tools

`lyngio` is built with a "Secure by Default" philosophy. Every I/O or process operation is checked against a policy.

- **Filesystem Security:** Implement `FsAccessPolicy` to restrict access to specific paths or operations (e.g., read-only access to a sandbox directory).
- **Process Security:** Implement `ProcessAccessPolicy` to restrict which executables can be run or to disable shell execution entirely.
- **Console Security:** Implement `ConsoleAccessPolicy` to control output writes, event reads, and raw mode switching.

For more details, see the specific module documentation:
- [Filesystem Security Details](lyng.io.fs.md#access-policy-security)
- [Process Security Details](lyng.io.process.md#security-policy)
- [Console Module Details](lyng.io.console.md)

---

#### Platform Support Overview

| Platform | lyng.io.fs | lyng.io.process | lyng.io.console |
| :--- | :---: | :---: | :---: |
| **JVM** | ✅ | ✅ | ✅ (baseline) |
| **Native (Linux/macOS)** | ✅ | ✅ | 🚧 |
| **Native (Windows)** | ✅ | 🚧 (Planned) | 🚧 |
| **Android** | ✅ | ❌ | ❌ |
| **NodeJS** | ✅ | ❌ | ❌ |
| **Browser / Wasm** | ✅ (In-memory) | ❌ | ❌ |
