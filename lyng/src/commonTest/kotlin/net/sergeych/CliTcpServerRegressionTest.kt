package net.sergeych

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.ObjString
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
