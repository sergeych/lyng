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
package net.sergeych.lyng_cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import net.sergeych.jvmExitImpl
import net.sergeych.runMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress

class CliNetworkJvmTest {
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err

    private class TestExit(val code: Int) : RuntimeException()

    @Before
    fun setUp() {
        jvmExitImpl = { code -> throw TestExit(code) }
    }

    @After
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        jvmExitImpl = { code -> kotlin.system.exitProcess(code) }
    }

    private data class CliResult(val out: String, val err: String, val exitCode: Int?)

    private fun runCli(vararg args: String): CliResult {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuf, true, Charsets.UTF_8))
        System.setErr(PrintStream(errBuf, true, Charsets.UTF_8))

        var exitCode: Int? = null
        try {
            runMain(arrayOf(*args))
        } catch (e: TestExit) {
            exitCode = e.code
        } finally {
            System.out.flush()
            System.err.flush()
        }
        return CliResult(outBuf.toString("UTF-8"), errBuf.toString("UTF-8"), exitCode)
    }

    @Test
    fun cliHasAllNetworkingModulesInstalled() {
        val server = newServer()
        try {
            val script = """
                import lyng.io.http
                import lyng.io.ws
                import lyng.io.net

                assert(Http.isSupported())
                println("ws=" + Ws.isSupported())
                println("net=" + Net.isSupported())

                val home = Http.get("http://127.0.0.1:${server.address.port}/").text()
                val jsRef = "src=\"([^\"]*lyng-version\\.js)\"".re.find(home)
                require(jsRef != null, "lyng-version.js reference not found")

                val versionJsPath = (jsRef as RegexMatch)[1]
                val versionJs = Http.get("http://127.0.0.1:${server.address.port}/" + versionJsPath).text()
                val versionMatch = "LYNG_VERSION\\s*=\\s*\"([^\"]+)\"".re.find(versionJs)
                require(versionMatch != null, "LYNG_VERSION assignment not found")

                println("version=" + ((versionMatch as RegexMatch)[1]))
            """.trimIndent()

            val result = runCli("-x", script)
            assertNull(result.exitCode)
            assertTrue(result.err, result.err.isBlank())
            assertTrue(result.out, result.out.contains("ws="))
            assertTrue(result.out, result.out.contains("net="))
            assertTrue(result.out, result.out.contains("version=9.9.9-test"))
        } finally {
            server.stop(0)
        }
    }

    private fun newServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            when (exchange.requestURI.path) {
                "/" -> writeResponse(
                    exchange,
                    200,
                    """
                    <!doctype html>
                    <html>
                    <head>
                        <script src="lyng-version.js"></script>
                    </head>
                    <body>
                        <span id="lyng-version-ribbon"></span>
                    </body>
                    </html>
                    """.trimIndent()
                )

                "/lyng-version.js" -> writeResponse(exchange, 200, """window.LYNG_VERSION = "9.9.9-test";""")
                else -> writeResponse(exchange, 404, "missing")
            }
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
