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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyngio.net.getSystemNetEngine
import net.sergeych.lyngio.net.security.PermitAllNetAccessPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class LyngNetTcpServerExampleTest {

    private fun concurrentTcpScript(clientCount: Int): String = """
        import lyng.io.net

        val host = "127.0.0.1"
        val clientCount = $clientCount
        val server: TcpServer = Net.tcpListen(0, host, clientCount, true) as TcpServer
        val port: Int = server.localAddress().port

        fun payloadFor(index: Int): String {
            "${'$'}index:${'$'}{Random.nextInt()}:${'$'}{Random.nextInt()}"
        }

        fun handleClient(client: TcpSocket): String {
            try {
                val source = client.readLine()
                if( source == null ) {
                    return "server-eof"
                }
                val reply = "pong: ${'$'}source"
                client.writeUtf8(reply + "\n")
                client.flush()
                reply
            } finally {
                client.close()
            }
        }

        val serverJob: Deferred = launch {
            var handlers: List<Deferred> = List()
            try {
                for( i in 0..<${clientCount} ) {
                    val client: TcpSocket = server.accept() as TcpSocket
                    handlers += launch {
                        handleClient(client)
                    }
                }
                handlers.joinAll()
            } finally {
                server.close()
            }
        }

        val clientJobs: List<Deferred> = (0..<clientCount).map { index ->
            val payload = payloadFor(index)
            launch {
                val socket: TcpSocket = Net.tcpConnect(host, port) as TcpSocket
                try {
                    socket.writeUtf8(payload + "\n")
                    socket.flush()
                    val reply = socket.readLine()
                    if( reply == null ) {
                        "client-eof:${'$'}payload"
                    }
                    else {
                    assertEquals("pong: ${'$'}payload", reply)
                    reply
                    }
                } finally {
                    socket.close()
                }
            }
        }

        val replies = clientJobs.joinAll()
        val serverReplies = serverJob.await() as List<Object>

        assertEquals(clientCount, replies.size)
        assertEquals(clientCount, serverReplies.size)
        assertEquals(replies.toSet, serverReplies.toSet)
        "OK:${'$'}clientCount"
    """.trimIndent()

    @Test
    fun tcpServerExampleSurvivesConcurrentLoopbackLoad() = runBlocking {
        val engine = getSystemNetEngine()
        if (!engine.isSupported || !engine.isTcpAvailable || !engine.isTcpServerAvailable) return@runBlocking

        val scope = Script.newScope()
        createNetModule(PermitAllNetAccessPolicy, scope)

        val result = withContext(Dispatchers.Default) {
            withTimeout(20_000) {
                Compiler.compile(concurrentTcpScript(clientCount = 32)).execute(scope).inspect(scope)
            }
        }

        assertEquals("\"OK:32\"", result)
    }
}
