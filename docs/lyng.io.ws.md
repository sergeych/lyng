### lyng.io.ws — WebSocket client for Lyng scripts

This module provides a compact WebSocket client API for Lyng scripts. It is implemented in `lyngio` and currently backed by Ktor WebSockets on the JVM.

> **Note:** `lyngio` is a separate library module. It must be explicitly added as a dependency to your host application and initialized in your Lyng scopes.
>
> **Shared type note:** `WsMessage` is also available from `lyng.io.ws.types` when host code wants the reusable message type without depending on the WebSocket client module itself.

---

#### Install the module into a Lyng session

Kotlin (host) bootstrap example:

```kotlin
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Scope
import net.sergeych.lyng.io.ws.createWsModule
import net.sergeych.lyngio.ws.security.PermitAllWsAccessPolicy

suspend fun bootstrapWs() {
    val session = EvalSession()
    val scope: Scope = session.getScope()
    createWsModule(PermitAllWsAccessPolicy, scope)
    session.eval("import lyng.io.ws")
}
```

---

#### Using from Lyng scripts

Simple text message exchange:

    import lyng.io.ws

    val ws = Ws.connect(WS_TEST_URL)
    ws.sendText("ping")
    val m: WsMessage = ws.receive()
    ws.close()
    [ws.url() == WS_TEST_URL, m.isText, m.text]
    >>> [true,true,echo:ping]

Binary message exchange:

    import lyng.buffer
    import lyng.io.ws

    val ws = Ws.connect(WS_TEST_BINARY_URL)
    ws.sendBytes(Buffer(9, 8, 7))
    val m: WsMessage = ws.receive()
    ws.close()
    [m.isText, (m.data as Buffer).hex]
    >>> [false,010203090807]

Secure websocket (`wss`) exchange:

    import lyng.io.ws

    val ws = Ws.connect(WSS_TEST_URL)
    ws.sendText("ping")
    val m: WsMessage = ws.receive()
    ws.close()
    [ws.url() == WSS_TEST_URL, m.text]
    >>> [true,secure:ping]

---

#### API reference

##### `Ws` (static methods)

- `isSupported(): Bool` — Whether WebSocket client support is available on the current runtime.
- `connect(url: String, headers...): WsSession` — Open a client websocket session.

`headers...` accepts:

- `MapEntry`, e.g. `"Authorization" => "Bearer x"`
- 2-item lists, e.g. `["Authorization", "Bearer x"]`

##### `WsSession`

- `isOpen(): Bool`
- `url(): String`
- `sendText(text: String): void`
- `sendBytes(data: Buffer): void`
- `receive(): WsMessage?`
- `close(code: Int = 1000, reason: String = ""): void`

`receive()` returns `null` after a clean close.

##### `WsMessage`

- `isText: Bool`
- `text: String?`
- `data: Buffer?`

Text messages populate `text`; binary messages populate `data`.

---

#### Security policy

The module uses `WsAccessPolicy` to authorize websocket operations.

- `WsAccessPolicy` — interface for custom policies
- `PermitAllWsAccessPolicy` — allows all websocket operations
- `WsAccessOp.Connect(url)`
- `WsAccessOp.Send(url, bytes, isText)`
- `WsAccessOp.Receive(url)`

Example restricted policy in Kotlin:

```kotlin
import net.sergeych.lyngio.fs.security.AccessContext
import net.sergeych.lyngio.fs.security.AccessDecision
import net.sergeych.lyngio.fs.security.Decision
import net.sergeych.lyngio.ws.security.WsAccessOp
import net.sergeych.lyngio.ws.security.WsAccessPolicy

val allowLocalOnly = object : WsAccessPolicy {
    override suspend fun check(op: WsAccessOp, ctx: AccessContext): AccessDecision =
        when (op) {
            is WsAccessOp.Connect ->
                if (
                    op.url.startsWith("ws://127.0.0.1:") ||
                    op.url.startsWith("wss://127.0.0.1:") ||
                    op.url.startsWith("ws://localhost:") ||
                    op.url.startsWith("wss://localhost:")
                )
                    AccessDecision(Decision.Allow)
                else
                    AccessDecision(Decision.Deny, "only local ws/wss connections are allowed")

            else -> AccessDecision(Decision.Allow)
        }
}
```

---

#### Platform support

- **JVM:** supported
- **Android:** supported via the Ktor CIO websocket client backend
- **JS:** supported via the Ktor JS websocket client backend
- **Linux native:** supported via the Ktor Curl websocket client backend
- **Windows native:** supported via the Ktor WinHttp websocket client backend
- **Apple native:** supported via the Ktor Darwin websocket client backend
- **Other targets:** may report unsupported; use `Ws.isSupported()` before relying on websocket client access
