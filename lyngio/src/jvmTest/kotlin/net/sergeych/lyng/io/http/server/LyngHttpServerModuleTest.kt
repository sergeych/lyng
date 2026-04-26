package net.sergeych.lyng.io.http.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Script
import net.sergeych.lyng.io.http.server.createHttpServerModule
import net.sergeych.lyng.io.ws.createWsModule
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.io.http.createHttpModule
import net.sergeych.lyngio.net.getSystemNetEngine
import net.sergeych.lyngio.http.security.PermitAllHttpAccessPolicy
import net.sergeych.lyngio.net.security.PermitAllNetAccessPolicy
import net.sergeych.lyngio.ws.security.PermitAllWsAccessPolicy
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LyngHttpServerModuleTest {

    @Test
    fun serverModuleReusesSharedHttpHeadersRuntimeType() = runBlocking {
        val scope = Script.newScope()
        createHttpModule(PermitAllHttpAccessPolicy, scope)
        createWsModule(PermitAllWsAccessPolicy, scope)
        createHttpServerModule(PermitAllNetAccessPolicy, scope)

        val httpModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.http")
        val wsModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.ws")
        val serverModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.http.server")
        val sharedTypesModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.http.types")
        val sharedWsTypesModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.ws.types")

        assertSame(sharedTypesModule.get("HttpHeaders")?.value, httpModule.get("HttpHeaders")?.value)
        assertSame(sharedTypesModule.get("HttpHeaders")?.value, serverModule.get("HttpHeaders")?.value)
        assertSame(sharedWsTypesModule.get("WsMessage")?.value, wsModule.get("WsMessage")?.value)
        assertSame(sharedWsTypesModule.get("WsMessage")?.value, serverModule.get("WsMessage")?.value)
    }

    @Test
    fun exactRouteAndFallbackWork() = runBlocking {
        val engine = getSystemNetEngine()
        if (!engine.isSupported || !engine.isTcpAvailable) return@runBlocking

        val scope = Script.newScope()
        createHttpServerModule(PermitAllNetAccessPolicy, scope)

        val code = """
            import lyng.io.http.server

            val server = HttpServer()
            server.get("/hello") { ex ->
                ex.setHeader("Content-Type", "text/plain")
                ex.respondText(200, "hello from lyng")
            }
            server.fallback { ex ->
                ex.respondText(404, "miss:" + ex.request.path)
            }
            server.listen(0, "127.0.0.1")
        """.trimIndent()

        val handle = Compiler.compile(code).execute(scope)
        val port = waitForPort(handle, scope)

        val client = engine.tcpConnect("127.0.0.1", port, 2_000, true)
        try {
            client.writeUtf8("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")
            client.flush()
            val hello = readHttpResponse(client)
            assertTrue(hello.contains("200 OK"), hello)
            assertTrue(hello.endsWith("hello from lyng"), hello)
        } finally {
            client.close()
        }

        val client2 = engine.tcpConnect("127.0.0.1", port, 2_000, true)
        try {
            client2.writeUtf8("GET /other HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            client2.flush()
            val miss = readHttpResponse(client2)
            assertTrue(miss.contains("404"), miss)
            assertTrue(miss.endsWith("miss:/other"), miss)
        } finally {
            client2.close()
        }

        handle.invokeInstanceMethod(scope, "close")
    }

    private suspend fun waitForPort(handle: Obj, scope: net.sergeych.lyng.Scope): Int {
        repeat(100) {
            val port = runCatching {
                val value = handle.invokeInstanceMethod(scope, "localPort")
                (value as ObjInt).value.toInt()
            }.getOrNull()
            if (port != null && port > 0) return port
            delay(10)
        }
        error("server did not bind in time")
    }

    private suspend fun readHttpResponse(client: net.sergeych.lyngio.net.LyngTcpSocket): String {
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

    private suspend fun readExact(client: net.sergeych.lyngio.net.LyngTcpSocket, size: Int): ByteArray {
        var pending = ByteArray(0)
        while (pending.size < size) {
            val chunk = client.read(size - pending.size) ?: error("unexpected EOF")
            pending += chunk
        }
        return pending
    }
}
