package net.sergeych.lyngio.http.server

import net.sergeych.lyngio.net.LyngSocketAddress
import net.sergeych.lyngio.ws.LyngWsMessage

internal data class HttpServerConfig(
    val host: String? = "127.0.0.1",
    val port: Int = 0,
    val backlog: Int = 128,
    val reuseAddress: Boolean = true,
    val maxRequestLineBytes: Int = 8 * 1024,
    val maxHeaderBytes: Int = 32 * 1024,
    val maxHeaderCount: Int = 100,
    val maxBodyBytes: Int = 1024 * 1024,
    val keepAliveTimeoutMillis: Long = 15_000,
)

internal data class HttpHeader(
    val name: String,
    val value: String,
)

internal class HttpHeaders(
    private val headerEntries: List<HttpHeader>,
) {
    fun first(name: String): String? =
        headerEntries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    fun all(name: String): List<String> =
        headerEntries.filter { it.name.equals(name, ignoreCase = true) }.map(HttpHeader::value)

    fun containsToken(name: String, token: String): Boolean =
        all(name).flatMap { value -> value.split(',') }
            .any { it.trim().equals(token, ignoreCase = true) }

    fun entries(): List<HttpHeader> = headerEntries
}

internal data class HttpRequestHead(
    val method: String,
    val target: String,
    val path: String,
    val queryString: String?,
    val version: String,
    val headers: HttpHeaders,
    val contentLength: Int?,
    val wantsClose: Boolean,
    val wantsWebSocketUpgrade: Boolean,
) {
    private var pathPartsParsed = false
    private var pathPartsCache: List<String> = emptyList()
    private var queryParsed = false
    private var queryCache: Map<String, String> = emptyMap()

    val pathParts: List<String>
        get() {
            if (!pathPartsParsed) {
                pathPartsCache = parsePathParts(path)
                pathPartsParsed = true
            }
            return pathPartsCache
        }

    val query: Map<String, String>
        get() {
            if (!queryParsed) {
                queryCache = parseQueryParameters(queryString)
                queryParsed = true
            }
            return queryCache
        }
}

internal data class HttpRequest(
    val head: HttpRequestHead,
    val body: ByteArray,
)

internal data class HttpResponse(
    val status: Int,
    val reason: String = defaultReason(status),
    val headers: List<HttpHeader> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val close: Boolean = false,
)

internal interface HttpWebSocketSession {
    fun isOpen(): Boolean
    suspend fun sendText(text: String)
    suspend fun sendBytes(data: ByteArray)
    suspend fun receive(): LyngWsMessage?
    suspend fun close(code: Int = 1000, reason: String = "")
}

internal sealed interface HttpHandlerResult {
    data class Response(val response: HttpResponse) : HttpHandlerResult
    data class WebSocket(val handler: suspend (HttpWebSocketSession) -> Unit) : HttpHandlerResult
}

internal fun interface HttpHandler {
    suspend fun handle(request: HttpRequest): HttpHandlerResult
}

internal interface HttpServer {
    fun isOpen(): Boolean
    fun localAddress(): LyngSocketAddress
    fun close()
}

internal fun parsePathParts(path: String): List<String> {
    if (path.isEmpty() || path == "/") return emptyList()
    val raw = if (path.startsWith('/')) path.substring(1) else path
    if (raw.isEmpty()) return emptyList()
    return raw.split('/').map(::decodePathSegment)
}

internal fun parseQueryParameters(queryString: String?): Map<String, String> {
    if (queryString.isNullOrEmpty()) return emptyMap()
    val result = linkedMapOf<String, String>()
    var start = 0
    while (start <= queryString.length) {
        val nextAmp = queryString.indexOf('&', start).let { if (it >= 0) it else queryString.length }
        if (nextAmp > start) {
            val part = queryString.substring(start, nextAmp)
            val eqAt = part.indexOf('=')
            val rawKey = if (eqAt >= 0) part.substring(0, eqAt) else part
            val rawValue = if (eqAt >= 0) part.substring(eqAt + 1) else ""
            result[decodeQueryComponent(rawKey, plusAsSpace = true)] = decodeQueryComponent(rawValue, plusAsSpace = true)
        }
        if (nextAmp == queryString.length) break
        start = nextAmp + 1
    }
    return result
}

internal fun decodePathSegment(value: String): String = decodeQueryComponent(value, plusAsSpace = false)

private fun decodeQueryComponent(value: String, plusAsSpace: Boolean): String {
    if (value.isEmpty()) return value
    val out = StringBuilder(value.length)
    val bytes = ArrayList<Byte>()

    fun flushBytes() {
        if (bytes.isEmpty()) return
        out.append(bytes.toByteArray().decodeToString())
        bytes.clear()
    }

    var i = 0
    while (i < value.length) {
        when (val ch = value[i]) {
            '+' -> {
                flushBytes()
                out.append(if (plusAsSpace) ' ' else '+')
                i += 1
            }
            '%' -> {
                val decoded = decodePercentByte(value, i)
                if (decoded != null) {
                    bytes += decoded.first.toByte()
                    i = decoded.second
                } else {
                    flushBytes()
                    out.append('%')
                    i += 1
                }
            }
            else -> {
                flushBytes()
                out.append(ch)
                i += 1
            }
        }
    }
    flushBytes()
    return out.toString()
}

private fun decodePercentByte(value: String, offset: Int): Pair<Int, Int>? {
    if (offset + 2 >= value.length) return null
    val hi = value[offset + 1].hexDigitValueOrNull() ?: return null
    val lo = value[offset + 2].hexDigitValueOrNull() ?: return null
    return ((hi shl 4) or lo) to (offset + 3)
}

private fun Char.hexDigitValueOrNull(): Int? = when (this) {
    in '0'..'9' -> code - '0'.code
    in 'a'..'f' -> code - 'a'.code + 10
    in 'A'..'F' -> code - 'A'.code + 10
    else -> null
}

internal fun defaultReason(status: Int): String = when (status) {
    101 -> "Switching Protocols"
    200 -> "OK"
    204 -> "No Content"
    400 -> "Bad Request"
    404 -> "Not Found"
    413 -> "Payload Too Large"
    414 -> "URI Too Long"
    426 -> "Upgrade Required"
    431 -> "Request Header Fields Too Large"
    500 -> "Internal Server Error"
    501 -> "Not Implemented"
    505 -> "HTTP Version Not Supported"
    else -> "HTTP $status"
}
