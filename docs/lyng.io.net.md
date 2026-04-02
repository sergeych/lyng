### lyng.io.net — TCP and UDP sockets for Lyng scripts

This module provides minimal raw transport networking for Lyng scripts. It is implemented in `lyngio` and backed by Ktor sockets on the JVM and by Node networking APIs on JS/Node runtimes.

> **Note:** `lyngio` is a separate library module. It must be explicitly added as a dependency to your host application and initialized in your Lyng scopes.

---

#### Install the module into a Lyng session

Kotlin (host) bootstrap example:

```kotlin
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Scope
import net.sergeych.lyng.io.net.createNetModule
import net.sergeych.lyngio.net.security.PermitAllNetAccessPolicy

suspend fun bootstrapNet() {
    val session = EvalSession()
    val scope: Scope = session.getScope()
    createNetModule(PermitAllNetAccessPolicy, scope)
    session.eval("import lyng.io.net")
}
```

---

#### Using from Lyng scripts

Capability checks and address resolution:

    import lyng.io.net

    val a: SocketAddress = Net.resolve("127.0.0.1", 4040)[0]
    [Net.isSupported(), a.toString(), a.resolved, a.ipVersion == IpVersion.IPV4]
    >>> [true,127.0.0.1:4040,true,true]

TCP client connect, write, read, and close:

    import lyng.buffer
    import lyng.io.net

    val socket = Net.tcpConnect("127.0.0.1", NET_TEST_TCP_PORT)
    socket.writeUtf8("ping")
    socket.flush()
    val reply = (socket.read(16) as Buffer).decodeUtf8()
    socket.close()
    reply
    >>> "reply:ping"

Lyng TCP server socket operations with `tcpListen()` and `accept()`:

    import lyng.buffer
    import lyng.io.net

    val server = Net.tcpListen(0, "127.0.0.1")
    val port = server.localAddress().port
    val accepted = launch {
        val client = server.accept()
        val line = (client.read(4) as Buffer).decodeUtf8()
        client.writeUtf8("echo:" + line)
        client.flush()
        client.close()
        server.close()
        line
    }

    val socket = Net.tcpConnect("127.0.0.1", port)
    socket.writeUtf8("ping")
    socket.flush()
    val reply = (socket.read(16) as Buffer).decodeUtf8()
    socket.close()
    [accepted.await(), reply]
    >>> [ping,echo:ping]

UDP bind, send, receive, and inspect sender address:

    import lyng.buffer
    import lyng.io.net

    val server = Net.udpBind(0, "127.0.0.1")
    val client = Net.udpBind(0, "127.0.0.1")
    client.send(Buffer("ping"), "127.0.0.1", server.localAddress().port)
    val d = server.receive()
    client.close()
    server.close()
    [d.data.decodeUtf8(), d.address.port > 0]
    >>> [ping,true]

---

#### API reference

##### `Net` (static methods)

- `isSupported(): Bool` — Whether any raw networking support is available.
- `isTcpAvailable(): Bool` — Whether outbound TCP sockets are available.
- `isTcpServerAvailable(): Bool` — Whether listening TCP server sockets are available.
- `isUdpAvailable(): Bool` — Whether UDP datagram sockets are available.
- `resolve(host: String, port: Int): List<SocketAddress>` — Resolve a host and port into concrete addresses.
- `tcpConnect(host: String, port: Int, timeoutMillis: Int? = null, noDelay: Bool = true): TcpSocket` — Open an outbound TCP socket.
- `tcpListen(port: Int, host: String? = null, backlog: Int = 128, reuseAddress: Bool = true): TcpServer` — Start a listening TCP server socket.
- `udpBind(port: Int = 0, host: String? = null, reuseAddress: Bool = true): UdpSocket` — Bind a UDP socket.

##### `SocketAddress`

- `host: String`
- `port: Int`
- `ipVersion: IpVersion`
- `resolved: Bool`
- `toString(): String`

##### `TcpSocket`

- `isOpen(): Bool`
- `localAddress(): SocketAddress`
- `remoteAddress(): SocketAddress`
- `read(maxBytes: Int = 65536): Buffer?`
- `readLine(): String?`
- `write(data: Buffer): void`
- `writeUtf8(text: String): void`
- `flush(): void`
- `close(): void`

##### `TcpServer`

- `isOpen(): Bool`
- `localAddress(): SocketAddress`
- `accept(): TcpSocket`
- `close(): void`

##### `UdpSocket`

- `isOpen(): Bool`
- `localAddress(): SocketAddress`
- `receive(maxBytes: Int = 65536): Datagram?`
- `send(data: Buffer, host: String, port: Int): void`
- `close(): void`

##### `Datagram`

- `data: Buffer`
- `address: SocketAddress`

---

#### Security policy

The module uses `NetAccessPolicy` to authorize network operations before they are executed.

- `NetAccessPolicy` — interface for custom policies
- `PermitAllNetAccessPolicy` — allows all network operations
- `NetAccessOp.Resolve(host, port)`
- `NetAccessOp.TcpConnect(host, port)`
- `NetAccessOp.TcpListen(host, port, backlog)`
- `NetAccessOp.UdpBind(host, port)`

---

#### Platform support

- **JVM:** supported
- **Android:** supported via the Ktor CIO and Ktor sockets backends
- **JS/Node:** supported for `resolve`, TCP client/server, and UDP
- **JS/browser:** unsupported; capability checks report unavailable
- **Other targets:** currently report unsupported; use capability checks before relying on raw sockets
