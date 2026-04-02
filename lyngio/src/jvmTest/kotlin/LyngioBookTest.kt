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

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Script
import net.sergeych.lyng.io.http.createHttpModule
import net.sergeych.lyng.io.net.createNetModule
import net.sergeych.lyng.io.testtls.TlsTestMaterial
import net.sergeych.lyng.io.ws.TestWebSocketServer
import net.sergeych.lyng.io.ws.createWsModule
import net.sergeych.lyng.leftMargin
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyngio.http.security.PermitAllHttpAccessPolicy
import net.sergeych.lyngio.net.security.PermitAllNetAccessPolicy
import net.sergeych.lyngio.ws.security.PermitAllWsAccessPolicy
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files.readAllLines
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals

data class IoDocTest(
    val fileName: String,
    val line: Int,
    val code: String,
    val expectedOutput: String,
    val expectedResult: String,
) {
    val fileNamePart by lazy { Paths.get(fileName).fileName.toString() }

    override fun toString(): String = "DocTest: ${Paths.get(fileName).absolutePathString()}:${line + 1}"
}

private fun parseIoDocTests(fileName: String): Flow<IoDocTest> = flow {
    val book = readAllLines(Paths.get(fileName))
    var startOffset = 0
    val block = mutableListOf<String>()
    var startIndex = 0
    for ((index, l) in book.withIndex()) {
        val off = leftMargin(l)
        when {
            off < startOffset && startOffset != 0 -> {
                if (l.isBlank()) continue
                if (block.size > 1) {
                    for ((i, s) in block.withIndex()) {
                        var x = s
                        val initial = leftMargin(x)
                        do {
                            x = x.drop(1)
                        } while (initial - leftMargin(x) != startOffset)
                        block[i] = x
                    }
                    val outStart = block.indexOfFirst { it.startsWith(">>>") }
                    if (outStart >= 0) {
                        val result = mutableListOf<String>()
                        while (block.lastOrNull()?.isEmpty() == true) block.removeLast()
                        var valid = true
                        while (block.size > outStart) {
                            val line = block.removeAt(outStart)
                            if (!line.startsWith(">>> ")) {
                                valid = false
                                break
                            }
                            result.add(line.drop(4))
                        }
                        if (valid) {
                            emit(
                                IoDocTest(
                                    fileName = fileName,
                                    line = startIndex,
                                    code = block.joinToString("\n"),
                                    expectedOutput = if (result.size > 1) result.dropLast(1).joinToString("") { "$it\n" } else "",
                                    expectedResult = result.last(),
                                )
                            )
                        }
                    }
                }
                block.clear()
                startOffset = 0
            }

            off != 0 && startOffset == 0 -> {
                block.clear()
                startIndex = index
                block.add(l)
                startOffset = off
            }

            off != 0 -> block.add(l)
        }
    }
}.flowOn(Dispatchers.IO)

private suspend fun IoDocTest.run(scope: Scope) {
    val output = StringBuilder()
    scope.addFn("println") {
        for ((i, a) in args.withIndex()) {
            if (i > 0) output.append(' ')
            output.append(toStringOf(a).value)
        }
        output.append('\n')
        ObjVoid
    }
    val result = scope.eval(code).inspect(scope).replace(Regex("@\\d+"), "@...")
    assertEquals(expectedOutput, output.toString(), "script output mismatch at $this")
    assertEquals(expectedResult, result, "script result mismatch at $this")
}

private suspend fun runIoDocTests(
    fileName: String,
    installModules: suspend (Scope) -> Unit,
    installConsts: suspend (Scope) -> Unit = {},
) {
    parseIoDocTests(fileName).collect { test ->
        val scope = Script.newScope()
        installModules(scope)
        installConsts(scope)
        test.run(scope)
    }
}

class LyngioBookTest {

    @Test
    fun testHttpDocs() = runBlocking {
        val server = newHttpServer(secure = false)
        val secureServer = newHttpServer(secure = true)
        try {
            runIoDocTests(
                fileName = "../docs/lyng.io.http.md",
                installModules = { scope -> createHttpModule(PermitAllHttpAccessPolicy, scope) },
                installConsts = { scope ->
                    scope.addConst("HTTP_TEST_URL", ObjString("http://127.0.0.1:${server.address.port}"))
                    scope.addConst("HTTPS_TEST_URL", ObjString("https://127.0.0.1:${secureServer.address.port}"))
                },
            )
        } finally {
            server.stop(0)
            secureServer.stop(0)
        }
    }

    @Test
    fun testNetDocs() = runBlocking {
        ServerSocket(0, 50).use { server ->
            val worker = thread(start = true) {
                server.accept().use { client ->
                    val line = client.getInputStream().readNBytes(4).decodeToString()
                    client.getOutputStream().write(("reply:" + line).toByteArray())
                    client.getOutputStream().flush()
                }
            }
            try {
                runIoDocTests(
                    fileName = "../docs/lyng.io.net.md",
                    installModules = { scope -> createNetModule(PermitAllNetAccessPolicy, scope) },
                    installConsts = { scope -> scope.addConst("NET_TEST_TCP_PORT", ObjInt(server.localPort.toLong())) },
                )
            } finally {
                worker.join(2000)
            }
        }
    }

    @Test
    fun testWsDocs() = runBlocking {
        TestWebSocketServer { connection ->
            val text = connection.receiveText()
            connection.sendText("echo:$text")
            connection.close()
        }.use { textServer ->
            TestWebSocketServer { connection ->
                val data = connection.receiveBinary()
                connection.sendBinary(byteArrayOf(1, 2, 3) + data)
                connection.close()
            }.use { binaryServer ->
                TestWebSocketServer(secure = true) { connection ->
                    val text = connection.receiveText()
                    connection.sendText("secure:$text")
                    connection.close()
                }.use { secureServer ->
                    runIoDocTests(
                        fileName = "../docs/lyng.io.ws.md",
                        installModules = { scope -> createWsModule(PermitAllWsAccessPolicy, scope) },
                        installConsts = { scope ->
                            scope.addConst("WS_TEST_URL", ObjString(textServer.url))
                            scope.addConst("WS_TEST_BINARY_URL", ObjString(binaryServer.url))
                            scope.addConst("WSS_TEST_URL", ObjString(secureServer.url))
                        },
                    )
                }
            }
        }
    }

    private fun writeResponse(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun newHttpServer(secure: Boolean): HttpServer {
        if (secure) TlsTestMaterial.installJvmClientTrust()
        val server = if (secure) {
            HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                httpsConfigurator = HttpsConfigurator(TlsTestMaterial.sslContext)
            }
        } else {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        }
        server.createContext("/hello") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            writeResponse(exchange, 200, "hello from test")
        }
        server.createContext("/headers") { exchange ->
            exchange.responseHeaders.add("X-Reply", "one")
            exchange.responseHeaders.add("X-Reply", "two")
            writeResponse(exchange, 200, "header demo")
        }
        server.createContext("/echo") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            writeResponse(exchange, 200, exchange.requestMethod + ":" + body)
        }
        server.start()
        return server
    }
}
