/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.sergeych.lyng.io.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.Script
import net.sergeych.lyngio.fs.security.AccessContext
import net.sergeych.lyngio.fs.security.AccessDecision
import net.sergeych.lyngio.fs.security.Decision
import net.sergeych.lyngio.http.security.HttpAccessOp
import net.sergeych.lyngio.http.security.HttpAccessPolicy
import net.sergeych.lyngio.http.security.PermitAllHttpAccessPolicy
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LyngHttpModuleTest {

    @Test
    fun testConvenienceGetAndHeaders() = runBlocking {
        val server = newServer { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.add("X-Reply", "one")
            exchange.responseHeaders.add("X-Reply", "two")
            writeResponse(exchange, 200, "hello from test")
        }
        try {
            val scope = Script.newScope()
            createHttpModule(PermitAllHttpAccessPolicy, scope)

            val code = """
                import lyng.io.http

                val r = Http.get(
                    "http://127.0.0.1:${server.address.port}/hello",
                    "Accept" => "text/plain",
                    "X-Test" => "yes"
                )
                [r.status, r.headers["Content-Type"], r.headers.getAll("X-Reply").size, r.text()]
            """.trimIndent()

            val result = Compiler.compile(code).execute(scope)
            val rendered = result.inspect(scope)
            assertTrue(rendered.contains("200"), rendered)
            assertTrue(rendered.contains("text/plain"), rendered)
            assertTrue(rendered.contains("2"), rendered)
            assertTrue(rendered.contains("hello from test"), rendered)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testMutableRequestPost() = runBlocking {
        val server = newServer { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            writeResponse(exchange, 200, exchange.requestMethod + ":" + body)
        }
        try {
            val scope = Script.newScope()
            createHttpModule(PermitAllHttpAccessPolicy, scope)

            val code = """
                import lyng.io.http

                val q = HttpRequest()
                q.method = "POST"
                q.url = "http://127.0.0.1:${server.address.port}/echo"
                q.headers = Map("Content-Type" => "text/plain")
                q.bodyText = "ping"

                val r = Http.request(q)
                r.text()
            """.trimIndent()

            val result = Compiler.compile(code).execute(scope)
            assertTrue(result.inspect(scope).contains("POST:ping"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testPolicyDenialSurfacesAsLyngError() = runBlocking {
        val scope = Script.newScope()
        val denyAll = object : HttpAccessPolicy {
            override suspend fun check(op: HttpAccessOp, ctx: AccessContext): AccessDecision =
                AccessDecision(Decision.Deny, "blocked by test policy")
        }
        createHttpModule(denyAll, scope)

        val code = """
            import lyng.io.http
            Http.get("http://127.0.0.1:1/")
        """.trimIndent()

        val error = assertFailsWith<ExecutionError> {
            Compiler.compile(code).execute(scope)
        }
        assertTrue(error.errorMessage.isNotBlank())
    }

    private fun newServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            handler(exchange)
        }
        server.start()
        return server
    }

    private fun writeResponse(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { out ->
            out.write(bytes)
        }
    }
}
