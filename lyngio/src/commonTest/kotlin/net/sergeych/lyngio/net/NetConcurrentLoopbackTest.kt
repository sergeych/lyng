package net.sergeych.lyngio.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetConcurrentLoopbackTest {

    @Test
    fun concurrentTcpRoundTripsWorkAtEngineLevel() = runBlocking {
        val engine = getSystemNetEngine()
        if (!engine.isSupported || !engine.isTcpAvailable || !engine.isTcpServerAvailable) return@runBlocking

        withTimeout(10_000) {
            val clients = 32
            val server = engine.tcpListen(host = "127.0.0.1", port = 0, backlog = 64, reuseAddress = true)
            val serverJob = async {
                coroutineScope {
                    val handlers = ArrayList<kotlinx.coroutines.Deferred<String>>(clients)
                    repeat(clients) {
                        val client = server.accept()
                        handlers += async {
                            try {
                                val payload = client.readLine()
                                val reply = "pong:$payload"
                                client.writeUtf8("$reply\n")
                                client.flush()
                                reply
                            } finally {
                                client.close()
                            }
                        }
                    }
                    handlers.awaitAll()
                }
            }

            val clientJobs = coroutineScope {
                (0 until clients).map { index ->
                    async {
                        val payload = "ping:$index"
                        val socket = engine.tcpConnect("127.0.0.1", server.localAddress().port, timeoutMillis = 2_000, noDelay = true)
                        try {
                            socket.writeUtf8("$payload\n")
                            socket.flush()
                            socket.readLine()
                        } finally {
                            socket.close()
                        }
                    }
                }
            }

            val clientReplies = clientJobs.awaitAll()
            val serverReplies = serverJob.await()
            server.close()

            assertEquals((0 until clients).map { "pong:ping:$it" }, clientReplies)
            assertEquals((0 until clients).map { "pong:ping:$it" }, serverReplies)
            assertTrue(clientReplies.all { it != null })
        }
    }
}
