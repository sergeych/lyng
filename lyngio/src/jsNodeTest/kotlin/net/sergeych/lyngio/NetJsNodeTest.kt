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

package net.sergeych.lyngio

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.promise
import net.sergeych.lyngio.net.LyngIpVersion
import net.sergeych.lyngio.net.getSystemNetEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(DelicateCoroutinesApi::class)
class NetJsNodeTest {
    @Test
    fun testNodeNetCapabilitiesAndResolve() = GlobalScope.promise {
        val engine = getSystemNetEngine()
        assertTrue(engine.isSupported)
        assertTrue(engine.isTcpAvailable)
        assertTrue(engine.isTcpServerAvailable)
        assertTrue(engine.isUdpAvailable)

        val resolved = engine.resolve("127.0.0.1", 4040)
        assertTrue(resolved.isNotEmpty())
        assertEquals(4040, resolved.first().port)
        assertEquals(LyngIpVersion.IPV4, resolved.first().ipVersion)
    }

    @Test
    fun testNodeTcpLoopback() = GlobalScope.promise {
        val engine = getSystemNetEngine()
        val server = engine.tcpListen(host = "127.0.0.1", port = 0, backlog = 8, reuseAddress = true)
        val accepted = async {
            val socket = server.accept()
            val line = socket.readLine()
            socket.writeUtf8("echo:$line\n")
            socket.flush()
            socket.close()
            line
        }

        val client = engine.tcpConnect("127.0.0.1", server.localAddress().port, timeoutMillis = null, noDelay = true)
        client.writeUtf8("ping\n")
        client.flush()
        val reply = client.readLine()
        client.close()
        server.close()

        assertEquals("ping", accepted.await())
        assertEquals("echo:ping", reply)
    }

    @Test
    fun testNodeUdpLoopback() = GlobalScope.promise {
        val engine = getSystemNetEngine()
        val server = engine.udpBind(host = "127.0.0.1", port = 0, reuseAddress = true)
        val client = engine.udpBind(host = "127.0.0.1", port = 0, reuseAddress = true)

        client.send("ping".encodeToByteArray(), "127.0.0.1", server.localAddress().port)
        val received = server.receive(1024)

        client.close()
        server.close()

        assertNotNull(received)
        assertEquals("ping", received.data.decodeToString())
        assertTrue(received.address.port > 0)
    }
}
