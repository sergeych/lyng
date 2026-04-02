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

package net.sergeych.lyng.io.ws

import net.sergeych.lyng.io.testtls.TlsTestMaterial
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

internal class TestWebSocketServer(
    secure: Boolean = false,
    private val handler: (TestWebSocketConnection) -> Unit,
) : AutoCloseable {
    private val server: ServerSocket = if (secure) {
        TlsTestMaterial.installJvmClientTrust()
        TlsTestMaterial.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as ServerSocket
    } else {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    }
    private val scheme = if (secure) "wss" else "ws"
    private val worker = thread(start = true, name = "ws-test-server") {
        try {
            server.accept().use { socket ->
                val connection = TestWebSocketConnection(socket)
                connection.handshake()
                handler(connection)
            }
        } catch (_: Exception) {
        }
    }

    val url: String = "$scheme://127.0.0.1:${server.localPort}/ws"

    override fun close() {
        server.close()
        worker.join(2000)
    }
}

internal class TestWebSocketConnection(socket: Socket) {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    val requestHeaders = linkedMapOf<String, String>()

    fun handshake() {
        val requestLine = input.readAsciiLine() ?: error("missing websocket request line")
        require(requestLine.startsWith("GET ")) { "unexpected request: $requestLine" }
        while (true) {
            val line = input.readAsciiLine() ?: error("unexpected EOF during websocket handshake")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                requestHeaders[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val key = requestHeaders["sec-websocket-key"] ?: error("missing Sec-WebSocket-Key")
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        )
        output.write(
            (
                "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n" +
                    "\r\n"
                ).toByteArray()
        )
        output.flush()
    }

    fun receiveText(): String = receiveFrame().let { frame ->
        require(frame.opcode == 0x1) { "expected text frame, got opcode=${frame.opcode}" }
        frame.payload.decodeToString()
    }

    fun receiveBinary(): ByteArray = receiveFrame().let { frame ->
        require(frame.opcode == 0x2) { "expected binary frame, got opcode=${frame.opcode}" }
        frame.payload
    }

    fun sendText(text: String) {
        sendFrame(0x1, text.encodeToByteArray())
    }

    fun sendBinary(data: ByteArray) {
        sendFrame(0x2, data)
    }

    fun close(code: Int = 1000, reason: String = "") {
        val reasonBytes = reason.encodeToByteArray()
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = ((code shr 8) and 0xff).toByte()
        payload[1] = (code and 0xff).toByte()
        reasonBytes.copyInto(payload, 2)
        sendFrame(0x8, payload)
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size <= 0xffff -> {
                output.write(126)
                output.write((payload.size ushr 8) and 0xff)
                output.write(payload.size and 0xff)
            }
            else -> error("payload too large for test websocket server")
        }
        output.write(payload)
        output.flush()
    }

    private fun receiveFrame(): IncomingFrame {
        val b0 = input.read()
        require(b0 >= 0) { "unexpected EOF reading websocket frame" }
        val b1 = input.read()
        require(b1 >= 0) { "unexpected EOF reading websocket frame length" }
        val opcode = b0 and 0x0f
        var length = b1 and 0x7f
        if (length == 126) {
            length = (input.read() shl 8) or input.read()
        } else if (length == 127) {
            error("64-bit websocket lengths are not supported in tests")
        }
        val masked = (b1 and 0x80) != 0
        val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(length)
        input.readFully(payload)
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return IncomingFrame(opcode, payload)
    }
}

private data class IncomingFrame(val opcode: Int, val payload: ByteArray)

private fun BufferedInputStream.readAsciiLine(): String? {
    val out = StringBuilder()
    while (true) {
        val b = read()
        if (b < 0) return if (out.isEmpty()) null else out.toString()
        if (b == '\n'.code) {
            if (out.endsWith("\r")) out.setLength(out.length - 1)
            return out.toString()
        }
        out.append(b.toChar())
    }
}

private fun BufferedInputStream.readFully(target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val read = read(target, offset, target.size - offset)
        require(read > 0) { "unexpected EOF" }
        offset += read
    }
}
