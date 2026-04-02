package net.sergeych.lyngio.net

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

internal fun createNativeKtorNetEngine(
    isSupported: Boolean,
    isTcpAvailable: Boolean,
    isTcpServerAvailable: Boolean,
    isUdpAvailable: Boolean,
): LyngNetEngine = NativeKtorNetEngine(
    isSupported = isSupported,
    isTcpAvailable = isTcpAvailable,
    isTcpServerAvailable = isTcpServerAvailable,
    isUdpAvailable = isUdpAvailable,
)

private class NativeKtorNetEngine(
    override val isSupported: Boolean,
    override val isTcpAvailable: Boolean,
    override val isTcpServerAvailable: Boolean,
    override val isUdpAvailable: Boolean,
) : LyngNetEngine {
    private val selectorManager: SelectorManager by lazy { SelectorManager(Dispatchers.Default) }

    override suspend fun resolve(host: String, port: Int): List<LyngSocketAddress> {
        val rawAddress = InetSocketAddress(host, port).resolveAddress()
            ?: throw IllegalStateException("Failed to resolve address for $host")
        return listOf(
            LyngSocketAddress(
                host = rawAddress.toIpHostString(),
                port = port,
                ipVersion = rawAddress.toLyngIpVersion(),
                resolved = true,
            )
        )
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
        return NativeLyngTcpSocket(socket)
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
        return NativeLyngTcpServer(server)
    }

    override suspend fun udpBind(host: String?, port: Int, reuseAddress: Boolean): LyngUdpSocket {
        val bindHost = host ?: "0.0.0.0"
        val socket = aSocket(selectorManager).udp().bind(bindHost, port) {
            this.reuseAddress = reuseAddress
        }
        return NativeLyngUdpSocket(socket)
    }
}

private class NativeLyngTcpSocket(
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

private class NativeLyngTcpServer(
    private val server: ServerSocket,
) : LyngTcpServer {
    override fun isOpen(): Boolean = !server.isClosed

    override fun localAddress(): LyngSocketAddress = server.localAddress.toLyngSocketAddress(resolved = true)

    override suspend fun accept(): LyngTcpSocket = NativeLyngTcpSocket(server.accept())

    override fun close() {
        server.close()
    }
}

private class NativeLyngUdpSocket(
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
        socket.send(Datagram(packet, InetSocketAddress(host, port)))
    }

    override fun close() {
        socket.close()
    }
}

private fun SocketAddress.toLyngSocketAddress(resolved: Boolean): LyngSocketAddress {
    val inetAddress = this as? InetSocketAddress
    if (inetAddress != null) {
        val rawAddress = inetAddress.resolveAddress()
        val host = rawAddress?.toIpHostString() ?: inetAddress.hostname
        return LyngSocketAddress(
            host = host,
            port = inetAddress.port,
            ipVersion = rawAddress?.toLyngIpVersion()
                ?: if (host.contains(':')) LyngIpVersion.IPV6 else LyngIpVersion.IPV4,
            resolved = resolved,
        )
    }

    val rendered = toString()
    return LyngSocketAddress(
        host = rendered,
        port = 0,
        ipVersion = if (rendered.contains(':')) LyngIpVersion.IPV6 else LyngIpVersion.IPV4,
        resolved = resolved,
    )
}

private fun ByteArray.toLyngIpVersion(): LyngIpVersion = if (size == 16) LyngIpVersion.IPV6 else LyngIpVersion.IPV4

private fun ByteArray.toIpHostString(): String = when (size) {
    4 -> joinToString(".") { (it.toInt() and 0xff).toString() }
    16 -> (0 until 8).joinToString(":") { index ->
        val hi = this[index * 2].toInt() and 0xff
        val lo = this[index * 2 + 1].toInt() and 0xff
        ((hi shl 8) or lo).toString(16)
    }
    else -> error("Unsupported IP address length: $size")
}
