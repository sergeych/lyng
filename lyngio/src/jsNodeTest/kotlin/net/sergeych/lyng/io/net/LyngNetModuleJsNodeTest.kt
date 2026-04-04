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

package net.sergeych.lyng.io.net

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.promise
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.Script
import net.sergeych.lyngio.fs.security.AccessContext
import net.sergeych.lyngio.fs.security.AccessDecision
import net.sergeych.lyngio.fs.security.Decision
import net.sergeych.lyngio.net.getSystemNetEngine
import net.sergeych.lyngio.net.security.NetAccessOp
import net.sergeych.lyngio.net.security.NetAccessPolicy
import net.sergeych.lyngio.net.security.PermitAllNetAccessPolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(DelicateCoroutinesApi::class)
class LyngNetModuleJsNodeTest {
    @Test
    fun testResolveAndCapabilities() = GlobalScope.promise {
        val scope = Script.newScope()
        createNetModule(PermitAllNetAccessPolicy, scope)

        val code = """
            import lyng.io.net

            val a: SocketAddress = Net.resolve("127.0.0.1", 4040)[0]
            [Net.isSupported(), Net.isTcpAvailable(), Net.isTcpServerAvailable(), Net.isUdpAvailable(), a.toString(), a.resolved, a.ipVersion == IpVersion.IPV4]
        """.trimIndent()

        val result = Compiler.compile(code).execute(scope).inspect(scope)
        assertTrue(result.contains("true,true,true,true"), result)
        assertTrue(result.contains("127.0.0.1:4040"), result)
    }

    @Test
    fun testTcpConnectConvenience() = GlobalScope.promise {
        val engine = getSystemNetEngine()
        val server = engine.tcpListen(host = "127.0.0.1", port = 0, backlog = 8, reuseAddress = true)
        val serverPort = server.localAddress().port
        val worker = async {
            val client = server.accept()
            val line = client.read(4)?.decodeToString()
            client.writeUtf8("reply:$line")
            client.flush()
            client.close()
        }

        val scope = Script.newScope()
        createNetModule(PermitAllNetAccessPolicy, scope)
        val code = """
            import lyng.buffer
            import lyng.io.net

            val socket = Net.tcpConnect("127.0.0.1", $serverPort)
            socket.writeUtf8("ping")
            socket.flush()
            val reply = (socket.read(16) as Buffer).decodeUtf8()
            val localPort = socket.localAddress().port
            val remotePort = socket.remoteAddress().port
            socket.close()
            [reply, localPort > 0, remotePort == $serverPort]
        """.trimIndent()

        val result = Compiler.compile(code).execute(scope).inspect(scope)
        worker.await()
        server.close()
        assertTrue(result.contains("reply:ping"), result)
        assertTrue(result.contains("true,true"), result)
    }

    @Test
    fun testUdpLoopback() = GlobalScope.promise {
        val scope = Script.newScope()
        createNetModule(PermitAllNetAccessPolicy, scope)

        val code = """
            import lyng.buffer
            import lyng.io.net

            val server = Net.udpBind(0, "127.0.0.1")
            val client = Net.udpBind(0, "127.0.0.1")
            client.send(Buffer("ping"), "127.0.0.1", server.localAddress().port)
            val d = server.receive()
            client.close()
            server.close()
            [d.data.decodeUtf8(), d.address.port > 0]
        """.trimIndent()

        val result = Compiler.compile(code).execute(scope).inspect(scope)
        assertTrue(result.contains("[ping,true]"), result)
    }

    @Test
    fun testPolicyDenialSurfacesAsLyngError() = GlobalScope.promise {
        val scope = Script.newScope()
        val denyAll = object : NetAccessPolicy {
            override suspend fun check(op: NetAccessOp, ctx: AccessContext): AccessDecision =
                AccessDecision(Decision.Deny, "blocked by test policy")
        }
        createNetModule(denyAll, scope)

        val code = """
            import lyng.io.net
            Net.tcpConnect("127.0.0.1", 1)
        """.trimIndent()

        val error = assertFailsWith<ExecutionError> {
            Compiler.compile(code).execute(scope)
        }
        assertTrue(error.errorMessage.isNotBlank())
    }
}
