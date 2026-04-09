package net.sergeych

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.ObjString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTcpServerRegressionTest {

    @Test
    fun reducedTcpServerExampleRunsWithCopiedCliImportManager() = runBlocking {
        val cliScope = newCliScope(emptyList())
        val session = EvalSession(cliScope)

        try {
            val result = evalOnCliDispatcher(
                session,
                Source(
                    "<tcp-server-regression>",
                    """
                    import lyng.buffer
                    import lyng.io.net

                    val host = "127.0.0.1"
                    val server = Net.tcpListen(0, host)
                    val port = server.localAddress().port
                    val accepted = launch {
                        val client = server.accept()
                        val line = (client.read(4) as Buffer).decodeUtf8()
                        client.close()
                        server.close()
                        line
                    }

                    val socket = Net.tcpConnect(host, port)
                    socket.writeUtf8("ping")
                    socket.flush()
                    socket.close()
                    accepted.await()
                    """.trimIndent()
                )
            )

            assertEquals("ping", (result as ObjString).value)
        } finally {
            session.cancelAndJoin()
        }
    }

    @Test
    fun concurrentTcpExampleRunsInCliScope() = runBlocking {
        val cliScope = newCliScope(emptyList())
        val session = EvalSession(cliScope)

        try {
            val result = evalOnCliDispatcher(
                session,
                Source(
                    "<tcp-server-concurrency-cli>",
                    """
                    import lyng.io.net

                    val host = "127.0.0.1"
                    val clientCount = 32
                    val server: TcpServer = Net.tcpListen(0, host, 32, true) as TcpServer
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
                            for( i in 0..<32 ) {
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

                    val clientJobs = (0..<clientCount).map { index ->
                        val payload = payloadFor(index)
                        launch {
                            val socket: TcpSocket = Net.tcpConnect(host, port) as TcpSocket
                            try {
                                socket.writeUtf8(payload + "\n")
                                socket.flush()
                                val reply = socket.readLine()
                                if( reply == null ) {
                                    "client-eof:${'$'}payload"
                                } else {
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
                    "OK:${'$'}clientCount:${'$'}{replies.toSet}:${'$'}{serverReplies.toSet}"
                    """.trimIndent()
                )
            )

            val text = (result as ObjString).value
            assertTrue(text.startsWith("OK:32:"), text)
        } finally {
            session.cancelAndJoin()
        }
    }

    @Test
    fun mixedModuleAndLocalCapturesWorkInCliScope() = runBlocking {
        val cliScope = newCliScope(emptyList())
        val session = EvalSession(cliScope)

        try {
            val result = evalOnCliDispatcher(
                session,
                Source(
                    "<cli-capture-regression>",
                    """
                    val prefix = "pong"
                    val jobs = (0..<32).map { index ->
                        val payload = "${'$'}index:${'$'}{Random.nextInt()}"
                        launch {
                            delay(5)
                            "${'$'}prefix:${'$'}payload"
                        }
                    }
                    jobs.joinAll()
                    """.trimIndent()
                )
            ) as net.sergeych.lyng.obj.ObjList

            assertEquals(32, result.list.size)
            assertEquals(32, result.list.map { (it as ObjString).value }.toSet().size)
        } finally {
            session.cancelAndJoin()
        }
    }
}
