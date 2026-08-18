package net.sergeych.lyngio.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KtorHttpRedirectTest {
    @Test
    fun redirectsWithinLimitRemainTransparent() = runBlocking {
        val server = newServer { exchange ->
            when (exchange.requestURI.path) {
                "/start" -> redirect(exchange, "/middle")
                "/middle" -> redirect(exchange, "/result")
                else -> writeResponse(exchange, 200, "redirected")
            }
        }
        try {
            val engine = createKtorHttpEngine(CIO, LyngHttpConfig(maxRedirects = 2))
            val response = engine.request(LyngHttpRequest("GET", url(server, "/start")))
            assertEquals(200, response.status)
            assertEquals("redirected", response.bodyBytes.decodeToString())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun redirectLimitProducesDiagnosticError() = runBlocking {
        val server = newServer { exchange ->
            val next = exchange.requestURI.path.removePrefix("/").toIntOrNull()?.plus(1) ?: 1
            redirect(exchange, "/$next")
        }
        try {
            val engine = createKtorHttpEngine(CIO, LyngHttpConfig(maxRedirects = 2))
            val error = assertFailsWith<HttpRedirectLimitExceededException> {
                engine.request(LyngHttpRequest("GET", url(server, "/0")))
            }
            assertEquals(2, error.limit)
            assertTrue(error.initialUrl.endsWith("/0"), error.initialUrl)
            assertTrue(error.lastUrl.endsWith("/3"), error.lastUrl)
            assertTrue(error.message.orEmpty().startsWith("HTTP_REDIRECT_LIMIT_EXCEEDED:"), error.message)
        } finally {
            server.stop(0)
        }
    }

    private fun newServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            start()
        }

    private fun url(server: HttpServer, path: String) =
        "http://127.0.0.1:${server.address.port}$path"

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun writeResponse(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.encodeToByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
