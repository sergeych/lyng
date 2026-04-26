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
    val query: String?,
    val version: String,
    val headers: HttpHeaders,
    val contentLength: Int?,
    val wantsClose: Boolean,
    val wantsWebSocketUpgrade: Boolean,
)

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
