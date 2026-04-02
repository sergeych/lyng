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

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyngio.net.getSystemNetEngine
import net.sergeych.lyngio.net.security.PermitAllNetAccessPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class LyngNetTcpServerExampleTest {

    @Test
    fun tcpServerExampleRoundTripsOverLoopback() = runBlocking {
        val engine = getSystemNetEngine()
        if (!engine.isSupported || !engine.isTcpAvailable || !engine.isTcpServerAvailable) return@runBlocking

        val scope = Script.newScope()
        createNetModule(PermitAllNetAccessPolicy, scope)

        val code = """
            import lyng.buffer
            import lyng.io.net

            val server = Net.tcpListen(0, "127.0.0.1")
            val port = server.localAddress().port
            val accepted = launch {
                val client = server.accept()
                val line = (client.read(4) as Buffer).decodeUtf8()
                client.writeUtf8("echo:" + line)
                client.flush()
                client.close()
                server.close()
                line
            }

            val socket = Net.tcpConnect("127.0.0.1", port)
            socket.writeUtf8("ping")
            socket.flush()
            val reply = (socket.read(16) as Buffer).decodeUtf8()
            socket.close()
            "${'$'}{accepted.await()}: ${'$'}reply"
        """.trimIndent()

        val result = withTimeout(5_000) {
            Compiler.compile(code).execute(scope).inspect(scope)
        }

        assertEquals("\"ping: echo:ping\"", result)
    }
}
