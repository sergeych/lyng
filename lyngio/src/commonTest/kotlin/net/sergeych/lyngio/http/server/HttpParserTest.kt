package net.sergeych.lyngio.http.server

import net.sergeych.lyngio.net.LyngIpVersion
import net.sergeych.lyngio.net.LyngSocketAddress
import net.sergeych.lyngio.net.LyngTcpSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpParserTest {

    @Test
    fun tooLargeHeadersMapTo431() = kotlinx.coroutines.test.runTest {
        val request = buildString {
            append("GET / HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("X-Big: ")
            append("a".repeat(64))
            append("\r\n\r\n")
        }
        val error = assertFailsWith<HttpProtocolException> {
            parse(request, HttpServerConfig(maxHeaderBytes = 32))
        }
        assertEquals(431, error.status)
    }

    @Test
    fun conflictingDuplicateHostIsRejected() = kotlinx.coroutines.test.runTest {
        val error = assertFailsWith<HttpProtocolException> {
            parse(
                "GET / HTTP/1.1\r\n" +
                    "Host: one.example\r\n" +
                    "Host: two.example\r\n\r\n"
            )
        }
        assertEquals(400, error.status)
    }

    @Test
    fun conflictingDuplicateContentLengthIsRejected() = kotlinx.coroutines.test.runTest {
        val error = assertFailsWith<HttpProtocolException> {
            parse(
                "POST /echo HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: 4\r\n" +
                    "Content-Length: 5\r\n\r\nping!"
            )
        }
        assertEquals(400, error.status)
    }

    @Test
    fun malformedRequestLineIsRejected() = kotlinx.coroutines.test.runTest {
        val error = assertFailsWith<HttpProtocolException> {
            parse("GET /only-two-parts\r\nHost: localhost\r\n\r\n")
        }
        assertEquals(400, error.status)
    }

    @Test
    fun identicalDuplicateContentLengthIsAccepted() = kotlinx.coroutines.test.runTest {
        val request = parse(
            "POST /echo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 4\r\n" +
                "Content-Length: 4\r\n\r\nping"
        )
        assertEquals("POST", request.head.method)
        assertEquals("/echo", request.head.path)
        assertEquals(4, request.head.contentLength)
        assertEquals("ping", request.body.decodeToString())
    }

    @Test
    fun queryStringAndDecodedQueryMapAreExposed() = kotlinx.coroutines.test.runTest {
        val request = parse(
            "GET /echo?a=1&b=hello+world&b=last&utf=%D1%82%D0%B5%D1%81%D1%82&bad=%GG%2&flag HTTP/1.1\r\n" +
                "Host: localhost\r\n\r\n"
        )
        assertEquals("a=1&b=hello+world&b=last&utf=%D1%82%D0%B5%D1%81%D1%82&bad=%GG%2&flag", request.head.queryString)
        assertEquals("1", request.head.query["a"])
        assertEquals("last", request.head.query["b"])
        assertEquals("тест", request.head.query["utf"])
        assertEquals("%GG%2", request.head.query["bad"])
        assertEquals("", request.head.query["flag"])
    }

    @Test
    fun missingQueryProducesEmptyMap() = kotlinx.coroutines.test.runTest {
        val request = parse(
            "GET /echo HTTP/1.1\r\n" +
                "Host: localhost\r\n\r\n"
        )
        assertEquals(null, request.head.queryString)
        assertEquals(emptyMap(), request.head.query)
    }

    @Test
    fun pathPartsAreLazyDecodedWithoutPlusTranslation() = kotlinx.coroutines.test.runTest {
        val request = parse(
            "GET /one/two%20words/a+b/%GG/%D1%82%D0%B5%D1%81%D1%82 HTTP/1.1\r\n" +
                "Host: localhost\r\n\r\n"
        )
        assertEquals(listOf("one", "two words", "a+b", "%GG", "тест"), request.head.pathParts)
    }

    private suspend fun parse(
        rawRequest: String,
        config: HttpServerConfig = HttpServerConfig(),
    ): HttpRequest {
        val socket = FakeTcpSocket(rawRequest.encodeToByteArray())
        val reader = BufferedSocketReader(socket)
        return parseHttpRequest(reader, config) ?: error("expected parsed request")
    }
}

private class FakeTcpSocket(
    source: ByteArray,
) : LyngTcpSocket {
    private var input = source
    private var output = ByteArray(0)
    private var open = true

    override fun isOpen(): Boolean = open

    override fun localAddress(): LyngSocketAddress =
        LyngSocketAddress("127.0.0.1", 8080, LyngIpVersion.IPV4, resolved = true)

    override fun remoteAddress(): LyngSocketAddress =
        LyngSocketAddress("127.0.0.1", 12345, LyngIpVersion.IPV4, resolved = true)

    override suspend fun read(maxBytes: Int): ByteArray? {
        if (!open || input.isEmpty()) return null
        val count = minOf(maxBytes, input.size)
        val chunk = input.copyOfRange(0, count)
        input = input.copyOfRange(count, input.size)
        return chunk
    }

    override suspend fun readLine(): String? = error("BufferedSocketReader should not call LyngTcpSocket.readLine()")

    override suspend fun write(data: ByteArray) {
        output += data
    }

    override suspend fun writeUtf8(text: String) {
        output += text.encodeToByteArray()
    }

    override suspend fun flush() = Unit

    override fun close() {
        open = false
    }
}
