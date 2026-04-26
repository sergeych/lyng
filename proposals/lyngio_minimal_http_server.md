# Proposal: Minimal HTTP/1.1 + WebSocket Server For `lyngio`

Status: Draft  
Date: 2026-04-26  
Owner: `lyngio`

## Context

`lyngio` already provides:

- HTTP client support via `lyng.io.http`
- WebSocket client support via `lyng.io.ws`
- raw TCP/UDP transport via `lyng.io.net`

The current transport layer is already multiplatform and exposes a small common Kotlin interface:

- `LyngTcpSocket`
- `LyngTcpServer`
- `LyngNetEngine`

This makes it practical to add a minimal server implementation in pure Kotlin without introducing a second public networking model.

The intended deployment model for this server is:

- behind a frontend proxy such as nginx
- no TLS termination in `lyngio`
- no HTTP/2 in `lyngio` v1
- minimal, strict HTTP/1.1 subset
- classic HTTP/1.1 WebSocket upgrade support

This proposal deliberately does **not** attempt to implement HTTP/2. That work is substantially larger because it requires binary framing, stream multiplexing, HPACK, and flow control. For the intended deployment model, a frontend proxy can provide TLS and public HTTP/2 while `lyngio` speaks HTTP/1.1 on the backend.

## Goals

- Add a minimal HTTP server implementation in pure Kotlin.
- Keep the implementation compatible with Kotlin Multiplatform common code constraints.
- Reuse the existing `lyngio.net` TCP transport layer.
- Support a strict, useful HTTP/1.1 subset.
- Support classic WebSocket upgrade from HTTP/1.1.
- Keep the API and implementation small enough to be auditable and testable.
- Preserve room for later richer server APIs or JVM-specific backends.

## Non-goals

- HTTP/2
- TLS
- ALPN
- proxy protocol support
- request pipelining
- chunked request bodies
- HTTP trailers
- content compression
- multipart/form-data parsing
- range requests
- streaming request bodies in v1
- streaming response bodies in v1
- WebSocket extensions
- WebSocket subprotocol negotiation in v1
- exposing Ktor server APIs or types

## Design principles

### 1. Common-code first

The implementation should live primarily in `commonMain` and depend only on existing common abstractions built on top of `LyngTcpSocket` and `LyngTcpServer`.

### 2. Strict subset over broad tolerance

The server should reject unsupported or ambiguous protocol constructs instead of trying to be maximally permissive.

This reduces complexity, avoids parser edge cases, and makes connection reuse easier to reason about.

### 3. Small surface area

The first version should only implement what is needed for:

- ordinary backend HTTP request/response handling behind a proxy
- WebSocket upgrade and session handling
- persistent HTTP/1.1 connections when message framing is unambiguous

### 4. Frontend proxy assumption

The server is expected to run behind nginx or a similar reverse proxy that can provide:

- TLS termination
- public HTTP/2 if needed
- request filtering and size limiting
- buffering and slow-client protection
- optional compression and edge-specific behavior

## Proposed package

Add a new internal package:

- `net.sergeych.lyngio.http.server`

This proposal defines an internal Kotlin API first. Lyng-facing scripting bindings are explicitly out of scope for the first phase.

## Supported HTTP request subset

### Request line

Accepted format:

- `METHOD SP request-target SP HTTP/1.1`

Rules:

- request line must split into exactly 3 parts
- `METHOD` must be a non-empty HTTP token
- version must be exactly `HTTP/1.1`
- request target must be origin-form only

Accepted request-target examples:

- `/`
- `/hello`
- `/hello/world?x=1&y=2`

Rejected request-target forms:

- absolute-form: `http://example.com/x`
- authority-form
- asterisk-form: `*`

### Methods

The parser should accept any syntactically valid token as a method and expose it as a string.

The handler layer may then decide what to do with it.

This keeps the parser generic and avoids hardcoding a small method list.

### Headers

Rules:

- header section ends at the first empty line
- each header line must have `name:value` form
- header names are case-insensitive for lookup
- original header values are preserved
- repeated headers are preserved as repeated values
- obsolete line folding is rejected
- embedded CR or LF in header values is rejected

### Host header

Rules:

- `Host` is required on every request
- there must be exactly one effective host value after normalization
- duplicate `Host` values are allowed only if they are identical after trimming
- conflicting `Host` values are rejected

### Request bodies

v1 accepted request body framing:

- no body
- body with a valid `Content-Length`

v1 rejected request body framing:

- any `Transfer-Encoding`
- chunked request bodies
- ambiguous or conflicting body framing

### Keep-alive

HTTP/1.1 persistent connections are supported.

Rules:

- keep-alive is the default
- the server closes the connection if the client sends `Connection: close`
- the server may close the connection after any response if it chooses
- the server closes the connection on parse errors or framing errors
- after a successful WebSocket upgrade, the HTTP request loop ends for that socket

### WebSocket upgrade

v1 supports classic HTTP/1.1 upgrade to WebSocket.

Required request properties:

- method is `GET`
- `Upgrade: websocket`
- `Connection` contains token `upgrade`
- `Sec-WebSocket-Key` is present
- `Sec-WebSocket-Version: 13`

v1 behavior:

- no subprotocol negotiation
- no extension negotiation
- no HTTP/2 WebSocket support
- no fallback upgrade modes beyond the standard HTTP/1.1 handshake

## Rejection and error rules

### `400 Bad Request`

Return `400` for:

- malformed request line
- invalid HTTP token in method or header name
- unsupported request-target form
- missing `Host`
- conflicting `Host` values
- invalid header syntax
- obsolete folded headers
- invalid `Content-Length`
- conflicting duplicate `Content-Length`
- invalid WebSocket upgrade request

### `413 Payload Too Large`

Return `413` when request body exceeds configured maximum size.

### `414 URI Too Long`

Return `414` when the request-target exceeds configured limits.

### `431 Request Header Fields Too Large`

Return `431` when:

- total header bytes exceed the configured limit
- header count exceeds the configured limit
- an individual header line exceeds the configured limit if such a per-line limit is introduced

### `501 Not Implemented`

Return `501` for:

- `Transfer-Encoding` in requests
- chunked request bodies
- `Expect: 100-continue`
- unsupported `Upgrade` values
- request features intentionally excluded from v1

### `505 HTTP Version Not Supported`

Return `505` for any HTTP version other than `HTTP/1.1`.

### `500 Internal Server Error`

Return `500` when the request was parsed successfully but the application handler throws or otherwise fails unexpectedly.

## Response model

v1 responses should be fully materialized before writing.

Rules:

- always send a status line
- always send response headers
- prefer sending `Content-Length` on all normal responses
- do not emit chunked responses in v1
- if response framing is ambiguous, close the connection instead of attempting reuse

Connection closing rules:

- include `Connection: close` when the server intends to close after the response
- close after the response if the request asked for `Connection: close`
- close after protocol errors
- after `101 Switching Protocols`, the HTTP server loop yields ownership of the socket to the WebSocket session

## Suggested defaults and limits

Default operational limits:

- maximum request line bytes: `8 KiB`
- maximum total header bytes: `32 KiB`
- maximum header count: `100`
- maximum request body bytes: `1 MiB`
- keep-alive idle timeout: `15_000 ms`

These should be configurable per server instance.

## Internal Kotlin API

The following shape is recommended as the initial internal API.

```kotlin
data class HttpServerConfig(
    val host: String? = "127.0.0.1",
    val port: Int = 0,
    val backlog: Int = 128,
    val reuseAddress: Boolean = true,
    val maxRequestLineBytes: Int = 8 * 1024,
    val maxHeaderBytes: Int = 32 * 1024,
    val maxHeaderCount: Int = 100,
    val maxBodyBytes: Int = 1 * 1024 * 1024,
    val keepAliveTimeoutMillis: Long = 15_000,
)

data class HttpHeader(
    val name: String,
    val value: String,
)

class HttpHeaders(
    private val entries: List<HttpHeader>,
) {
    fun first(name: String): String?
    fun all(name: String): List<String>
    fun containsToken(name: String, token: String): Boolean
    fun entries(): List<HttpHeader>
}

data class HttpRequestHead(
    val method: String,
    val target: String,
    val path: String,
    val query: String?,
    val version: String,
    val headers: HttpHeaders,
    val contentLength: Int?,
    val wantsClose: Boolean,
    val wantsWebSocketUpgrade: Boolean,
)

data class HttpRequest(
    val head: HttpRequestHead,
    val body: ByteArray,
)

data class HttpResponse(
    val status: Int,
    val reason: String = defaultReason(status),
    val headers: List<HttpHeader> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val close: Boolean = false,
)

interface HttpWebSocketSession {
    fun isOpen(): Boolean
    suspend fun sendText(text: String)
    suspend fun sendBytes(data: ByteArray)
    suspend fun receive(): net.sergeych.lyngio.ws.LyngWsMessage?
    suspend fun close(code: Int = 1000, reason: String = "")
}

sealed interface HttpHandlerResult {
    data class Response(val response: HttpResponse) : HttpHandlerResult
    data class WebSocket(val handler: suspend (HttpWebSocketSession) -> Unit) : HttpHandlerResult
}

fun interface HttpHandler {
    suspend fun handle(request: HttpRequest): HttpHandlerResult
}

interface HttpServer {
    fun isOpen(): Boolean
    fun localAddress(): net.sergeych.lyngio.net.LyngSocketAddress
    fun close()
}
```

## Implementation architecture

The implementation should be split into a small number of focused components.

### 1. `HttpServer.kt`

Contains:

- public internal interfaces and data classes
- config and response models
- default reason phrase mapping

### 2. `BufferedSocketReader.kt`

A small internal reader built on top of `LyngTcpSocket`.

Responsibilities:

- buffered reads
- line reads with explicit limits
- exact byte reads for request bodies and WebSocket frames
- avoiding fragile mixing of raw `read()` and `readLine()` semantics

This reader should be internal and should not require changes to `LyngTcpSocket` in v1.

### 3. `HttpParser.kt`

Responsibilities:

- request line parsing
- target parsing into `path` and optional query
- header parsing and normalization
- validation of `Host`, `Content-Length`, and connection semantics
- mapping parse failures into typed HTTP errors

### 4. `HttpWriter.kt`

Responsibilities:

- writing status line and headers
- adding `Content-Length` where needed
- setting `Connection: close` when the server intends to close
- writing the response body
- flushing output

### 5. `HttpServerLoop.kt`

Responsibilities:

- accept loop over `LyngTcpServer`
- per-connection request loop
- keep-alive timeout handling
- error-to-response mapping
- handing off upgraded sockets to WebSocket session implementation

### 6. `ServerWebSocket.kt`

Responsibilities:

- validating upgrade request
- computing `Sec-WebSocket-Accept`
- writing `101 Switching Protocols`
- reading and writing WebSocket frames
- close handling

This should reuse the client-side frame and handshake logic already present in spirit, but server-side behavior should stay separate and explicit.

## Connection processing model

Per accepted TCP connection:

1. read request line
2. read headers
3. validate request
4. read request body if `Content-Length` is present
5. call the application handler
6. if handler returns HTTP response, write it and decide whether to continue
7. if handler returns WebSocket upgrade, send `101`, create a WebSocket session, and transfer ownership of the socket
8. continue until close, error, timeout, or upgrade

The server should process one request at a time per connection.

Pipelining is out of scope.

## Detailed parser rules

### Method parsing

- method must be a valid HTTP token
- parser does not enforce a fixed method allowlist

### Target parsing

- target must begin with `/`
- split on the first `?`
- `path` is the portion before `?`
- `query` is the portion after `?`, or `null`
- no URL decoding is required in v1; raw target text may be exposed

### Header parsing

- split each header line on the first `:`
- trim outer spaces and tabs from the value
- reject control characters other than horizontal tab if any are allowed at all
- do case-insensitive matching by normalized header name
- preserve the original values as supplied

### Content-Length rules

- absent means no request body
- one valid decimal value is accepted
- multiple values are accepted only if all normalized values are identical
- negative values are rejected
- values above configured maximum body size are rejected with `413`

### Connection token parsing

- `Connection` is tokenized case-insensitively on commas
- surrounding spaces are ignored
- helper methods should support `containsToken("Connection", "close")`
- helper methods should support `containsToken("Connection", "upgrade")`

## WebSocket v1 rules

### Upgrade acceptance

Accept only if all of the following are true:

- request method is `GET`
- request version is `HTTP/1.1`
- request body is empty
- `Upgrade` contains `websocket`
- `Connection` contains `upgrade`
- `Sec-WebSocket-Key` is present and syntactically valid
- `Sec-WebSocket-Version` equals `13`

Otherwise return a regular HTTP error response.

### WebSocket features in v1

Supported:

- text messages
- binary messages
- ping/pong handling
- close handshake

Not supported in v1:

- permessage-deflate
- subprotocol negotiation
- fragmented-message streaming to the application
- very large frame optimizations beyond a reasonable implementation limit

## Testing plan

A server like this should be tested at three levels.

### 1. Parser unit tests

Cases:

- valid request line parsing
- invalid request line parsing
- target parsing with and without query
- header case-insensitive lookup
- duplicate `Host` handling
- duplicate `Content-Length` handling
- oversized request line rejection
- oversized headers rejection
- `Transfer-Encoding` rejection

### 2. Engine-level loopback tests

Using existing TCP backends:

- simple `GET` request and response
- `POST` with `Content-Length`
- keep-alive with two sequential requests on one socket
- `Connection: close`
- malformed request closes connection
- handler exception becomes `500`
- body too large becomes `413`

### 3. WebSocket upgrade tests

Cases:

- successful upgrade handshake
- text echo
- binary echo
- ping/pong behavior
- clean close handshake
- invalid upgrade headers rejected as HTTP errors

## Implementation phases

### Phase 1: internal HTTP server core

Implement:

- config
- buffered reader
- parser
- writer
- request loop
- fixed-body responses
- keep-alive

### Phase 2: server-side WebSocket upgrade

Implement:

- upgrade validation
- `101 Switching Protocols`
- WebSocket frame IO
- session object
- close and ping/pong handling

### Phase 3: host integration and optional Lyng exposure

Possible future work:

- host-facing convenience factory APIs
- Lyng module exposure if there is a clear scripting use case
- route helpers or lightweight dispatching
- JVM-specific richer backends if requirements grow

## Open questions

1. Should the first version expose only a Kotlin host API, or should it also be surfaced to Lyng scripts immediately?
2. Should response headers be represented as repeated `HttpHeader` entries only, or should a convenience builder API be added from the start?
3. Should the first version include a small path router helper, or should routing stay entirely in host code?
4. Should very small chunked response support be added later if keep-alive plus unknown response length becomes a real need, or should v1 require fully materialized responses only?

## Recommendation

Proceed with this strict HTTP/1.1 + WebSocket subset.

It is small enough to finish in common Kotlin, fits the current `lyngio` transport architecture, and avoids turning the project into a full protocol-stack implementation effort.
