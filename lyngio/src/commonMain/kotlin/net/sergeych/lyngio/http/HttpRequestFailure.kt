package net.sergeych.lyngio.http

/** Transport diagnostics, preserved by the Lyng bridge instead of a generic "http error". */
class LyngHttpRequestException(request: LyngHttpRequest, cause: Throwable) :
    IllegalStateException(describeHttpFailure(request, cause), cause)

internal fun describeHttpFailure(request: LyngHttpRequest, cause: Throwable): String {
    val causes = mutableListOf<Throwable>()
    var current: Throwable? = cause
    repeat(8) {
        val item = current ?: return@repeat
        if (causes.any { it === item }) return@repeat
        causes += item
        current = item.cause
    }
    val details = causes.joinToString(" <- ") { "${it::class.simpleName ?: "Exception"}: ${it.message ?: "no detail"}" }
    val text = details.lowercase()
    val (code, advice) = when {
        "http_redirect_limit_exceeded" in text -> "HTTP_REDIRECT_LIMIT_EXCEEDED" to
            "Do not retry this URL unchanged; inspect the redirect chain or use another source."
        "tls" in text || "ssl" in text || "handshake" in text || "certificate" in text -> "HTTP_TLS_FAILED" to
            "The HTTPS handshake failed before an HTTP response. Do not retry this URL unchanged; changing its path/query will not repair TLS. Use another HTTPS host or source."
        "timeout" in text || "timed out" in text -> "HTTP_TIMEOUT" to
            "The request exceeded a time limit. At most a bounded retry may help; avoid repeating it indefinitely."
        "unknownhost" in text || "unresolvedaddress" in text || "name or service not known" in text -> "HTTP_DNS_FAILED" to
            "The hostname could not be resolved. Verify the hostname or use another source."
        "connectexception" in text || "connection refused" in text || "connection reset" in text -> "HTTP_CONNECTION_FAILED" to
            "The connection failed before a usable response. Verify the endpoint; avoid repeated unchanged requests."
        else -> "HTTP_REQUEST_FAILED" to "Use the cause details to choose another source or a different approach."
    }
    // Query strings and userinfo can contain credentials; the script already knows the full URL.
    val target = request.url.substringBefore('?').substringBefore('#').replace(Regex("//[^/@]+@"), "//[redacted]@")
    return "$code: ${request.method.uppercase()} $target\nCause: $details\n$advice"
}
