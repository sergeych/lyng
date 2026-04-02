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

package net.sergeych.lyngio.net

import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.toJavaAddress
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

actual fun getSystemNetEngine(): LyngNetEngine = JvmKtorNetEngine

private object JvmKtorNetEngine : LyngNetEngine {
    private val selectorManager: SelectorManager by lazy { ActorSelectorManager(Dispatchers.IO) }

    override val isSupported: Boolean = true
    override val isTcpAvailable: Boolean = true
    override val isTcpServerAvailable: Boolean = true
    override val isUdpAvailable: Boolean = true

    override suspend fun resolve(host: String, port: Int): List<LyngSocketAddress> = withContext(Dispatchers.IO) {
        InetAddress.getAllByName(host).map { address ->
            address.toLyngSocketAddress(port = port, resolved = true)
        }
    }

    override suspend fun tcpConnect(
        host: String,
        port: Int,
        timeoutMillis: Long?,
        noDelay: Boolean,
    ): LyngTcpSocket {
        val connectBlock: suspend () -> Socket = {
            aSocket(selectorManager).tcp().connect(host, port) {
                this.noDelay = noDelay
            }
        }
        val socket = if (timeoutMillis != null) withTimeout(timeoutMillis) { connectBlock() } else connectBlock()
        return JvmLyngTcpSocket(socket)
    }

    override suspend fun tcpListen(
        host: String?,
        port: Int,
        backlog: Int,
        reuseAddress: Boolean,
    ): LyngTcpServer {
        val bindHost = host ?: "0.0.0.0"
        val server = aSocket(selectorManager).tcp().bind(bindHost, port) {
            backlogSize = backlog
            this.reuseAddress = reuseAddress
        }
        return JvmLyngTcpServer(server)
    }

    override suspend fun udpBind(host: String?, port: Int, reuseAddress: Boolean): LyngUdpSocket {
        val bindHost = host ?: "0.0.0.0"
        val socket = aSocket(selectorManager).udp().bind(bindHost, port) {
            this.reuseAddress = reuseAddress
        }
        return JvmLyngUdpSocket(socket)
    }
}

private class JvmLyngTcpSocket(
    private val socket: Socket,
) : LyngTcpSocket {
    private val input: ByteReadChannel by lazy { socket.openReadChannel() }
    private val output: ByteWriteChannel by lazy { socket.openWriteChannel(autoFlush = true) }

    override fun isOpen(): Boolean = !socket.isClosed

    override fun localAddress(): LyngSocketAddress = socket.localAddress.toLyngSocketAddress(resolved = true)

    override fun remoteAddress(): LyngSocketAddress = socket.remoteAddress.toLyngSocketAddress(resolved = true)

    override suspend fun read(maxBytes: Int): ByteArray? {
        if (!input.awaitContent(1)) return null
        val buffer = ByteArray(maxBytes)
        val count = input.readAvailable(buffer, 0, maxBytes)
        return when {
            count <= 0 -> null
            count == maxBytes -> buffer
            else -> buffer.copyOf(count)
        }
    }

    override suspend fun readLine(): String? = input.readUTF8Line()

    override suspend fun write(data: ByteArray) {
        output.writeFully(data, 0, data.size)
    }

    override suspend fun writeUtf8(text: String) {
        output.writeStringUtf8(text)
    }

    override suspend fun flush() {
        output.flush()
    }

    override fun close() {
        socket.close()
    }
}

private class JvmLyngTcpServer(
    private val server: ServerSocket,
) : LyngTcpServer {
    override fun isOpen(): Boolean = !server.isClosed

    override fun localAddress(): LyngSocketAddress = server.localAddress.toLyngSocketAddress(resolved = true)

    override suspend fun accept(): LyngTcpSocket = JvmLyngTcpSocket(server.accept())

    override fun close() {
        server.close()
    }
}

private class JvmLyngUdpSocket(
    private val socket: BoundDatagramSocket,
) : LyngUdpSocket {
    override fun isOpen(): Boolean = !socket.isClosed

    override fun localAddress(): LyngSocketAddress = socket.localAddress.toLyngSocketAddress(resolved = true)

    override suspend fun receive(maxBytes: Int): LyngDatagram? {
        val datagram = try {
            socket.receive()
        } catch (e: Throwable) {
            if (!isOpen()) return null
            throw e
        }
        val bytes = datagram.packet.readByteArray().let {
            if (it.size <= maxBytes) it else it.copyOf(maxBytes)
        }
        return LyngDatagram(bytes, datagram.address.toLyngSocketAddress(resolved = true))
    }

    override suspend fun send(data: ByteArray, host: String, port: Int) {
        val packet = Buffer()
        packet.write(data)
        socket.send(io.ktor.network.sockets.Datagram(packet, InetSocketAddress(host, port)))
    }

    override fun close() {
        socket.close()
    }
}

private fun io.ktor.network.sockets.SocketAddress.toLyngSocketAddress(
    port: Int? = null,
    resolved: Boolean,
): LyngSocketAddress {
    val javaAddress = this.toJavaAddress()
    val inetSocket = javaAddress as? java.net.InetSocketAddress
    if (inetSocket != null) {
        val inetAddress = inetSocket.address
        val host = inetAddress?.hostAddress ?: inetSocket.hostString
        val actualPort = port ?: inetSocket.port
        val version = when (inetAddress) {
            is Inet6Address -> LyngIpVersion.IPV6
            is Inet4Address -> LyngIpVersion.IPV4
            else -> if (host.contains(':')) LyngIpVersion.IPV6 else LyngIpVersion.IPV4
        }
        return LyngSocketAddress(host = host, port = actualPort, ipVersion = version, resolved = resolved)
    }

    val rendered = toString()
    return LyngSocketAddress(
        host = rendered,
        port = port ?: 0,
        ipVersion = if (rendered.contains(':')) LyngIpVersion.IPV6 else LyngIpVersion.IPV4,
        resolved = resolved,
    )
}

private fun InetAddress.toLyngSocketAddress(port: Int, resolved: Boolean): LyngSocketAddress =
    LyngSocketAddress(
        host = hostAddress,
        port = port,
        ipVersion = when (this) {
            is Inet6Address -> LyngIpVersion.IPV6
            else -> LyngIpVersion.IPV4
        },
        resolved = resolved,
    )
