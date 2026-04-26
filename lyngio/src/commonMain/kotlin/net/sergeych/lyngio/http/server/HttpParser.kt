package net.sergeych.lyngio.http.server

internal class HttpProtocolException(
    val status: Int,
    message: String,
) : IllegalStateException(message)

internal suspend fun parseHttpRequest(
    reader: BufferedSocketReader,
    config: HttpServerConfig,
): HttpRequest? {
    val requestLine = reader.readLine(
        maxBytes = config.maxRequestLineBytes,
        overflowStatus = 414,
        overflowMessage = "request line is too long",
    ) ?: return null
    val requestHead = parseRequestLine(requestLine, config)
    val headerEntries = parseHeaders(reader, config)
    val headers = HttpHeaders(headerEntries)
    validateHost(headers)
    val contentLength = parseContentLength(headers, config)
    validateUnsupportedRequestFeatures(headers)
    val wantsWebSocketUpgrade = isWebSocketUpgrade(requestHead.method, headers)
    validateWebSocketUpgradeRequest(headers, requestHead.method, contentLength, wantsWebSocketUpgrade)
    val body = if (contentLength != null) {
        reader.readExact(contentLength)
            ?: throw HttpProtocolException(400, "unexpected EOF while reading request body")
    } else {
        ByteArray(0)
    }
    return HttpRequest(
        head = HttpRequestHead(
            method = requestHead.method,
            target = requestHead.target,
            path = requestHead.path,
            queryString = requestHead.queryString,
            version = requestHead.version,
            headers = headers,
            contentLength = contentLength,
            wantsClose = headers.containsToken("Connection", "close"),
            wantsWebSocketUpgrade = wantsWebSocketUpgrade,
        ),
        body = body,
    )
}

private data class ParsedRequestLine(
    val method: String,
    val target: String,
    val path: String,
    val queryString: String?,
    val version: String,
)

private fun parseRequestLine(line: String, config: HttpServerConfig): ParsedRequestLine {
    val firstSpace = line.indexOf(' ')
    val lastSpace = line.lastIndexOf(' ')
    if (firstSpace <= 0 || lastSpace <= firstSpace || lastSpace == line.lastIndex) {
        throw HttpProtocolException(400, "malformed request line")
    }
    val method = line.substring(0, firstSpace)
    val target = line.substring(firstSpace + 1, lastSpace)
    val version = line.substring(lastSpace + 1)
    if (!method.all(::isHttpTokenChar)) {
        throw HttpProtocolException(400, "invalid HTTP method")
    }
    if (version != "HTTP/1.1") {
        throw HttpProtocolException(505, "unsupported HTTP version: $version")
    }
    if (target.length > config.maxRequestLineBytes) {
        throw HttpProtocolException(414, "request target is too long")
    }
    if (!target.startsWith('/')) {
        throw HttpProtocolException(400, "only origin-form request targets are supported")
    }
    val queryAt = target.indexOf('?')
    val path = if (queryAt >= 0) target.substring(0, queryAt) else target
    val queryString = if (queryAt >= 0) target.substring(queryAt + 1) else null
    return ParsedRequestLine(method = method, target = target, path = path, queryString = queryString, version = version)
}

private suspend fun parseHeaders(
    reader: BufferedSocketReader,
    config: HttpServerConfig,
): List<HttpHeader> {
    val headers = ArrayList<HttpHeader>()
    var totalBytes = 0
    while (true) {
        val line = reader.readLine(
            maxBytes = config.maxHeaderBytes,
            overflowStatus = 431,
            overflowMessage = "request headers are too large",
        )
            ?: throw HttpProtocolException(400, "unexpected EOF while reading headers")
        totalBytes += line.length + 2
        if (totalBytes > config.maxHeaderBytes) {
            throw HttpProtocolException(431, "request headers are too large")
        }
        if (line.isEmpty()) return headers
        if (line.firstOrNull() == ' ' || line.firstOrNull() == '\t') {
            throw HttpProtocolException(400, "obsolete folded headers are not supported")
        }
        val colonAt = line.indexOf(':')
        if (colonAt <= 0) throw HttpProtocolException(400, "invalid header syntax")
        val name = line.substring(0, colonAt)
        if (!name.all(::isHttpTokenChar)) {
            throw HttpProtocolException(400, "invalid header name: $name")
        }
        val value = line.substring(colonAt + 1).trim(' ', '\t')
        if (value.any { it == '\r' || it == '\n' || it.code < 0x20 && it != '\t' }) {
            throw HttpProtocolException(400, "invalid header value")
        }
        headers += HttpHeader(name, value)
        if (headers.size > config.maxHeaderCount) {
            throw HttpProtocolException(431, "too many headers")
        }
    }
}

private fun validateHost(headers: HttpHeaders) {
    val values = headers.all("Host").map(String::trim)
    if (values.isEmpty()) throw HttpProtocolException(400, "Host header is required")
    if (values.distinct().size > 1) throw HttpProtocolException(400, "conflicting Host header values")
}

private fun parseContentLength(headers: HttpHeaders, config: HttpServerConfig): Int? {
    val values = headers.all("Content-Length")
    if (values.isEmpty()) return null
    val normalized = values.flatMap { raw -> raw.split(',').map(String::trim) }
    if (normalized.any { it.isEmpty() }) throw HttpProtocolException(400, "invalid Content-Length")
    val distinct = normalized.distinct()
    if (distinct.size > 1) throw HttpProtocolException(400, "conflicting Content-Length values")
    val parsed = distinct.single().toLongOrNull() ?: throw HttpProtocolException(400, "invalid Content-Length")
    if (parsed < 0L || parsed > Int.MAX_VALUE.toLong()) throw HttpProtocolException(400, "invalid Content-Length")
    if (parsed > config.maxBodyBytes.toLong()) throw HttpProtocolException(413, "request body is too large")
    return parsed.toInt()
}

private fun validateUnsupportedRequestFeatures(headers: HttpHeaders) {
    if (headers.all("Transfer-Encoding").isNotEmpty()) {
        throw HttpProtocolException(501, "Transfer-Encoding is not supported")
    }
    if (headers.first("Expect")?.equals("100-continue", ignoreCase = true) == true) {
        throw HttpProtocolException(501, "Expect: 100-continue is not supported")
    }
    val upgrade = headers.first("Upgrade")
    if (upgrade != null && !upgrade.equals("websocket", ignoreCase = true)) {
        throw HttpProtocolException(501, "unsupported Upgrade value")
    }
}

private fun isWebSocketUpgrade(method: String, headers: HttpHeaders): Boolean =
    method.equals("GET", ignoreCase = true) &&
        headers.first("Upgrade")?.equals("websocket", ignoreCase = true) == true &&
        headers.containsToken("Connection", "upgrade")

private fun validateWebSocketUpgradeRequest(
    headers: HttpHeaders,
    method: String,
    contentLength: Int?,
    wantsWebSocketUpgrade: Boolean,
) {
    if (!wantsWebSocketUpgrade) return
    if (!method.equals("GET", ignoreCase = true)) {
        throw HttpProtocolException(400, "websocket upgrade requires GET")
    }
    if (contentLength != null && contentLength != 0) {
        throw HttpProtocolException(400, "websocket upgrade request must not include a body")
    }
    if (headers.first("Sec-WebSocket-Key").isNullOrBlank()) {
        throw HttpProtocolException(400, "missing Sec-WebSocket-Key")
    }
    if (headers.first("Sec-WebSocket-Version") != "13") {
        throw HttpProtocolException(400, "unsupported Sec-WebSocket-Version")
    }
}

private fun isHttpTokenChar(ch: Char): Boolean =
    ch in '0'..'9' || ch in 'A'..'Z' || ch in 'a'..'z' || ch in setOf(
        '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~'
    )
