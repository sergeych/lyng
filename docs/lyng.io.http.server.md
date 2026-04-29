# `lyng.io.http.server` - Minimal HTTP/1.1 And WebSocket Server

This module provides a small server-side HTTP API for Lyng scripts. It is implemented in `lyngio` on top of the existing TCP layer and is intended for embedded tools, local services, test fixtures, and lightweight app backends.

It supports:
- HTTP/1.1 request parsing
- keep-alive
- exact-path routing
- regex routing
- path-template routing with named parameters
- websocket upgrade and server-side sessions

It does not aim to replace a full reverse proxy. Typical deployment is behind nginx, Caddy, or another frontend that handles TLS and public-facing edge concerns.

> **Security note:** this module uses the same `NetAccessPolicy` capability model as raw TCP sockets. If scripts are allowed to listen on TCP, they can host an HTTP server.

## Install The Module Into A Lyng Session

Kotlin bootstrap example:

```kotlin
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Scope
import net.sergeych.lyng.io.http.server.createHttpServerModule
import net.sergeych.lyngio.net.security.PermitAllNetAccessPolicy

suspend fun bootstrapHttpServer() {
    val session = EvalSession()
    val scope: Scope = session.getScope()
    createHttpServerModule(PermitAllNetAccessPolicy, scope)
    session.eval("import lyng.io.http.server")
}
```

## RequestContext Sugar

Route handlers use `RequestContext` as the receiver, so inside handlers you normally write direct calls such as:

- `jsonBody<T>()`
- `respondJson(...)`
- `respondHtml { ... }`
- `respondText(...)`
- `setHeader(...)`
- `request.path`
- `routeParams["id"]`

This keeps ordinary HTTP endpoints compact and avoids passing an explicit request or exchange parameter through every route lambda.

## HTML Response Sugar

Use `respondHtml { ... }` to render an HTML document with the `lyng.io.html` DSL and send it as `text/html; charset=utf-8`.

```lyng
import lyng.io.http.server
import lyng.io.html

val server = HttpServer()

server.get("/") {
    respondHtml {
        head {
            title { +"Lyng status" }
        }
        body {
            h3 { +"Service is running" }
            p { +("Path: " + request.path) }
        }
    }
}

server.listen(8080, "127.0.0.1")
```

Pass `code:` when the route should return a non-200 status:

```lyng
server.get("/accepted") {
    respondHtml(code: 202) {
        body { h3 { +"Accepted" } }
    }
}
```

## JSON API Sugar

For ordinary JSON APIs, `RequestContext` includes two primary helpers:

- `jsonBody<T>()` decodes the request body with typed `Json.decodeAs(...)`
- `respondJson(body, status = 200)` sets JSON content type and responds with plain `toJsonString()`

These helpers intentionally use ordinary JSON projection for HTTP interop, not canonical `Json.encode(...)`.

### Typed JSON POST With Route Params

```lyng
import lyng.io.http.server

closed class CreateResultRequest(title: String, score: Int)
closed class CreateResultResponse(id: String, userId: String, title: String, score: Int)

val server = HttpServer()

server.postPath("/api/users/{userId}/results") {
    val req = jsonBody<CreateResultRequest>()

    if (req.title.isBlank()) {
        respondJson({ error: "title must not be empty" }, 400)
        return
    }

    respondJson(
        CreateResultResponse("r-101", routeParams["userId"], req.title, req.score),
        201
    )
}

server.listen(8080, "127.0.0.1")
```

### JSON Response With Route Params

```lyng
import lyng.io.http.server

val server = HttpServer()

server.getPath("/api/users/{id}") {
    respondJson({
        id: routeParams["id"],
        path: request.path,
        ok: true
    })
}

server.listen(8080, "127.0.0.1")
```

## Request And Route Data

`ServerRequest` exposes parsed HTTP request data:

- `method: String`
- `target: String`
- `path: String`
- `pathParts: List<String>`
- `queryString: String?`
- `query: Map<String, String>`
- `headers: HttpHeaders`
- `body: Buffer`

`RequestContext` exposes routing context and response controls:

- `request: ServerRequest`
- `routeMatch: RegexMatch?`
- `routeParams: Map<String, String>`
- `jsonBody<T>()`
- `respond(...)`
- `respondText(...)`
- `respondJson(body, status = 200)`
- `respondHtml(code: 200) { ... }`
- `setHeader(...)`
- `addHeader(...)`
- `acceptWebSocket(...)`

For exact routes, `routeMatch` is `null` and `routeParams` is empty.
For regex routes, `routeMatch` is set and `routeParams` is empty.
For path-template routes, both `routeMatch` and `routeParams` are set.

## Reusable Routers

`Router` collects the same route kinds as `HttpServer`, but does not listen on sockets by itself.
Mount it into `HttpServer` or another `Router`.

```lyng
import lyng.io.http.server

val api = Router()
api.get("/health") {
    respondText(200, "ok")
}

val users = Router()
users.getPath("/users/{id}") {
    respondJson({ id: routeParams["id"] })
}

api.mount(users)

val server = HttpServer()
server.mount(api)
server.listen(8080, "127.0.0.1")
```

Mounted routers reuse the built-in server router. They are configuration-time composition, not an extra per-request Lyng dispatch layer.

## WebSocket Routes

You can route websocket upgrades by exact path, regex, or path template.

```lyng
server.ws("/chat") { ws ->
    ws.sendText("hello")
    ws.close()
}

server.wsPath("/ws/{room}") { ws ->
    ws.sendText("room=" + routeParams["room"])
    ws.close()
}
```

A websocket handler runs only for requests that actually ask for websocket upgrade. Ordinary HTTP requests to the same path are not treated as websocket sessions.

### Choosing Between `ws(...)` And `acceptWebSocket(...)`

Use `server.ws(...)` or `server.wsPath(...)` when the route is always a websocket endpoint.

Use `acceptWebSocket(...)` inside a normal HTTP handler when the same route may inspect the request first and then decide whether to upgrade.

```lyng
server.get("/maybe-upgrade") {
    if (!request.isWebSocketUpgrade()) {
        respondText(400, "websocket upgrade required")
        return
    }

    acceptWebSocket { ws ->
        ws.sendText("connected")
        ws.close()
    }
}
```

### Reading Incoming Messages

Inside a websocket handler, call `ws.receive()` to wait for the next application message.

What `receive()` returns:
- `WsMessage` for the next text or binary message.
- `null` after the client sends a close frame.
- `null` after the socket is already closed and no more frames can arrive.

What reaches Lyng code:
- Text frames become `WsMessage(isText = true, text = ...)`.
- Binary frames become `WsMessage(isText = false, data = ...)`.
- Fragmented websocket messages are reassembled before they are returned.
- Ping and pong control frames are handled internally and do not appear in Lyng.
- A client close frame is answered by the server close handshake, then `receive()` returns `null`.

Typical server receive loop:

```lyng
import lyng.buffer

server.ws("/echo") { ws ->
    while (true) {
        val msg = ws.receive() ?: break
        if (msg.isText) {
            ws.sendText("echo:" + msg.text)
        } else {
            ws.sendBytes(msg.data as Buffer)
        }
    }
}
```

### Sending Outgoing Messages

Use:
- `ws.sendText(text)` for text messages.
- `ws.sendBytes(data)` for binary messages.

Example:

```lyng
import lyng.buffer

server.ws("/push") { ws ->
    ws.sendText("ready")
    ws.sendBytes(Buffer(1, 2, 3))
    ws.close()
}
```

Send behavior:
- Each call sends one websocket message.
- The server API does not expose frame-by-frame streaming.
- Once the session is closed, send calls fail with a websocket error.

### What Happens When The Connection Closes

There are three practical cases:

1. The client closes first.
   The runtime replies with a close frame, releases the socket, and `receive()` returns `null`.

2. Your handler closes first with `ws.close(...)`.
   The runtime sends a close frame and releases the socket locally.

3. The transport disappears unexpectedly.
   The session is released and no more messages can be received; subsequent sends fail.

What Lyng code should do:
- Treat `receive() == null` as end-of-session.
- Exit the handler or break the receive loop at that point.
- Do not keep sending after close has been observed.

The current server-side API does not expose the peer close code or close reason to Lyng.

### Closing The Connection Yourself

Call `ws.close()` when you want to terminate the websocket session.

```lyng
server.ws("/chat") { ws ->
    ws.sendText("server shutting down")
    ws.close(1000, "done")
}
```

Close semantics:
- `close()` sends a websocket close frame with the given code and reason.
- Defaults are `code = 1000` and `reason = ""`.
- `close()` is idempotent; calling it again after close does nothing.
- After local close, the session should be treated as unusable.
- After close, `isOpen()` becomes false and further sends fail.

### WebSocket Handler Pattern

```lyng
import lyng.io.http.server

val server = HttpServer()

server.wsPath("/rooms/{room}") { ws ->
    val room = routeParams["room"] ?: "<unknown>"
    ws.sendText("joined:" + room)

    while (true) {
        val msg = ws.receive() ?: break
        if (msg.isText) {
            ws.sendText(room + ":" + msg.text)
        }
    }

    ws.close()
}

server.listen(8080, "127.0.0.1")
```

## Path-Template Routes

Path templates are sugar on top of regex routes. Template parameters are exposed as decoded `routeParams`.

```lyng
server.getPath("/users/{userId}/posts/{postId}") {
    respondText(
        200,
        routeParams["userId"] + ":" + routeParams["postId"]
    )
}
```

Template rules:
- template must start with `/`
- a segment is either literal text or `{name}`
- parameter names must be valid identifiers
- parameter values match one path segment only
- parameter values use path decoding rules:
  - valid percent-encoding is decoded
  - `+` stays `+`
  - malformed `%` stays literal

## Regex Routes

Regex routes match the whole request path, not a substring.

```lyng
server.get("^/users/([0-9]+)/posts/([0-9]+)$".re) {
    val m = routeMatch!!
    respondText(200, "user=" + m[1] + ", post=" + m[2])
}
```

## Basic Exact Route

```lyng
import lyng.io.http.server

val server = HttpServer()
server.get("/hello") {
    setHeader("Content-Type", "text/plain")
    respondText(200, "hello")
}
server.listen(8080, "127.0.0.1")
```

## Route Precedence

Dispatch order is:

1. exact method route
2. exact `any` route
3. regex method route, registration order
4. regex `any` route, registration order
5. fallback

This means exact routes stay fast and always win over template or regex routes for the same path.

## API Surface

### `Router` Route Registration Methods

- `get(path: String|Regex, handler)`
- `getPath(pathTemplate: String, handler)`
- `post(path: String|Regex, handler)`
- `postPath(pathTemplate: String, handler)`
- `put(path: String|Regex, handler)`
- `putPath(pathTemplate: String, handler)`
- `delete(path: String|Regex, handler)`
- `deletePath(pathTemplate: String, handler)`
- `any(path: String|Regex, handler)`
- `anyPath(pathTemplate: String, handler)`
- `ws(path: String|Regex, handler)`
- `wsPath(pathTemplate: String, handler)`
- `fallback(handler)`
- `mount(router)`

### `HttpServer` Route Registration Methods

- `get(path: String|Regex, handler)`
- `getPath(pathTemplate: String, handler)`
- `post(path: String|Regex, handler)`
- `postPath(pathTemplate: String, handler)`
- `put(path: String|Regex, handler)`
- `putPath(pathTemplate: String, handler)`
- `delete(path: String|Regex, handler)`
- `deletePath(pathTemplate: String, handler)`
- `any(path: String|Regex, handler)`
- `anyPath(pathTemplate: String, handler)`
- `ws(path: String|Regex, handler)`
- `wsPath(pathTemplate: String, handler)`
- `fallback(handler)`
- `mount(router)`
- `listen(port, host = null, backlog = 128)`
