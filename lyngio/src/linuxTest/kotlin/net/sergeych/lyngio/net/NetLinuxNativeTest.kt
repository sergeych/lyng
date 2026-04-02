package net.sergeych.lyngio.net

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyng.io.net.createNetModule
import net.sergeych.lyngio.net.security.PermitAllNetAccessPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetLinuxNativeTest {

    @Test
    fun testLinuxNativeCapabilitiesAndResolve() = runBlocking {
        val engine = getSystemNetEngine()

        assertTrue(engine.isSupported)
        assertTrue(engine.isTcpAvailable)
        assertTrue(engine.isTcpServerAvailable)
        assertTrue(engine.isUdpAvailable)

        val resolved = engine.resolve("127.0.0.1", 4040)
        assertEquals(1, resolved.size)
        assertEquals("127.0.0.1", resolved.single().host)
        assertEquals(4040, resolved.single().port)
        assertEquals(LyngIpVersion.IPV4, resolved.single().ipVersion)
        assertTrue(resolved.single().resolved)
    }

    @Test
    fun testLinuxNativeLyngModuleCapabilities() = runBlocking {
        val scope = Script.newScope()
        createNetModule(PermitAllNetAccessPolicy, scope)

        val code = """
            import lyng.io.net

            val a: SocketAddress = Net.resolve("127.0.0.1", 4040)[0]
            [Net.isSupported(), Net.isTcpAvailable(), Net.isTcpServerAvailable(), Net.isUdpAvailable(), a.toString(), a.resolved]
        """.trimIndent()

        val result = Compiler.compile(code).execute(scope).inspect(scope)
        assertTrue(result.contains("true,true,true,true"), result)
        assertTrue(result.contains("127.0.0.1:4040"), result)
    }

    @Test
    fun testLinuxNativeTcpAndUdpLoopback() = runBlocking {
        val engine = getSystemNetEngine()

        withTimeout(5_000) {
            val server = engine.tcpListen(host = "127.0.0.1", port = 0, backlog = 16, reuseAddress = true)
            val accepted = async {
                val client = server.accept()
                val text = client.read(4)?.decodeToString()
                client.writeUtf8("echo:$text")
                client.flush()
                client.close()
                server.close()
                text
            }

            val socket = engine.tcpConnect("127.0.0.1", server.localAddress().port, timeoutMillis = 2_000, noDelay = true)
            socket.writeUtf8("ping")
            socket.flush()
            val reply = socket.read(32)?.decodeToString()
            socket.close()

            assertEquals("ping", accepted.await())
            assertEquals("echo:ping", reply)
        }

        withTimeout(5_000) {
            val receiver = engine.udpBind(host = "127.0.0.1", port = 0, reuseAddress = true)
            val sender = engine.udpBind(host = "127.0.0.1", port = 0, reuseAddress = true)

            sender.send("ping".encodeToByteArray(), "127.0.0.1", receiver.localAddress().port)
            val datagram = receiver.receive(32)

            sender.close()
            receiver.close()

            assertEquals("ping", datagram?.data?.decodeToString())
            assertTrue((datagram?.address?.port ?: 0) > 0)
        }
    }
}
