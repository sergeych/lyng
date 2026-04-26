# `lyng.io.ws` - WebSocket client for Lyng scripts

This module provides a compact WebSocket client API for Lyng scripts. It is implemented in `lyngio` and currently backed by Ktor WebSockets on the JVM.

> **Note:** `lyngio` is a separate library module. It must be explicitly added as a dependency to your host application and initialized in your Lyng scopes.
>
> **Shared type note:** `WsMessage` is also available from `lyng.io.ws.types` when host code wants the reusable message type without depending on the WebSocket client module itself.

## Install The Module Into A Lyng Session

Kotlin host bootstrap example:

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

## Using From Lyng Scripts

### Text Exchange

```lyng
import lyng.io.ws

val ws = Ws.connect(WS_TEST_URL)
ws.sendText("ping")
val m: WsMessage = ws.receive()
ws.close()
[ws.url() == WS_TEST_URL, m.isText, m.text]
>>> [true,true,echo:ping]
```

### Binary Exchange

```lyng
import lyng.buffer
import lyng.io.ws

val ws = Ws.connect(WS_TEST_BINARY_URL)
ws.sendBytes(Buffer(9, 8, 7))
val m: WsMessage = ws.receive()
ws.close()
[m.isText, (m.data as Buffer).hex]
>>> [false,010203090807]
```

### Secure `wss` Exchange

```lyng
import lyng.io.ws

val ws = Ws.connect(WSS_TEST_URL)
ws.sendText("ping")
val m: WsMessage = ws.receive()
ws.close()
[ws.url() == WSS_TEST_URL, m.text]
>>> [true,secure:ping]
```

## Message Flow And Session Lifecycle

### Reading Incoming Messages

Call `ws.receive()` to wait for the next application message.

What `receive()` returns:
- `WsMessage` for the next text or binary message.
- `null` after the peer closes the connection cleanly.
- `null` after the transport has already been closed and no more messages can arrive.

What reaches Lyng code:
- Text frames are exposed as `WsMessage(isText = true, text = ...)`.
- Binary frames are exposed as `WsMessage(isText = false, data = ...)`.
- Fragmented websocket messages are reassembled before they are returned.
- Ping and pong control frames are handled internally and are not returned by `receive()`.
- Incoming close frames are handled internally; after that `receive()` returns `null`.

Typical receive loop:

```lyng
import lyng.buffer
import lyng.io.ws

val ws = Ws.connect(WS_URL)

while (true) {
    val msg = ws.receive() ?: break

    if (msg.isText) {
        println("text=" + msg.text)
    } else {
        println("bytes=" + ((msg.data as Buffer).size))
    }
}

println("peer closed the websocket")
```

### Sending Outgoing Messages

Use:
- `ws.sendText(text)` for UTF-8 text messages.
- `ws.sendBytes(data)` for binary messages.

Example:

```lyng
import lyng.buffer
import lyng.io.ws

val ws = Ws.connect(WS_URL)
ws.sendText("hello")
ws.sendBytes(Buffer(1, 2, 3, 4))
```

Send behavior:
- Each call sends one websocket message.
- The API does not expose partial-frame streaming; send the whole message in one call.
- If the session is already closed, `sendText(...)` and `sendBytes(...)` fail with a websocket error.
- If the transport breaks during send, the session is released and the send call fails.

### Detecting Closed Connections

Use both signals together:
- `ws.isOpen()` tells you whether the session is still considered open right now.
- `ws.receive() == null` tells you the receive side has reached the end of the websocket session.

Practical rule:
- If `receive()` returns `null`, stop reading and treat the session as closed.
- After close has been observed, do not attempt further sends.

The API does not currently expose the peer close code or close reason to Lyng code.

### Closing The Connection Yourself

Call `ws.close()` when you are done.

```lyng
import lyng.io.ws

val ws = Ws.connect(WS_URL)
ws.sendText("bye")
ws.close(1000, "done")
```

Close semantics:
- `close()` sends a websocket close frame with the given code and reason.
- Defaults are `code = 1000` and `reason = ""`.
- After `close()`, the session is released locally and should be treated as closed immediately.
- Calling `close()` on an already closed session is a no-op.
- After local close, `receive()` returns `null` and further sends fail.

### Recommended Usage Pattern

For request-response style exchanges:

```lyng
import lyng.io.ws

val ws = Ws.connect(WS_URL)
try {
    ws.sendText("ping")
    val reply = ws.receive() ?: error("socket closed before reply")
    println(reply.text)
} finally {
    ws.close()
}
```

For long-lived consumers:

```lyng
import lyng.io.ws

val ws = Ws.connect(WS_URL)

try {
    while (true) {
        val msg = ws.receive() ?: break
        if (msg.isText) {
            println(msg.text)
        }
    }
} finally {
    ws.close()
}
```

## API Reference

### `Ws`

- `isSupported(): Bool` - whether WebSocket client support is available on the current runtime.
- `connect(url: String, headers...): WsSession` - open a client websocket session.

`headers...` accepts:
- `MapEntry`, for example `"Authorization" => "Bearer x"`
- 2-item lists, for example `["Authorization", "Bearer x"]`

### `WsSession`

- `isOpen(): Bool`
- `url(): String`
- `sendText(text: String): void`
- `sendBytes(data: Buffer): void`
- `receive(): WsMessage?`
- `close(code: Int = 1000, reason: String = ""): void`

Behavior summary:
- `receive()` returns `null` after close.
- `close()` is safe to call more than once.
- send operations require an open session.

### `WsMessage`

- `isText: Bool`
- `text: String?`
- `data: Buffer?`

Payload rules:
- Text messages populate `text` and leave `data == null`.
- Binary messages populate `data` and leave `text == null`.

## Security Policy

The module uses `WsAccessPolicy` to authorize websocket operations.

- `WsAccessPolicy` - interface for custom policies.
- `PermitAllWsAccessPolicy` - allows all websocket operations.
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

## Platform Support

- **JVM:** supported.
- **Android:** supported via the Ktor CIO websocket client backend.
- **JS:** supported via the Ktor JS websocket client backend.
- **Linux native:** supported via the Ktor Curl websocket client backend.
- **Windows native:** supported via the Ktor WinHttp websocket client backend.
- **Apple native:** supported via the Ktor Darwin websocket client backend.
- **Other targets:** may report unsupported; use `Ws.isSupported()` before relying on websocket client access.
