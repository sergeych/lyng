package net.sergeych.lyngio.http.server

import net.sergeych.lyngio.net.LyngTcpSocket

internal suspend fun writeHttpResponse(
    socket: LyngTcpSocket,
    response: HttpResponse,
    closeConnection: Boolean,
) {
    val body = response.body
    val headerLines = LinkedHashMap<String, MutableList<String>>()
    response.headers.forEach { header ->
        headerLines.getOrPut(header.name) { mutableListOf() }.add(header.value)
    }
    if (headerLines.keys.none { it.equals("Content-Length", ignoreCase = true) }) {
        headerLines["Content-Length"] = mutableListOf(body.size.toString())
    }
    if (closeConnection) {
        val connectionKey = headerLines.keys.firstOrNull { it.equals("Connection", ignoreCase = true) }
        if (connectionKey != null) {
            headerLines.remove(connectionKey)
        }
        headerLines["Connection"] = mutableListOf("close")
    }
    val head = buildString {
        append("HTTP/1.1 ")
        append(response.status)
        append(' ')
        append(response.reason)
        append("\r\n")
        headerLines.forEach { (name, values) ->
            values.forEach { value ->
                append(name)
                append(": ")
                append(value)
                append("\r\n")
            }
        }
        append("\r\n")
    }
    socket.writeUtf8(head)
    if (body.isNotEmpty()) socket.write(body)
    socket.flush()
}
