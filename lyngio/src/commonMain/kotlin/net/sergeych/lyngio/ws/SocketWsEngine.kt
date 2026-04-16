package net.sergeych.lyngio.ws

import io.ktor.http.Url
import io.ktor.http.URLProtocol
import net.sergeych.lyngio.net.LyngNetEngine
import net.sergeych.lyngio.net.LyngTcpSocket
import net.sergeych.lyngio.net.getSystemNetEngine
import net.sergeych.mp_tools.encodeToBase64
import kotlin.random.Random

internal fun createSocketWsEngine(
    secureFallback: LyngWsEngine = UnsupportedLyngWsEngine,
): LyngWsEngine = SocketLyngWsEngine(getSystemNetEngine(), secureFallback)

private class SocketLyngWsEngine(
    private val netEngine: LyngNetEngine,
    private val secureFallback: LyngWsEngine,
) : LyngWsEngine {
    override val isSupported: Boolean
        get() = (netEngine.isSupported && netEngine.isTcpAvailable) || secureFallback.isSupported

    override suspend fun connect(url: String, headers: Map<String, String>): LyngWsSession {
        val parsedUrl = Url(url)
        return when (parsedUrl.protocol.name.lowercase()) {
            URLProtocol.WS.name.lowercase() -> connectPlain(parsedUrl, headers)
            URLProtocol.WSS.name.lowercase() -> secureFallback.connect(url, headers)
            else -> throw UnsupportedOperationException("Unsupported websocket scheme: ${parsedUrl.protocol.name}")
        }
    }

    private suspend fun connectPlain(url: Url, headers: Map<String, String>): LyngWsSession {
        if (!netEngine.isSupported || !netEngine.isTcpAvailable) {
            throw UnsupportedOperationException("WebSocket client is not supported on this runtime")
        }
        val socket = netEngine.tcpConnect(url.host, url.port, timeoutMillis = null, noDelay = true)
        try {
            val key = randomBytes(16).encodeToBase64()
            val requestPath = buildRequestPath(url)
            val hostHeader = buildHostHeader(url)
            val request = buildString {
                append("GET ").append(requestPath).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                headers.forEach { (name, value) ->
                    append(name).append(": ").append(value).append("\r\n")
                }
                append("\r\n")
            }
            socket.writeUtf8(request)
            socket.flush()
            validateHandshake(socket, key)
            return SocketLyngWsSession(url.toString(), socket)
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
    }
}

private class SocketLyngWsSession(
    private val targetUrl: String,
    private val socket: LyngTcpSocket,
) : LyngWsSession {
    private var closed = false
    private var closeSent = false
    private var pending = ByteArray(0)
    private var fragmentedOpcode: Int? = null
    private var fragmentedPayload = ByteArray(0)

    override fun isOpen(): Boolean = !closed && socket.isOpen()

    override fun url(): String = targetUrl

    override suspend fun sendText(text: String) {
        ensureOpen()
        sendFrame(OPCODE_TEXT, text.encodeToByteArray())
    }

    override suspend fun sendBytes(data: ByteArray) {
        ensureOpen()
        sendFrame(OPCODE_BINARY, data)
    }

    override suspend fun receive(): LyngWsMessage? {
        if (closed) return null
        while (true) {
            val frame = readFrame() ?: run {
                release()
                return null
            }
            when (frame.opcode) {
                OPCODE_CONTINUATION -> {
                    val opcode = fragmentedOpcode ?: throw IllegalStateException("unexpected websocket continuation frame")
                    fragmentedPayload += frame.payload
                    if (frame.fin) {
                        val payload = fragmentedPayload
                        fragmentedOpcode = null
                        fragmentedPayload = ByteArray(0)
                        return payload.toMessage(opcode)
                    }
                }
                OPCODE_TEXT, OPCODE_BINARY -> {
                    if (frame.fin) return frame.payload.toMessage(frame.opcode)
                    fragmentedOpcode = frame.opcode
                    fragmentedPayload = frame.payload
                }
                OPCODE_CLOSE -> {
                    if (!closeSent) {
                        sendFrame(OPCODE_CLOSE, frame.payload)
                        closeSent = true
                    }
                    release()
                    return null
                }
                OPCODE_PING -> sendFrame(OPCODE_PONG, frame.payload)
                OPCODE_PONG -> Unit
                else -> Unit
            }
        }
    }

    override suspend fun close(code: Int, reason: String) {
        if (closed) return
        val reasonBytes = reason.encodeToByteArray()
        val payload = ByteArray(reasonBytes.size + 2)
        payload[0] = (code shr 8).toByte()
        payload[1] = code.toByte()
        reasonBytes.copyInto(payload, destinationOffset = 2)
        try {
            if (!closeSent) {
                sendFrame(OPCODE_CLOSE, payload)
                closeSent = true
            }
        } finally {
            release()
        }
    }

    private suspend fun sendFrame(opcode: Int, payload: ByteArray) {
        ensureOpen()
        val header = buildFrameHeader(opcode, payload.size, masked = true)
        val mask = randomBytes(4)
        val maskedPayload = payload.copyOf()
        maskedPayload.indices.forEach { index ->
            maskedPayload[index] = (maskedPayload[index].toInt() xor mask[index % mask.size].toInt()).toByte()
        }
        socket.write(header + mask + maskedPayload)
        socket.flush()
    }

    private suspend fun readFrame(): WsFrame? {
        val head = readExact(2) ?: return null
        val fin = (head[0].toInt() and 0x80) != 0
        val opcode = head[0].toInt() and 0x0f
        val masked = (head[1].toInt() and 0x80) != 0
        val payloadLength = when (val lengthCode = head[1].toInt() and 0x7f) {
            126 -> {
                val extended = readExact(2) ?: return null
                ((extended[0].toInt() and 0xff) shl 8) or (extended[1].toInt() and 0xff)
            }
            127 -> {
                val extended = readExact(8) ?: return null
                var acc = 0L
                extended.forEach { byte ->
                    acc = (acc shl 8) or (byte.toInt() and 0xff).toLong()
                }
                require(acc <= Int.MAX_VALUE.toLong()) { "websocket frame is too large" }
                acc.toInt()
            }
            else -> lengthCode
        }
        val mask = if (masked) readExact(4) ?: return null else null
        val payload = if (payloadLength > 0) readExact(payloadLength) ?: return null else ByteArray(0)
        if (mask != null) {
            payload.indices.forEach { index ->
                payload[index] = (payload[index].toInt() xor mask[index % mask.size].toInt()).toByte()
            }
        }
        return WsFrame(fin = fin, opcode = opcode, payload = payload)
    }

    private suspend fun readExact(byteCount: Int): ByteArray? {
        while (pending.size < byteCount) {
            val chunk = socket.read(maxOf(4096, byteCount - pending.size)) ?: break
            if (chunk.isEmpty()) break
            pending += chunk
        }
        if (pending.size < byteCount) return null
        val result = pending.copyOfRange(0, byteCount)
        pending = pending.copyOfRange(byteCount, pending.size)
        return result
    }

    private fun release() {
        if (closed) return
        closed = true
        socket.close()
    }

    private fun ensureOpen() {
        if (closed || !socket.isOpen()) throw IllegalStateException("websocket session is closed")
    }
}

private data class WsFrame(
    val fin: Boolean,
    val opcode: Int,
    val payload: ByteArray,
)

private suspend fun validateHandshake(socket: LyngTcpSocket, key: String) {
    val statusLine = socket.readLine() ?: error("websocket handshake failed: missing response status")
    require(statusLine.startsWith("HTTP/1.1 101") || statusLine.startsWith("HTTP/1.0 101")) {
        "websocket handshake failed: $statusLine"
    }
    val headers = linkedMapOf<String, String>()
    while (true) {
        val line = socket.readLine() ?: error("websocket handshake failed: unexpected EOF")
        if (line.isBlank()) break
        val colonAt = line.indexOf(':')
        require(colonAt > 0) { "invalid websocket header: $line" }
        val name = line.substring(0, colonAt).trim().lowercase()
        val value = line.substring(colonAt + 1).trim()
        headers[name] = value
    }
    require(headers["upgrade"]?.lowercase() == "websocket") { "websocket handshake failed: missing Upgrade header" }
    require(headers["connection"]?.lowercase()?.contains("upgrade") == true) {
        "websocket handshake failed: missing Connection header"
    }
    require(headers["sec-websocket-accept"] == websocketAcceptKey(key)) {
        "websocket handshake failed: invalid Sec-WebSocket-Accept"
    }
}

private fun websocketAcceptKey(key: String): String =
    sha1((key + WS_GUID).encodeToByteArray()).encodeToBase64()

private fun buildRequestPath(url: Url): String {
    val path = url.encodedPath.ifEmpty { "/" }
    val query = url.encodedQuery
    return if (query.isBlank()) path else "$path?$query"
}

private fun buildHostHeader(url: Url): String {
    val host = if (':' in url.host && !url.host.startsWith("[")) "[${url.host}]" else url.host
    return if (url.port == url.protocol.defaultPort) host else "$host:${url.port}"
}

private fun buildFrameHeader(opcode: Int, payloadSize: Int, masked: Boolean): ByteArray {
    require(payloadSize >= 0) { "payload size must be non-negative" }
    val firstByte = (0x80 or (opcode and 0x0f)).toByte()
    val maskBit = if (masked) 0x80 else 0
    return when {
        payloadSize <= 125 -> byteArrayOf(firstByte, (maskBit or payloadSize).toByte())
        payloadSize <= 0xffff -> byteArrayOf(
            firstByte,
            (maskBit or 126).toByte(),
            ((payloadSize ushr 8) and 0xff).toByte(),
            (payloadSize and 0xff).toByte(),
        )
        else -> byteArrayOf(
            firstByte,
            (maskBit or 127).toByte(),
            0,
            0,
            0,
            0,
            ((payloadSize ushr 24) and 0xff).toByte(),
            ((payloadSize ushr 16) and 0xff).toByte(),
            ((payloadSize ushr 8) and 0xff).toByte(),
            (payloadSize and 0xff).toByte(),
        )
    }
}

private fun ByteArray.toMessage(opcode: Int): LyngWsMessage = when (opcode) {
    OPCODE_TEXT -> LyngWsMessage(isText = true, text = decodeToString())
    OPCODE_BINARY -> LyngWsMessage(isText = false, data = copyOf())
    else -> throw IllegalStateException("unsupported websocket opcode: $opcode")
}

private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(Random.Default::nextBytes)

private fun sha1(input: ByteArray): ByteArray {
    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()

    val msgLen = input.size
    val bitLen = msgLen.toLong() * 8L
    val totalLen = ((msgLen + 1 + 8 + 63) / 64) * 64
    val padded = ByteArray(totalLen).also { buf ->
        input.copyInto(buf)
        buf[msgLen] = 0x80.toByte()
        for (i in 0..7) {
            buf[totalLen - 8 + i] = ((bitLen ushr (56 - i * 8)) and 0xff).toByte()
        }
    }

    val words = IntArray(80)
    var blockStart = 0
    while (blockStart < padded.size) {
        for (i in 0..15) {
            val off = blockStart + i * 4
            words[i] = ((padded[off].toInt() and 0xff) shl 24) or
                ((padded[off + 1].toInt() and 0xff) shl 16) or
                ((padded[off + 2].toInt() and 0xff) shl 8) or
                (padded[off + 3].toInt() and 0xff)
        }
        for (i in 16..79) {
            val mixed = words[i - 3] xor words[i - 8] xor words[i - 14] xor words[i - 16]
            words[i] = (mixed shl 1) or (mixed ushr 31)
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4

        for (i in 0..19) {
            val f = (b and c) or (b.inv() and d)
            val temp = ((a shl 5) or (a ushr 27)) + f + e + 0x5A827999 + words[i]
            e = d
            d = c
            c = (b shl 30) or (b ushr 2)
            b = a
            a = temp
        }
        for (i in 20..39) {
            val f = b xor c xor d
            val temp = ((a shl 5) or (a ushr 27)) + f + e + 0x6ED9EBA1 + words[i]
            e = d
            d = c
            c = (b shl 30) or (b ushr 2)
            b = a
            a = temp
        }
        for (i in 40..59) {
            val f = (b and c) or (b and d) or (c and d)
            val temp = ((a shl 5) or (a ushr 27)) + f + e + 0x8F1BBCDC.toInt() + words[i]
            e = d
            d = c
            c = (b shl 30) or (b ushr 2)
            b = a
            a = temp
        }
        for (i in 60..79) {
            val f = b xor c xor d
            val temp = ((a shl 5) or (a ushr 27)) + f + e + 0xCA62C1D6.toInt() + words[i]
            e = d
            d = c
            c = (b shl 30) or (b ushr 2)
            b = a
            a = temp
        }

        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        blockStart += 64
    }

    return ByteArray(20).also { out ->
        fun putInt(offset: Int, value: Int) {
            out[offset] = (value ushr 24).toByte()
            out[offset + 1] = (value ushr 16).toByte()
            out[offset + 2] = (value ushr 8).toByte()
            out[offset + 3] = value.toByte()
        }
        putInt(0, h0)
        putInt(4, h1)
        putInt(8, h2)
        putInt(12, h3)
        putInt(16, h4)
    }
}

private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private const val OPCODE_CONTINUATION = 0x0
private const val OPCODE_TEXT = 0x1
private const val OPCODE_BINARY = 0x2
private const val OPCODE_CLOSE = 0x8
private const val OPCODE_PING = 0x9
private const val OPCODE_PONG = 0xA
