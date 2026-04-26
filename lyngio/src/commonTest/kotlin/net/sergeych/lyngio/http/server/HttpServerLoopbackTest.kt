package net.sergeych.lyngio.http.server

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.sergeych.lyngio.net.LyngTcpSocket
import net.sergeych.lyngio.net.getSystemNetEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpServerLoopbackTest {

    @Test
    fun simpleGetReturnsResponse() = runBlocking {
        val engine = getSystemNetEngine()
        if (!engine.isSupported || !engine.isTcpAvailable || !engine.isTcpServerAvailable) return@runBlocking

        withTimeout(10_000) {
            val server = startHttpServer { request ->
                HttpHandlerResult.Response(
                    HttpResponse(
                        status = 200,
                        headers = listOf(HttpHeader("Content-Type", "text/plain")),
                        body = "hello:${request.head.path}".encodeToByteArray(),
                    )
                )
            }
            try {
                val port = waitForPort(server)
                val client = engine.tcpConnect("127.0.0.1", port, 2_000, true)
                try {
                    client.writeUtf8("GET /demo HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    client.flush()
                    val text = readHttpResponse(client)
                    assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"), text)
                    assertTrue(text.contains("Content-Type: text/plain\r\n"), text)
                    assertTrue(text.endsWith("hello:/demo"), text)
                } finally {
                    client.close()
                }
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun keepAliveServesTwoRequestsOnOneSocket() = runBlocking {
        val engine = getSystemNetEngine()
        if (!engine.isSupported || !engine.isTcpAvailable || !engine.isTcpServerAvailable) return@runBlocking

        withTimeout(10_000) {
            val server = startHttpServer { request ->
                HttpHandlerResult.Response(
                    HttpResponse(status = 200, body = request.head.path.encodeToByteArray())
                )
            }
            try {
                val port = waitForPort(server)
                val client = engine.tcpConnect("127.0.0.1", port, 2_000, true)
                try {
                    client.writeUtf8(
                        "GET /one HTTP/1.1\r\nHost: localhost\r\n\r\n" +
                            "GET /two HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    )
                    client.flush()
                    val first = readHttpResponse(client)
                    val second = readHttpResponse(client)
                    assertTrue(first.endsWith("/one"), first)
                    assertTrue(second.contains("Connection: close\r\n"), second)
                    assertTrue(second.endsWith("/two"), second)
                } finally {
                    client.close()
                }
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun postWithContentLengthReadsBody() = runBlocking {
        val engine = getSystemNetEngine()
        if (!engine.isSupported || !engine.isTcpAvailable || !engine.isTcpServerAvailable) return@runBlocking

        withTimeout(10_000) {
            val server = startHttpServer { request ->
                HttpHandlerResult.Response(
                    HttpResponse(status = 200, body = (request.head.method + ":" + request.body.decodeToString()).encodeToByteArray())
                )
            }
            try {
                val port = waitForPort(server)
                val client = engine.tcpConnect("127.0.0.1", port, 2_000, true)
                try {
                    client.writeUtf8(
                        "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\nConnection: close\r\n\r\nping"
                    )
                    client.flush()
                    val text = readHttpResponse(client)
                    assertTrue(text.endsWith("POST:ping"), text)
                } finally {
                    client.close()
                }
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun transferEncodingIsRejected() = runBlocking {
        val engine = getSystemNetEngine()
        if (!engine.isSupported || !engine.isTcpAvailable || !engine.isTcpServerAvailable) return@runBlocking

        withTimeout(10_000) {
            val server = startHttpServer { _ ->
                HttpHandlerResult.Response(HttpResponse(status = 200, body = "ok".encodeToByteArray()))
            }
            try {
                val port = waitForPort(server)
                val client = engine.tcpConnect("127.0.0.1", port, 2_000, true)
                try {
                    client.writeUtf8(
                        "POST /x HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    )
                    client.flush()
                    val text = readHttpResponse(client)
                    assertTrue(text.startsWith("HTTP/1.1 501 Not Implemented\r\n"), text)
                } finally {
                    client.close()
                }
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun websocketUpgradeEchoesText() = runBlocking {
        val engine = getSystemNetEngine()
        if (!engine.isSupported || !engine.isTcpAvailable || !engine.isTcpServerAvailable) return@runBlocking

        withTimeout(10_000) {
            val server = startHttpServer { request ->
                if (request.head.path != "/ws") {
                    HttpHandlerResult.Response(HttpResponse(status = 404, close = true))
                } else {
                    HttpHandlerResult.WebSocket { session ->
                        val message = session.receive() ?: return@WebSocket
                        session.sendText("echo:${message.text}")
                    }
                }
            }
            try {
                val port = waitForPort(server)
                val client = engine.tcpConnect("127.0.0.1", port, 2_000, true)
                try {
                    val key = "dGhlIHNhbXBsZSBub25jZQ=="
                    client.writeUtf8(
                        "GET /ws HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Key: $key\r\n" +
                            "Sec-WebSocket-Version: 13\r\n\r\n"
                    )
                    client.flush()
                    val headers = ArrayList<String>()
                    while (true) {
                        val line = client.readLine() ?: break
                        if (line.isEmpty()) break
                        headers += line
                    }
                    assertEquals("HTTP/1.1 101 Switching Protocols", headers.first())
                    sendMaskedTextFrame(client, "ping")
                    val reply = readServerTextFrame(client)
                    assertEquals("echo:ping", reply)
                } finally {
                    client.close()
                }
            } finally {
                server.close()
            }
        }
    }

    private suspend fun waitForPort(server: HttpServer): Int {
        repeat(100) {
            runCatching { return server.localAddress().port }
            kotlinx.coroutines.delay(10)
        }
        error("server did not bind in time")
    }

    private suspend fun readHttpResponse(client: LyngTcpSocket): String {
        val statusLine = client.readLine() ?: error("missing status line")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = client.readLine() ?: error("unexpected EOF in response headers")
            if (line.isEmpty()) break
            val colonAt = line.indexOf(':')
            if (colonAt > 0) headers[line.substring(0, colonAt)] = line.substring(colonAt + 1).trim()
        }
        val bodyLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val body = if (bodyLength > 0) readExact(client, bodyLength).decodeToString() else ""
        return buildString {
            append(statusLine).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
            append(body)
        }
    }

    private suspend fun sendMaskedTextFrame(client: LyngTcpSocket, text: String) {
        val payload = text.encodeToByteArray()
        val mask = byteArrayOf(1, 2, 3, 4)
        val masked = payload.copyOf()
        masked.indices.forEach { index ->
            masked[index] = (masked[index].toInt() xor mask[index % mask.size].toInt()).toByte()
        }
        val frame = byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte()) + mask + masked
        client.write(frame)
        client.flush()
    }

    private suspend fun readServerTextFrame(client: LyngTcpSocket): String {
        val head = readExact(client, 2)
        val len = head[1].toInt() and 0x7f
        val payload = if (len > 0) readExact(client, len) else ByteArray(0)
        return payload.decodeToString()
    }

    private suspend fun readExact(client: LyngTcpSocket, size: Int): ByteArray {
        var pending = ByteArray(0)
        while (pending.size < size) {
            val chunk = client.read(size - pending.size) ?: error("unexpected EOF")
            pending += chunk
        }
        return pending
    }
}
