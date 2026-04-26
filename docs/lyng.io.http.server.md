### lyng.io.http.server — Minimal HTTP/1.1 and WebSocket server

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

---

#### Install the module into a Lyng session

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

---

#### Basic exact route

```lyng
import lyng.io.http.server

val server = HttpServer()
server.get("/hello") {
    setHeader("Content-Type", "text/plain")
    respondText(200, "hello")
}
server.listen(8080, "127.0.0.1")
```

---

#### Reusable routers

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

---

#### Regex route

Regex routes match the whole request path, not a substring.

```lyng
server.get("^/users/([0-9]+)/posts/([0-9]+)$".re) {
    val m = routeMatch!!
    respondText(200, "user=" + m[1] + ", post=" + m[2])
}
```

---

#### Path-template route

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

---

#### Request and exchange data

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
- `setHeader(...)`
- `addHeader(...)`
- `acceptWebSocket(...)`

For exact routes, `routeMatch` is `null` and `routeParams` is empty.  
For regex routes, `routeMatch` is set and `routeParams` is empty.  
For path-template routes, both `routeMatch` and `routeParams` are set.

---

#### JSON request/response helpers

For ordinary HTTP JSON APIs, `RequestContext` includes two helpers:

- `jsonBody<T>()` decodes the request body with typed `Json.decodeAs(...)`
- `respondJson(body, status = 200)` sets JSON content type and responds with plain `toJsonString()`

Example:

```lyng
import lyng.io.http.server

closed class CreateUserRequest(name: String, age: Int)
closed class CreateUserResponse(id: Int, name: String, age: Int)

val server = HttpServer()

server.postPath("/api/users") {
    val req = jsonBody<CreateUserRequest>()

    if (req.name.isBlank()) {
        respondJson({ error: "name must not be empty" }, 400)
        return
    }

    respondJson(CreateUserResponse(101, req.name, req.age), 201)
}

server.listen(8080, "127.0.0.1")
```

These helpers intentionally use ordinary JSON projection for HTTP interop, not canonical `Json.encode(...)`.

---

#### Route precedence

Dispatch order is:

1. exact method route
2. exact `any` route
3. regex method route, registration order
4. regex `any` route, registration order
5. fallback

This means exact routes stay fast and always win over template or regex routes for the same path.

---

#### WebSocket routes

You can route websocket upgrades by exact path, regex, or path template:

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

---

#### API surface

`Router` route registration methods:

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

`HttpServer` route registration methods:

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
