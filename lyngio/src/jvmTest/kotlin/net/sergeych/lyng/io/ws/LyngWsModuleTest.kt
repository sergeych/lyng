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

package net.sergeych.lyng.io.ws

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.Script
import net.sergeych.lyngio.fs.security.AccessContext
import net.sergeych.lyngio.fs.security.AccessDecision
import net.sergeych.lyngio.fs.security.Decision
import net.sergeych.lyngio.ws.security.PermitAllWsAccessPolicy
import net.sergeych.lyngio.ws.security.WsAccessOp
import net.sergeych.lyngio.ws.security.WsAccessPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LyngWsModuleTest {

    @Test
    fun testTextSessionAndHeaders() = runBlocking {
        TestWebSocketServer { connection ->
            assertEquals("yes", connection.requestHeaders["x-test"])
            val text = connection.receiveText()
            connection.sendText("echo:$text")
            connection.close()
        }.use { server ->
            val scope = Script.newScope()
            createWsModule(PermitAllWsAccessPolicy, scope)

            val code = """
                import lyng.io.ws

                val ws = Ws.connect("${server.url}", "X-Test" => "yes")
                ws.sendText("ping")
                val m: WsMessage = ws.receive()
                ws.close()
                [ws.url(), m.isText, m.text]
            """.trimIndent()

            val result = Compiler.compile(code).execute(scope).inspect(scope)
            assertTrue(result.contains(server.url), result)
            assertTrue(result.contains("true,echo:ping"), result)
        }
    }

    @Test
    fun testBinarySession() = runBlocking {
        TestWebSocketServer { connection ->
            val data = connection.receiveBinary()
            connection.sendBinary(byteArrayOf(1, 2, 3) + data)
            connection.close()
        }.use { server ->
            val scope = Script.newScope()
            createWsModule(PermitAllWsAccessPolicy, scope)

            val code = """
                import lyng.buffer
                import lyng.io.ws

                val ws = Ws.connect("${server.url}")
                ws.sendBytes(Buffer(9, 8, 7))
                val m: WsMessage = ws.receive()
                ws.close()
                [m.isText, (m.data as Buffer).hex]
            """.trimIndent()

            val result = Compiler.compile(code).execute(scope).inspect(scope)
            assertTrue(result.contains("false,010203090807"), result)
        }
    }

    @Test
    fun testSecureTextSession() = runBlocking {
        TestWebSocketServer(secure = true) { connection ->
            val text = connection.receiveText()
            connection.sendText("secure:$text")
            connection.close()
        }.use { server ->
            val scope = Script.newScope()
            createWsModule(PermitAllWsAccessPolicy, scope)

            val code = """
                import lyng.io.ws

                val ws = Ws.connect("${server.url}")
                ws.sendText("ping")
                val m: WsMessage = ws.receive()
                ws.close()
                [ws.url(), m.text]
            """.trimIndent()

            val result = Compiler.compile(code).execute(scope).inspect(scope)
            assertTrue(result.contains(server.url), result)
            assertTrue(result.contains("secure:ping"), result)
        }
    }

    @Test
    fun testPolicyDenialSurfacesAsLyngError() = runBlocking {
        val scope = Script.newScope()
        val denyAll = object : WsAccessPolicy {
            override suspend fun check(op: WsAccessOp, ctx: AccessContext): AccessDecision =
                AccessDecision(Decision.Deny, "blocked by test policy")
        }
        createWsModule(denyAll, scope)

        val code = """
            import lyng.io.ws
            Ws.connect("ws://127.0.0.1:1/ws")
        """.trimIndent()

        val error = assertFailsWith<ExecutionError> {
            Compiler.compile(code).execute(scope)
        }
        assertTrue(error.errorMessage.isNotBlank())
    }
}
