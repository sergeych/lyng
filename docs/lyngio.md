### lyngio — Extended I/O and System Library for Lyng

`lyngio` is a separate library that extends the Lyng core (`lynglib`) with powerful, multiplatform, and secure I/O capabilities.

> **Important native networking limit:** `lyng.io.net` on current native targets is suitable for modest workloads, local tools, and test servers, but not yet for high-connection-count production servers. For serious HTTP/TCP serving, prefer the JVM target for now. If native high-concurrency networking matters for your use case, please open or upvote an issue at <https://github.com/sergeych/lyng/issues>.

#### Why a separate module?

1. **Security:** I/O and process execution are sensitive operations. By keeping them in a separate module, we ensure that the Lyng core remains 100% safe by default. You only enable what you explicitly need.
2. **Footprint:** Not every script needs filesystem or process access. Keeping these as a separate module helps minimize the dependency footprint for small embedded projects.
3. **Control:** `lyngio` provides fine-grained security policies (`FsAccessPolicy`, `ProcessAccessPolicy`, `ConsoleAccessPolicy`) that allow you to control exactly what a script can do.

#### Included Modules

- **[lyng.io.db](lyng.io.db.md):** Portable SQL database access. Provides `Database`, `SqlTransaction`, `ResultSet`, SQLite support through `lyng.io.db.sqlite`, and JVM JDBC support through `lyng.io.db.jdbc`.
- **[lyng.io.fs](lyng.io.fs.md):** Async filesystem access. Provides the `Path` class for file/directory operations, streaming, and globbing.
- **[lyng.io.process](lyng.io.process.md):** External process execution and shell commands. Provides `Process`, `RunningProcess`, and `Platform` information.
- **[lyng.io.console](lyng.io.console.md):** Rich console/TTY access. Provides `Console` capability detection, geometry, output, and iterable events.
- **[lyng.io.http](lyng.io.http.md):** HTTP/HTTPS client access. Provides `Http`, `HttpRequest`, `HttpResponse`, and `HttpHeaders`.
- **[lyng.io.http.server](lyng.io.http.server.md):** Minimal HTTP/1.1 and WebSocket server. Provides `HttpServer`, `Router`, `ServerRequest`, `RequestContext`, and `ServerWebSocket`.
- **[lyng.io.ws](lyng.io.ws.md):** WebSocket client access. Provides `Ws`, `WsSession`, and `WsMessage`.
- **[lyng.io.net](lyng.io.net.md):** Transport networking. Provides `Net`, `TcpSocket`, `TcpServer`, `UdpSocket`, and `SocketAddress`.
- **Shared networking type packages:** `lyng.io.http.types`, `lyng.io.ws.types`, and `lyng.io.net.types` expose reusable value types such as `HttpHeaders`, `WsMessage`, `IpVersion`, `SocketAddress`, and `Datagram` when host code wants type-only imports without installing the corresponding capability object module.

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
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.io.db.createDbModule
import net.sergeych.lyng.io.db.jdbc.createJdbcModule
import net.sergeych.lyng.io.db.sqlite.createSqliteModule
import net.sergeych.lyng.io.fs.createFs
import net.sergeych.lyng.io.process.createProcessModule
import net.sergeych.lyng.io.console.createConsoleModule
import net.sergeych.lyng.io.http.createHttpModule
import net.sergeych.lyng.io.net.createNetModule
import net.sergeych.lyng.io.ws.createWsModule
import net.sergeych.lyngio.fs.security.PermitAllAccessPolicy
import net.sergeych.lyngio.process.security.PermitAllProcessAccessPolicy
import net.sergeych.lyngio.console.security.PermitAllConsoleAccessPolicy
import net.sergeych.lyngio.http.security.PermitAllHttpAccessPolicy
import net.sergeych.lyngio.net.security.PermitAllNetAccessPolicy
import net.sergeych.lyngio.ws.security.PermitAllWsAccessPolicy

suspend fun runMyScript() {
    val session = EvalSession()
    val scope = session.getScope()
    
    // Install modules with policies
    createDbModule(scope)
    createJdbcModule(scope)
    createSqliteModule(scope)
    createFs(PermitAllAccessPolicy, scope)
    createProcessModule(PermitAllProcessAccessPolicy, scope)
    createConsoleModule(PermitAllConsoleAccessPolicy, scope)
    createHttpModule(PermitAllHttpAccessPolicy, scope)
    createNetModule(PermitAllNetAccessPolicy, scope)
    createWsModule(PermitAllWsAccessPolicy, scope)
    
    // Now scripts can import them
    session.eval("""
        import lyng.io.db
        import lyng.io.db.jdbc
        import lyng.io.db.sqlite
        import lyng.io.fs
        import lyng.io.process
        import lyng.io.console
        import lyng.io.http
        import lyng.io.net
        import lyng.io.ws
        
        println("H2 JDBC available: " + (openH2("mem:demo;DB_CLOSE_DELAY=-1") != null))
        println("SQLite available: " + (openSqlite(":memory:") != null))
        println("Working dir: " + Path(".").readUtf8())
        println("OS: " + Platform.details().name)
        println("TTY: " + Console.isTty())
        println("HTTP available: " + Http.isSupported())
        println("TCP available: " + Net.isTcpAvailable())
        println("WS available: " + Ws.isSupported())
    """)
}
```

---

#### Security Tools

`lyngio` is built with a "Secure by Default" philosophy. Every I/O or process operation is checked against a policy.

- **Filesystem Security:** Implement `FsAccessPolicy` to restrict access to specific paths or operations (e.g., read-only access to a sandbox directory).
- **Database Installation:** Database access is still explicit-capability style. The host must install `lyng.io.db` and at least one provider such as `lyng.io.db.sqlite` or `lyng.io.db.jdbc`; otherwise scripts cannot open databases.
- **Process Security:** Implement `ProcessAccessPolicy` to restrict which executables can be run or to disable shell execution entirely.
- **Console Security:** Implement `ConsoleAccessPolicy` to control output writes, event reads, and raw mode switching.
- **HTTP Security:** Implement `HttpAccessPolicy` to restrict which requests scripts may send.
- **Transport Security:** Implement `NetAccessPolicy` to restrict DNS resolution and TCP/UDP socket operations.
- **WebSocket Security:** Implement `WsAccessPolicy` to restrict websocket connects and message flow.

For more details, see the specific module documentation:
- [Database Module Details](lyng.io.db.md)
- [Filesystem Security Details](lyng.io.fs.md#access-policy-security)
- [Process Security Details](lyng.io.process.md#security-policy)
- [Console Module Details](lyng.io.console.md)
- [HTTP Module Details](lyng.io.http.md)
- [HTTP Server Module Details](lyng.io.http.server.md)
- [Transport Networking Details](lyng.io.net.md)
- [WebSocket Module Details](lyng.io.ws.md)

---

#### Platform Support Overview

| Platform | lyng.io.db/sqlite | lyng.io.db/jdbc | lyng.io.fs | lyng.io.process | lyng.io.console | lyng.io.http | lyng.io.ws | lyng.io.net |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **JVM** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Linux Native** | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Apple Native** | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ |
| **Windows Native** | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ | ❌ |
| **Android** | ⚠️ | ❌ | ✅ | ❌ | ⚠️ | ✅ | ✅ | ✅ |
| **JS / Node** | ❌ | ❌ | ✅ | ❌ | ⚠️ | ✅ | ✅ | ✅ |
| **JS / Browser** | ❌ | ❌ | ✅ (in-memory) | ❌ | ⚠️ | ✅ | ✅ | ❌ |
| **Wasm** | ❌ | ❌ | ✅ (in-memory) | ❌ | ⚠️ | ❌ | ❌ | ❌ |

Legend:
- `✅` supported
- `⚠️` available but environment-dependent or not fully host-verified yet
- `❌` unsupported
