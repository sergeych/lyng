### lyng.io.fs — async filesystem access for Lyng scripts

This module provides a uniform, suspend-first filesystem API to Lyng scripts, backed by Kotlin Multiplatform implementations.

- JVM/Android/Native: Okio `FileSystem.SYSTEM` (non-blocking via coroutine dispatcher)
- JS/Node: Node filesystem (currently via Okio node backend; a native `fs/promises` backend is planned)
- JS/Browser and Wasm: in-memory virtual filesystem for now

It exposes a Lyng class `Path` with methods for file and directory operations, including streaming readers for large files.

It is a separate library because access to teh filesystem is a security risk we compensate with a separate API that user must explicitly include to the dependency and allow. Together with `FsAceessPolicy` that is required to `createFs()` which actually adds the filesystem to the scope, the security risk is isolated.

Also, it helps keep Lyng core small and focused.

---

#### Add the library to your project (Gradle)

If you use this repository as a multi-module project, add a dependency on `:lyngio`:

```kotlin
dependencies {
    implementation("net.sergeych:lyngio:0.0.1-SNAPSHOT")
}
```
Note on maven repository. Lyngio uses ths same maven as Lyng code (`lynglib`) so it is most likely already in your project. If ont, add it to the proper section of your `build.gradle.kts` or settings.gradle.kts:

```kotlin
    repositories {
        maven("https://gitea.sergeych.net/api/packages/SergeychWorks/maven")
    }
```

This brings in:

- `:lynglib` (Lyng engine)
- Okio (`okio`, `okio-fakefilesystem`, and `okio-nodefilesystem` for JS)
- Kotlin coroutines

---

#### Install the module into a Lyng Scope

The filesystem module is not installed automatically. You must explicitly register it in the scope’s `ImportManager` using the installer. You can customize access control via `FsAccessPolicy`.

Kotlin (host) bootstrap example (imports omitted for brevity):

```kotlin
val scope: Scope = Scope.new()
val installed: Boolean = createFs(PermitAllAccessPolicy, scope)
// installed == true on first registration in this ImportManager, false on repeats

// In scripts (or via scope.eval), import the module to use its symbols:
scope.eval("import lyng.io.fs")
```

You can install with a custom policy too (see Access policy below).

---

#### Using from Lyng scripts

```lyng
val p = Path("/tmp/hello.txt")

// Text I/O
p.writeUtf8("Hello Lyng!\n")
println(p.readUtf8())

// Binary I/O
val data = Buffer.fromHex("deadbeef")
p.writeBytes(data)

// Existence and directories
assertTrue(p.exists())
Path("/tmp/work").mkdirs()

// Listing
for (entry in Path("/tmp").list()) {
    println(entry)
}

// Globbing
val txts = Path("/tmp").glob("**/*.txt").toList()

// Copy / Move / Delete
Path("/tmp/a.txt").copy("/tmp/b.txt", overwrite=true)
Path("/tmp/b.txt").move("/tmp/c.txt", overwrite=true)
Path("/tmp/c.txt").delete()

// Streaming large files (does not load whole file into memory)
var bytes = 0
val it = Path("/tmp/big.bin").readChunks(1_048_576) // 1MB chunks
val iter = it.iterator()
while (iter.hasNext()) {
    val chunk = iter.next()
    bytes = bytes + chunk.size()
}

// Text chunks and lines
for (s in Path("/tmp/big.txt").readUtf8Chunks(64_000)) {
    // process each string chunk
}

for (ln in Path("/tmp/big.txt").lines()) {
    // process line by line
}
```

---

#### API (Lyng class `Path`)

Constructor:
- `Path(path: String)` — creates a Path object
- `Paths(path: String)` — alias

File and directory operations (all suspend under the hood):
- `name`: name, `String`
- `segments`: list of parsed path segments (directories)
- `parent`: parent directory, `Path?`; null if root
- `exists(): Bool`
- `isFile(): Bool` — true if the path points to a regular file (cached metadata)
- `isDirectory(): Bool` — true if the path points to a directory (cached metadata)
- `size(): Int?` — size in bytes or null if unknown (cached metadata)
- `createdAt(): Instant?` — creation time as Lyng `Instant`, or null (cached metadata)
- `createdAtMillis(): Int?` — creation time in epoch milliseconds, or null (cached metadata)
- `modifiedAt(): Instant?` — last modification time as Lyng `Instant`, or null (cached metadata)
- `modifiedAtMillis(): Int?` — last modification time in epoch milliseconds, or null (cached metadata)
- `list(): List<Path>` — children of a directory
- `readBytes(): Buffer`
- `writeBytes(bytes: Buffer)`
- `appendBytes(bytes: Buffer)`
- `readUtf8(): String`
- `writeUtf8(text: String)`
- `appendUtf8(text: String)`
- `metadata(): Map` — keys: `isFile`, `isDirectory`, `size`, `createdAtMillis`, `modifiedAtMillis`, `isSymlink`
- `mkdirs(mustCreate: Bool = false)`
- `move(to: Path|String, overwrite: Bool = false)`
- `delete(mustExist: Bool = false, recursively: Bool = false)`
- `copy(to: Path|String, overwrite: Bool = false)`
- `glob(pattern: String): List<Path>` — supports `**`, `*`, `?` (POSIX-style)

Streaming readers for big files:
- `readChunks(size: Int = 65536): Iterator<Buffer>` — iterate fixed-size byte chunks
- `readUtf8Chunks(size: Int = 65536): Iterator<String>` — iterate text chunks by character count
- `lines(): Iterator<String>` — line iterator built on `readUtf8Chunks`

Notes:
- Iterators implement Lyng iterator protocol. If you break early from a loop, the runtime will attempt to call `cancelIteration()` when available.
- Current implementations chunk in memory. The public API is stable; internals will evolve to true streaming on all platforms.
- Attribute accessors (`isFile`, `isDirectory`, `size`, `createdAt*`, `modifiedAt*`) cache a metadata snapshot inside the `Path` instance to avoid repeated filesystem calls during a sequence of queries. `metadata()` remains available for bulk access.

---

#### Access policy (security)

Access control is enforced by `FsAccessPolicy`. You pass a policy at installation time. The module wraps the filesystem with a secured decorator that consults the policy for each primitive operation.

Main types:
- `FsAccessPolicy` — your policy implementation
- `PermitAllAccessPolicy` — allows all operations (default for testing)
- `AccessOp` (sealed) — operations the policy can decide on:
  - `ListDir(path)`
  - `CreateFile(path)`
  - `OpenRead(path)`
  - `OpenWrite(path)`
  - `OpenAppend(path)`
  - `Delete(path)`
  - `Rename(from, to)`
  - `UpdateAttributes(path)` — defaults to write-level semantics

Minimal denying policy example (imports omitted for brevity):

```kotlin
val denyWrites = object : FsAccessPolicy {
    override suspend fun check(op: AccessOp, ctx: AccessContext): AccessDecision = when (op) {
        is AccessOp.OpenRead, is AccessOp.ListDir -> AccessDecision(Decision.Allow)
        else -> AccessDecision(Decision.Deny, reason = "read-only policy")
    }
}

createFs(denyWrites, scope)
scope.eval("import lyng.io.fs")
```

Composite operations like `copy` and `move` are checked as a set of primitives (e.g., `OpenRead(src)` + `Delete(dst)` if overwriting + `CreateFile(dst)` + `OpenWrite(dst)`).

---

#### Errors and exceptions

Policy denials are surfaced as Lyng runtime errors, not raw Kotlin exceptions:
- Internally, a denial throws `AccessDeniedException`. The module maps it to `ObjIllegalOperationException` wrapped into an `ExecutionError` visible to scripts.

Examples (Lyng):

```lyng
import lyng.io.fs
val p = Path("/protected/file.txt")
try {
    p.writeUtf8("x")
    fail("expected error")
} catch (e) {
    // e is an ExecutionError; message contains the policy reason
}
```

Other I/O failures (e.g., not found, not a directory) are also raised as Lyng errors (`ObjIllegalStateException`, `ObjIllegalArgumentException`, etc.) depending on context.

---

#### Platform notes

- JVM/Android/Native: synchronous Okio calls are executed on `Dispatchers.IO` (JVM/Android) or `Dispatchers.Default` (Native) to avoid blocking the main thread.
- NodeJS: currently uses Okio’s Node backend. For heavy I/O, a native `fs/promises` backend is planned to fully avoid event-loop blocking.
- Browser/Wasm: uses an in-memory filesystem for now. Persistent backends (IndexedDB or File System Access API) are planned.

---

#### Roadmap

- Native NodeJS backend using `fs/promises`
- Browser persistent storage (IndexedDB)
- Streaming readers/writers over real OS streams
- Attribute setters and richer metadata

If you have specific needs (e.g., sandboxing, virtual roots), implement a custom `FsAccessPolicy` or ask us to add a helper.
