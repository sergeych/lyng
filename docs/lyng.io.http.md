### lyng.io.http — HTTP/HTTPS client for Lyng scripts

This module provides a compact HTTP client API for Lyng scripts. It is implemented in `lyngio` and backed by Ktor on supported runtimes.

> **Note:** `lyngio` is a separate library module. It must be explicitly added as a dependency to your host application and initialized in your Lyng scopes.
>
> **Shared type note:** `HttpHeaders` is also available from `lyng.io.http.types` when host code wants the reusable value type without relying on the HTTP client module itself.

---

#### Add the library to your project (Gradle)

If you use this repository as a multi-module project, add a dependency on `:lyngio`:

```kotlin
dependencies {
    implementation("net.sergeych:lyngio:0.0.1-SNAPSHOT")
}
```

For external projects, ensure you also use the Lyng Maven repository described in `lyng.io.fs`.

---

#### Install the module into a Lyng session

The HTTP module is not installed automatically. Install it into the session scope and provide a policy.

Kotlin (host) bootstrap example:

```kotlin
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Scope
import net.sergeych.lyng.io.http.createHttpModule
import net.sergeych.lyngio.http.security.PermitAllHttpAccessPolicy

suspend fun bootstrapHttp() {
    val session = EvalSession()
    val scope: Scope = session.getScope()
    createHttpModule(PermitAllHttpAccessPolicy, scope)
    session.eval("import lyng.io.http")
}
```

---

#### Using from Lyng scripts

Simple GET:

    import lyng.io.http

    val r = Http.get(HTTP_TEST_URL + "/hello")
    [r.status, r.text()]
    >>> [200,hello from test]

Headers and response header access:

    import lyng.io.http

    val r = Http.get(HTTP_TEST_URL + "/headers")
    [r.headers["X-Reply"], r.headers.getAll("X-Reply").size, r.text()]
    >>> [one,2,header demo]

Programmatic request object:

    import lyng.io.http

    val q = HttpRequest()
    q.method = "POST"
    q.url = HTTP_TEST_URL + "/echo"
    q.headers = Map("Content-Type" => "text/plain")
    q.bodyText = "ping"

    val r = Http.request(q)
    r.text()
    >>> "POST:ping"

HTTPS GET:

    import lyng.io.http

    val r = Http.get(HTTPS_TEST_URL + "/hello")
    [r.status, r.text()]
    >>> [200,hello from test]

---

#### API reference

##### `Http` (static methods)

- `isSupported(): Bool` — Whether HTTP client support is available on the current runtime.
- `request(req: HttpRequest): HttpResponse` — Execute a request described by a mutable request object.
- `get(url: String, headers...): HttpResponse` — Convenience GET request.
- `post(url: String, bodyText: String = "", contentType: String? = null, headers...): HttpResponse` — Convenience text POST request.
- `postBytes(url: String, body: Buffer, contentType: String? = null, headers...): HttpResponse` — Convenience binary POST request.

For convenience methods, `headers...` accepts:

- `MapEntry`, e.g. `"Accept" => "text/plain"`
- 2-item lists, e.g. `["Accept", "text/plain"]`

##### `HttpRequest`

- `method: String`
- `url: String`
- `headers: Map<String, String>`
- `bodyText: String?`
- `bodyBytes: Buffer?`
- `timeoutMillis: Int?`

Only one of `bodyText` and `bodyBytes` should be set.

##### `HttpResponse`

- `status: Int`
- `statusText: String`
- `headers: HttpHeaders`
- `text(): String`
- `bytes(): Buffer`

Response body decoding is cached inside the response object.

##### `HttpHeaders`

`HttpHeaders` behaves like `Map<String, String>` for the first value of each header name and additionally exposes:

- `get(name: String): String?`
- `getAll(name: String): List<String>`
- `names(): List<String>`

Header lookup is case-insensitive.

---

#### Security policy

The module uses `HttpAccessPolicy` to authorize requests before they are sent.

- `HttpAccessPolicy` — interface for custom policies
- `PermitAllHttpAccessPolicy` — allows all requests
- `HttpAccessOp.Request(method, url)` — operation checked by the policy

Example restricted policy in Kotlin:

```kotlin
import net.sergeych.lyngio.fs.security.AccessContext
import net.sergeych.lyngio.fs.security.AccessDecision
import net.sergeych.lyngio.fs.security.Decision
import net.sergeych.lyngio.http.security.HttpAccessOp
import net.sergeych.lyngio.http.security.HttpAccessPolicy

val allowLocalOnly = object : HttpAccessPolicy {
    override suspend fun check(op: HttpAccessOp, ctx: AccessContext): AccessDecision =
        when (op) {
            is HttpAccessOp.Request ->
                if (
                    op.url.startsWith("http://127.0.0.1:") ||
                    op.url.startsWith("https://127.0.0.1:") ||
                    op.url.startsWith("http://localhost:") ||
                    op.url.startsWith("https://localhost:")
                )
                    AccessDecision(Decision.Allow)
                else
                    AccessDecision(Decision.Deny, "only local HTTP/HTTPS requests are allowed")
        }
}
```

---

#### Platform support

- **JVM:** supported
- **Android:** supported via the Ktor CIO client backend
- **JS:** supported via the Ktor JS client backend
- **Linux native:** supported via the Ktor Curl client backend
- **Windows native:** supported via the Ktor WinHttp client backend
- **Apple native:** supported via the Ktor Darwin client backend
- **Other targets:** may report unsupported; use `Http.isSupported()` before relying on it
