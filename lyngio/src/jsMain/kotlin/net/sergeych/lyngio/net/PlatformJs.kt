@file:Suppress("UnsafeCastFromDynamic", "SpellCheckingInspection")

package net.sergeych.lyngio.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.json
import org.khronos.webgl.Uint8Array

actual fun getSystemNetEngine(): LyngNetEngine = jsNodeNetEngineOrNull ?: UnsupportedLyngNetEngine

actual fun shutdownSystemNetEngine() {}

private val jsNodeNetEngineOrNull: LyngNetEngine? by lazy {
    if (!isNodeRuntime()) return@lazy null
    val net = requireNodeModule("net") ?: return@lazy null
    val dgram = requireNodeModule("dgram") ?: return@lazy null
    val dns = requireNodeModule("dns") ?: return@lazy null
    JsNodeNetEngine(net, dgram, dns)
}

private class JsNodeNetEngine(
    private val netModule: dynamic,
    private val dgramModule: dynamic,
    private val dnsModule: dynamic,
) : LyngNetEngine {
    override val isSupported: Boolean = true
    override val isTcpAvailable: Boolean = true
    override val isTcpServerAvailable: Boolean = true
    override val isUdpAvailable: Boolean = true

    override suspend fun resolve(host: String, port: Int): List<LyngSocketAddress> {
        val family = netModule.isIP(host) as Int
        if (family == 4 || family == 6) {
            return listOf(
                LyngSocketAddress(
                    host = host,
                    port = port,
                    ipVersion = if (family == 6) LyngIpVersion.IPV6 else LyngIpVersion.IPV4,
                    resolved = true,
                )
            )
        }
        return suspendCancellableCoroutine { cont ->
            dnsModule.lookup(host, json("all" to true), { error: dynamic, result: dynamic ->
                if (!cont.isActive) return@lookup
                if (error != null) {
                    cont.resumeWithException(IllegalStateException(error.message?.unsafeCast<String>() ?: "DNS lookup failed"))
                    return@lookup
                }
                val addresses = mutableListOf<LyngSocketAddress>()
                val items = result.unsafeCast<Array<dynamic>>()
                for (item in items) {
                    val address = item.address?.unsafeCast<String>() ?: continue
                    val itemFamily = item.family?.unsafeCast<Int>() ?: if (address.contains(':')) 6 else 4
                    addresses += LyngSocketAddress(
                        host = address,
                        port = port,
                        ipVersion = if (itemFamily == 6) LyngIpVersion.IPV6 else LyngIpVersion.IPV4,
                        resolved = true,
                    )
                }
                cont.resume(addresses)
            })
        }
    }

    override suspend fun tcpConnect(
        host: String,
        port: Int,
        timeoutMillis: Long?,
        noDelay: Boolean,
    ): LyngTcpSocket {
        var socket: dynamic = null
        return try {
            val connected = suspend {
                suspendCancellableCoroutine<dynamic> { cont ->
                    socket = netModule.createConnection(json("host" to host, "port" to port)) {
                        if (cont.isActive) cont.resume(socket)
                    }
                    socket.once("error", { error: dynamic ->
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException(error.message?.unsafeCast<String>() ?: "TCP connect failed")
                            )
                        }
                    })
                }
            }
            val connectedSocket = if (timeoutMillis != null) withTimeout(timeoutMillis) { connected() } else connected()
            connectedSocket.setNoDelay(noDelay)
            JsNodeTcpSocket(connectedSocket)
        } catch (e: Throwable) {
            if (socket != null) socket.destroy()
            throw e
        }
    }

    override suspend fun tcpListen(
        host: String?,
        port: Int,
        backlog: Int,
        reuseAddress: Boolean,
    ): LyngTcpServer {
        val accepted = Channel<LyngTcpSocket>(Channel.UNLIMITED)
        val server = netModule.createServer({ socket: dynamic ->
            accepted.trySend(JsNodeTcpSocket(socket))
        })
        server.on("error", { _: dynamic -> })
        val listenHost = host ?: "0.0.0.0"
        val options = json(
            "host" to listenHost,
            "port" to port,
            "backlog" to backlog,
            "exclusive" to !reuseAddress,
        )
        suspendCancellableCoroutine<Unit> { cont ->
            server.once("error", { error: dynamic ->
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException(error.message?.unsafeCast<String>() ?: "TCP listen failed"))
                }
            })
            server.listen(options) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
        return JsNodeTcpServer(server, accepted)
    }

    override suspend fun udpBind(host: String?, port: Int, reuseAddress: Boolean): LyngUdpSocket {
        val socketType = if ((host ?: "").contains(':')) "udp6" else "udp4"
        val socket = dgramModule.createSocket(json("type" to socketType, "reuseAddr" to reuseAddress))
        val incoming = Channel<LyngDatagram>(Channel.UNLIMITED)
        socket.on("message", { msg: dynamic, rinfo: dynamic ->
            incoming.trySend(
                LyngDatagram(
                    data = dynamicToByteArray(msg),
                    address = rinfoToAddress(rinfo),
                )
            )
        })
        socket.on("error", { _: dynamic -> })
        suspendCancellableCoroutine<Unit> { cont ->
            socket.once("error", { error: dynamic ->
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException(error.message?.unsafeCast<String>() ?: "UDP bind failed"))
                }
            })
            socket.bind(port, host ?: "0.0.0.0") {
                if (cont.isActive) cont.resume(Unit)
            }
        }
        return JsNodeUdpSocket(socket, incoming)
    }
}

private class JsNodeTcpSocket(
    private val socket: dynamic,
) : LyngTcpSocket {
    private val incoming = Channel<ByteArray?>(Channel.UNLIMITED)
    private val buffered = ArrayDeque<Byte>()
    private var closed = false
    private var failure: Throwable? = null

    init {
        socket.on("data", { chunk: dynamic ->
            incoming.trySend(dynamicToByteArray(chunk))
        })
        socket.on("end", {
            closed = true
            incoming.trySend(null)
        })
        socket.on("close", {
            closed = true
            incoming.trySend(null)
        })
        socket.on("error", { error: dynamic ->
            failure = IllegalStateException(error.message?.unsafeCast<String>() ?: "TCP socket failed")
            closed = true
            incoming.trySend(null)
        })
    }

    override fun isOpen(): Boolean = !closed && socket.destroyed != true

    override fun localAddress(): LyngSocketAddress = socketAddress(
        host = socket.localAddress?.unsafeCast<String>() ?: "0.0.0.0",
        port = socket.localPort?.unsafeCast<Int>() ?: 0,
        family = socket.localFamily,
        resolved = true,
    )

    override fun remoteAddress(): LyngSocketAddress = socketAddress(
        host = socket.remoteAddress?.unsafeCast<String>() ?: "0.0.0.0",
        port = socket.remotePort?.unsafeCast<Int>() ?: 0,
        family = socket.remoteFamily,
        resolved = true,
    )

    override suspend fun read(maxBytes: Int): ByteArray? {
        if (!ensureBuffered()) return null
        val count = minOf(maxBytes, buffered.size)
        return ByteArray(count) { buffered.removeFirst() }
    }

    override suspend fun readLine(): String? {
        while (true) {
            val newlineIndex = buffered.indexOfFirst { it == '\n'.code.toByte() }
            if (newlineIndex >= 0) {
                val raw = takeBuffered(newlineIndex + 1)
                val trimmed = if (raw.lastOrNull() == '\n'.code.toByte()) raw.dropLast(1) else raw
                val withoutCr = if (trimmed.lastOrNull() == '\r'.code.toByte()) trimmed.dropLast(1) else trimmed
                return withoutCr.toByteArray().decodeToString()
            }
            if (!fillBuffer()) break
        }
        if (buffered.isEmpty()) {
            failure?.let { throw it }
            return null
        }
        return takeBuffered(buffered.size).toByteArray().decodeToString()
    }

    override suspend fun write(data: ByteArray) {
        ensureOpen()
        suspendCancellableCoroutine<Unit> { cont ->
            socket.write(byteArrayToUint8Array(data), { error: dynamic ->
                if (!cont.isActive) return@write
                if (error != null) {
                    cont.resumeWithException(IllegalStateException(error.message?.unsafeCast<String>() ?: "TCP write failed"))
                } else {
                    cont.resume(Unit)
                }
            })
        }
    }

    override suspend fun writeUtf8(text: String) {
        ensureOpen()
        suspendCancellableCoroutine<Unit> { cont ->
            socket.write(text, "utf8", { error: dynamic ->
                if (!cont.isActive) return@write
                if (error != null) {
                    cont.resumeWithException(IllegalStateException(error.message?.unsafeCast<String>() ?: "TCP write failed"))
                } else {
                    cont.resume(Unit)
                }
            })
        }
    }

    override suspend fun flush() {
        ensureOpen()
        if (socket.writableNeedDrain == true) {
            withTimeoutOrNull(5_000) {
                awaitNodeEvent(socket, "drain")
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (socket.destroyed == true) {
            incoming.trySend(null)
            return
        }
        if (socket.writable == true) socket.end() else socket.destroy()
    }

    private suspend fun ensureBuffered(): Boolean {
        if (buffered.isNotEmpty()) return true
        return fillBuffer()
    }

    private suspend fun fillBuffer(): Boolean {
        while (buffered.isEmpty()) {
            val chunk = incoming.receive()
            if (chunk == null) {
                failure?.let { if (buffered.isEmpty()) throw it }
                return buffered.isNotEmpty()
            }
            chunk.forEach { buffered.addLast(it) }
        }
        return true
    }

    private fun takeBuffered(count: Int): List<Byte> = List(count) { buffered.removeFirst() }

    private fun ensureOpen() {
        if (!isOpen()) throw IllegalStateException("tcp socket is closed")
    }
}

private class JsNodeTcpServer(
    private val server: dynamic,
    private val accepted: Channel<LyngTcpSocket>,
) : LyngTcpServer {
    private var closed = false

    override fun isOpen(): Boolean = !closed && server.listening == true

    override fun localAddress(): LyngSocketAddress {
        val info = server.address()
        return socketAddress(
            host = info.address?.unsafeCast<String>() ?: "0.0.0.0",
            port = info.port?.unsafeCast<Int>() ?: 0,
            family = info.family,
            resolved = true,
        )
    }

    override suspend fun accept(): LyngTcpSocket = accepted.receive()

    override fun close() {
        if (closed) return
        closed = true
        server.close()
        accepted.close()
    }
}

private class JsNodeUdpSocket(
    private val socket: dynamic,
    private val incoming: Channel<LyngDatagram>,
) : LyngUdpSocket {
    private var closed = false

    override fun isOpen(): Boolean = !closed

    override fun localAddress(): LyngSocketAddress = rinfoToAddress(socket.address())

    override suspend fun receive(maxBytes: Int): LyngDatagram? {
        val datagram = incoming.receiveCatching().getOrNull() ?: return null
        return if (datagram.data.size <= maxBytes) datagram else datagram.copy(data = datagram.data.copyOf(maxBytes))
    }

    override suspend fun send(data: ByteArray, host: String, port: Int) {
        if (closed) throw IllegalStateException("udp socket is closed")
        suspendCancellableCoroutine<Unit> { cont ->
            socket.send(byteArrayToUint8Array(data), port, host, { error: dynamic ->
                if (!cont.isActive) return@send
                if (error != null) {
                    cont.resumeWithException(IllegalStateException(error.message?.unsafeCast<String>() ?: "UDP send failed"))
                } else {
                    cont.resume(Unit)
                }
            })
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        socket.close()
        incoming.close()
    }
}

private suspend fun awaitNodeEvent(target: dynamic, name: String) {
    suspendCancellableCoroutine<Unit> { cont ->
        target.once("error", { error: dynamic ->
            if (cont.isActive) {
                cont.resumeWithException(IllegalStateException(error.message?.unsafeCast<String>() ?: "Node operation failed"))
            }
        })
        target.once(name) {
            if (cont.isActive) cont.resume(Unit)
        }
    }
}

private fun socketAddress(host: String, port: Int, family: dynamic, resolved: Boolean): LyngSocketAddress =
    LyngSocketAddress(
        host = host,
        port = port,
        ipVersion = when (family?.toString()) {
            "IPv6", "6" -> LyngIpVersion.IPV6
            else -> if (host.contains(':')) LyngIpVersion.IPV6 else LyngIpVersion.IPV4
        },
        resolved = resolved,
    )

private fun rinfoToAddress(rinfo: dynamic): LyngSocketAddress = socketAddress(
    host = rinfo.address?.unsafeCast<String>() ?: "0.0.0.0",
    port = rinfo.port?.unsafeCast<Int>() ?: 0,
    family = rinfo.family,
    resolved = true,
)

private fun isNodeRuntime(): Boolean = js(
    """
    typeof process !== "undefined" &&
    process != null &&
    process.versions != null &&
    process.versions.node != null
    """
).unsafeCast<Boolean>()

private fun requireNodeModule(name: String): dynamic {
    val requireFn = js("typeof require !== 'undefined' ? require : undefined")
    if (requireFn == js("undefined")) return null
    return try {
        requireFn(name)
    } catch (_: Throwable) {
        null
    }
}

private fun dynamicToByteArray(value: dynamic): ByteArray {
    val source = js("new Uint8Array(value)").unsafeCast<Uint8Array>()
    val size = source.length
    return ByteArray(size) { index -> source.asDynamic()[index].unsafeCast<Byte>() }
}

private fun byteArrayToUint8Array(value: ByteArray): Uint8Array {
    val out = Uint8Array(value.size)
    value.forEachIndexed { index, byte -> out.asDynamic()[index] = byte.toInt() and 0xff }
    return out
}
