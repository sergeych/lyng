# Proposal: Ktor-Backed Networking Modules For `lyngio`

Status: Draft  
Date: 2026-04-02  
Owner: `lyngio`

## Context

`lyngio` currently provides optional modules for filesystem, process execution, and console access. Networking is still missing.

The first networking release should optimize for:

- broad multiplatform reach from day one
- a stable Lyng-native API surface
- small implementation cost by building on existing, well-tested KMP libraries
- explicit capability discovery, because not every transport exists on every platform

The proposed implementation base is:

- `io.ktor:ktor-client-*` for HTTP/HTTPS
- Ktor WebSockets client on top of the HTTP client for `ws` and `wss`
- `io.ktor:ktor-network` for TCP client/server sockets and UDP datagrams
- no `ktor-server`

The critical design constraint is that Lyng must not expose Ktor types directly. Ktor is an implementation dependency, not the public scripting model.

## Goals

- Add first-release networking support with a practical breadth of features:
  - HTTP/HTTPS client
  - WebSocket client (`ws` / `wss`)
  - TCP client socket
  - TCP server socket where supported
  - UDP socket with a minimal datagram API where supported
- Keep the Lyng-facing API uniform across platforms.
- Provide capability discovery functions so scripts can branch safely.
- Keep declarations in `.lyng` files as the source of truth.
- Preserve room for later lower-level or non-universal socket APIs.

## Non-goals (phase 1)

- exposing Ktor classes, channels, frames, engines, or plugins directly to Lyng
- HTTP server support
- TLS configuration beyond default secure client behavior
- fully general stream/channel APIs for every transport
- advanced socket tuning beyond a minimal useful set
- promising identical runtime support on every platform

## Proposed module split

Use three Lyng modules instead of one monolith:

- `lyng.io.http`
- `lyng.io.ws`
- `lyng.io.net`

Rationale:

- HTTP and WebSocket are much more portable than raw sockets.
- TCP/UDP belong in a transport module, not in the HTTP client API.
- Each module can have its own capability checks while still sharing common address and security types underneath.
- The user-facing import structure stays clear and unsurprising.

## Uniform capability model

Every module should expose explicit capability discovery.

Recommended shape:

- `Http.isSupported(): Bool`
- `Ws.isSupported(): Bool`
- `Net.isSupported(): Bool`
- `Net.isTcpAvailable(): Bool`
- `Net.isTcpServerAvailable(): Bool`
- `Net.isUdpAvailable(): Bool`

Optional richer form for later:

- `Http.details(): HttpSupport`
- `Ws.details(): WsSupport`
- `Net.details(): NetSupport`

For v1, booleans are enough.

Scripts should be able to write:

```lyng
if (Net.isTcpServerAvailable()) {
    val server = Net.tcpListen(4040)
}
```

This is preferable to hard-failing during import or construction.

## Design principles

### 1. Lyng-native API, Ktor-backed implementation

Public Lyng objects should be small and script-oriented.

Do not expose:

- `HttpClient`
- `HttpResponse`
- `ByteReadChannel`
- `ByteWriteChannel`
- `Frame`
- Ktor engine names
- Ktor pipeline/plugin concepts

Instead, expose a narrow stable surface implemented internally via Ktor.

### 2. Minimal but broad v1

The first release should support more transport kinds, but only with the smallest coherent API for each kind.

That means:

- HTTP: request/response, headers, text/body bytes, status, method
- WebSocket: connect, send text/binary, receive text/binary, close
- TCP: connect, accept, read, write, close
- UDP: bind, send datagram, receive datagram, close

Notably absent from v1:

- interceptors
- cookies/session stores beyond defaults
- multipart builders
- HTTP/2 tuning knobs
- ping/pong tuning for WebSockets
- socket option explosion

### 3. Uniform shapes where practical

Across transports, prefer the same ideas:

- `isOpen()`
- `close()`
- `localAddress()`
- `remoteAddress()` when meaningful
- `read(...)`, `write(...)`
- `receive()`, `send(...)` for datagrams/messages
- `isSupported()` / capability methods on module singletons

This keeps discovery predictable without forcing false sameness where semantics differ.

## Proposed Lyng modules

### `lyng.io.http`

Purpose:

- high-level HTTP/HTTPS client backed by Ktor client

Recommended declaration sketch:

```lyng
package lyng.io.http

extern class HttpHeaders : Map<String, String> {
    fun get(name: String): String?
    fun getAll(name: String): List<String>
    fun names(): List<String>
}

extern class HttpRequest {
    var method: String
    var url: String
    var headers: Map<String, String>
    var bodyText: String?
    var bodyBytes: Buffer?
    var timeoutMillis: Int?
}

extern class HttpResponse {
    val status: Int
    val statusText: String
    val headers: HttpHeaders
    fun text(): String
    fun bytes(): Buffer
}

extern object Http {
    fun isSupported(): Bool
    fun request(req: HttpRequest): HttpResponse
    fun get(url: String, headers...): HttpResponse
    fun post(url: String, bodyText: String = "", contentType: String? = null, headers...): HttpResponse
    fun postBytes(url: String, body: Buffer, contentType: String? = null, headers...): HttpResponse
}
```

Notes:

- `HttpRequest` is mutable because it is ergonomic in scripts.
- `headers` is intentionally simple in v1: `Map<String, String>` for programmatic request construction.
- `HttpHeaders` is primarily for response headers, where repeated names matter.
- `text()` and `bytes()` should cache decoded content inside the response object.
- For convenience methods, `headers...` should accept `MapEntry` values such as `"X-Token" => "abc"` and `[key, value]` pairs, then normalize them internally into request headers.

### `lyng.io.ws`

Purpose:

- WebSocket client built on Ktor client WebSockets

Recommended declaration sketch:

```lyng
package lyng.io.ws

extern class WsMessage {
    val isText: Bool
    val text: String?
    val data: Buffer?
}

extern class WsSession {
    fun isOpen(): Bool
    fun url(): String
    fun sendText(text: String): void
    fun sendBytes(data: Buffer): void
    fun receive(): WsMessage?
    fun close(code: Int = 1000, reason: String = ""): void
}

extern object Ws {
    fun isSupported(): Bool
    fun connect(url: String, headers...): WsSession
}
```

Notes:

- Keep frames hidden. Lyng sees messages.
- `receive()` returns `null` on clean close.
- v1 supports text and binary messages only.
- `headers...` should follow the same rules as `Http.get(...)`: `MapEntry` values or `[key, value]` pairs.

### `lyng.io.net`

Purpose:

- transport sockets backed by `ktor-network`

Recommended declaration sketch:

```lyng
package lyng.io.net

enum IpVersion {
    IPV4,
    IPV6
}

extern class SocketAddress {
    val host: String
    val port: Int
    val ipVersion: IpVersion
    val resolved: Bool
    fun toString(): String
}

extern class Datagram {
    val data: Buffer
    val address: SocketAddress
}

extern class TcpSocket {
    fun isOpen(): Bool
    fun localAddress(): SocketAddress
    fun remoteAddress(): SocketAddress
    fun read(maxBytes: Int = 65536): Buffer?
    fun readLine(): String?
    fun write(data: Buffer): void
    fun writeUtf8(text: String): void
    fun flush(): void
    fun close(): void
}

extern class TcpServer {
    fun isOpen(): Bool
    fun localAddress(): SocketAddress
    fun accept(): TcpSocket
    fun close(): void
}

extern class UdpSocket {
    fun isOpen(): Bool
    fun localAddress(): SocketAddress
    fun receive(maxBytes: Int = 65536): Datagram?
    fun send(data: Buffer, host: String, port: Int): void
    fun close(): void
}

extern object Net {
    fun isSupported(): Bool
    fun isTcpAvailable(): Bool
    fun isTcpServerAvailable(): Bool
    fun isUdpAvailable(): Bool

    fun resolve(host: String, port: Int): List<SocketAddress>

    fun tcpConnect(
        host: String,
        port: Int,
        timeoutMillis: Int? = null,
        noDelay: Bool = true
    ): TcpSocket

    fun tcpListen(
        port: Int,
        host: String? = null,
        backlog: Int = 128,
        reuseAddress: Bool = true
    ): TcpServer

    fun udpBind(
        port: Int = 0,
        host: String? = null,
        reuseAddress: Bool = true
    ): UdpSocket
}
```

Notes:

- UDP is included in v1, but only with a minimal datagram API.
- `udpBind` should bind an ephemeral port by default.
- TCP server methods exist only where the backend supports them, but the declaration stays uniform.

## Example usage

HTTP:

```lyng
import lyng.io.http

if (!Http.isSupported())
    throw IllegalStateException("http is not supported here")

val r = Http.get(
    "https://example.com",
    "Accept" => "text/plain",
    "X-Trace" => "demo"
)
println(r.status)
println(r.text())
```

WebSocket:

```lyng
import lyng.io.ws

if (Ws.isSupported()) {
    val ws = Ws.connect(
        "wss://echo.example/ws",
        "Authorization" => "Bearer <token>"
    )
    try {
        ws.sendText("hello")
        val msg = ws.receive()
        if (msg != null && msg.isText)
            println(msg.text)
    } finally {
        ws.close()
    }
}
```

TCP:

```lyng
import lyng.io.net

if (Net.isTcpAvailable()) {
    val s = Net.tcpConnect("example.com", 80)
    try {
        s.writeUtf8("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
        s.flush()
        while (true) {
            val line = s.readLine()
            if (line == null) break
            println(line)
        }
    } finally {
        s.close()
    }
}
```

TCP server:

```lyng
import lyng.io.net

if (Net.isTcpServerAvailable()) {
    val server = Net.tcpListen(4040, host="127.0.0.1")
    println("listening on " + server.localAddress())

    try {
        while (server.isOpen()) {
            val client = server.accept()
            launch {
                try {
                    println("accepted " + client.remoteAddress())

                    val requestLine = client.readLine()
                    if (requestLine != null)
                        println("request: " + requestLine)

                    while (true) {
                        val line = client.readLine()
                        if (line == null || line == "") break
                    }

                    client.writeUtf8("hello from lyng server\n")
                    client.flush()
                } finally {
                    client.close()
                }
            }
        }
    } finally {
        server.close()
    }
}
```

UDP:

```lyng
import lyng.io.net

if (Net.isUdpAvailable()) {
    val udp = Net.udpBind()
    try {
        udp.send(Buffer("ping"), "127.0.0.1", 4041)
        val d = udp.receive()
        if (d != null)
            println("from " + d.address + ": " + d.data)
    } finally {
        udp.close()
    }
}
```

## Platform support model

The interface is uniform. Support is platform-dependent.

Initial intent:

| Platform | HTTP | WS client | TCP client | TCP server | UDP |
| :--- | :---: | :---: | :---: | :---: | :---: |
| JVM | yes | yes | yes | yes | yes |
| Android | yes | yes | likely yes | likely yes | likely yes |
| Native Linux/macOS | yes | yes | target | target | target |
| Native Windows | yes | yes | target | target | target |
| NodeJS | yes | engine-dependent | target | target | target |
| Browser / Wasm | yes | engine-dependent | no or target-specific | no | no |

Interpretation rules:

- import succeeds when the module is installed
- construction/operations may still fail if the specific feature is unavailable
- discovery methods must let scripts avoid that path cleanly
- `isSupported()` means the module has at least one useful implementation on the current runtime

## Security model

Use separate policies per module to avoid muddy authorization.

Recommended policies:

- `HttpAccessPolicy`
- `WsAccessPolicy`
- `NetAccessPolicy`

Suggested operations:

```kotlin
sealed interface HttpAccessOp {
    data class Request(val method: String, val url: String) : HttpAccessOp
}

sealed interface WsAccessOp {
    data class Connect(val url: String) : WsAccessOp
    data class Send(val url: String, val bytes: Int, val isText: Boolean) : WsAccessOp
    data class Receive(val url: String) : WsAccessOp
}

sealed interface NetAccessOp {
    data class Resolve(val host: String, val port: Int) : NetAccessOp
    data class TcpConnect(val host: String, val port: Int) : NetAccessOp
    data class TcpListen(val host: String?, val port: Int, val backlog: Int) : NetAccessOp
    data class TcpAccept(val localHost: String?, val localPort: Int, val remoteHost: String, val remotePort: Int) : NetAccessOp
    data class TcpRead(val remoteHost: String, val remotePort: Int, val requestedBytes: Int) : NetAccessOp
    data class TcpWrite(val remoteHost: String, val remotePort: Int, val bytes: Int) : NetAccessOp
    data class UdpBind(val host: String?, val port: Int) : NetAccessOp
    data class UdpSend(val host: String, val port: Int, val bytes: Int) : NetAccessOp
    data class UdpReceive(val localHost: String?, val localPort: Int, val requestedBytes: Int) : NetAccessOp
}
```

This is intentionally strict. It lets embedders enforce:

- allow HTTP but forbid raw sockets
- allow outbound TCP but forbid listening sockets
- allow UDP only on loopback
- allow WebSockets only to whitelisted hosts

## Error mapping

Like other `lyngio` modules, host exceptions should be mapped into Lyng runtime errors.

Recommended v1 mapping:

- policy denial -> illegal operation
- malformed URL / invalid host / invalid port -> illegal argument
- connect failure / bind failure / timeout / protocol failure -> illegal state

Later, if needed, add typed Lyng exceptions such as:

- `HttpException`
- `WebSocketException`
- `NetworkException`
- `TimeoutException`

## Implementation outline

Recommended layout:

- `lyngio/stdlib/lyng/io/http.lyng`
- `lyngio/stdlib/lyng/io/ws.lyng`
- `lyngio/stdlib/lyng/io/net.lyng`
- `lyngio/src/commonMain/kotlin/net/sergeych/lyng/io/http/LyngHttpModule.kt`
- `lyngio/src/commonMain/kotlin/net/sergeych/lyng/io/ws/LyngWsModule.kt`
- `lyngio/src/commonMain/kotlin/net/sergeych/lyng/io/net/LyngNetModule.kt`
- `lyngio/src/commonMain/kotlin/net/sergeych/lyngio/http/...`
- `lyngio/src/commonMain/kotlin/net/sergeych/lyngio/ws/...`
- `lyngio/src/commonMain/kotlin/net/sergeych/lyngio/net/...`

Module installers should mirror existing `lyngio` patterns:

```kotlin
fun createHttpModule(policy: HttpAccessPolicy, scope: Scope): Boolean
fun createWsModule(policy: WsAccessPolicy, scope: Scope): Boolean
fun createNetModule(policy: NetAccessPolicy, scope: Scope): Boolean
```

As with `lyng.io.console`, the registrar should evaluate embedded `.lyng` declarations into module scope.

## Why this is better than the earlier raw-sockets-first plan

- it delivers useful networking sooner
- it covers HTTP and WebSocket immediately, not as future work
- it reuses mature KMP networking code instead of creating a transport stack from scratch
- it still leaves room for a later low-level socket API if Ktor abstractions prove insufficient

## Open questions

1. Should `Http` use static convenience methods only in v1, or also expose a reusable `HttpClient`-like Lyng object later?
2. Should `Ws.connect(...)` accept only URL plus headers, or also subprotocols in v1?
3. Should `TcpSocket.readExact(...)` be included from the start, or wait until there is a real use case?
4. Should `UdpSocket.send(...)` accept `SocketAddress` overloads in v1, or only `host` and `port`?
5. Should `isSupported()` return true when only a subset of the module works, or should module support be stricter?

## Recommended implementation order

1. `lyng.io.http`
2. `lyng.io.ws`
3. `lyng.io.net` TCP client
4. `lyng.io.net` TCP server
5. `lyng.io.net` UDP

This order matches both practical usefulness and implementation risk.
