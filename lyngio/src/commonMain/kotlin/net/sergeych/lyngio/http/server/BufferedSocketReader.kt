package net.sergeych.lyngio.http.server

import net.sergeych.lyngio.net.LyngTcpSocket

internal class BufferedSocketReader(
    private val socket: LyngTcpSocket,
) {
    private var pending = ByteArray(0)

    suspend fun readLine(
        maxBytes: Int,
        overflowStatus: Int,
        overflowMessage: String,
    ): String? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val out = ByteArray(maxBytes)
        var count = 0
        while (true) {
            val next = readByte() ?: return if (count == 0) null else out.copyOf(count).decodeToString()
            if (next == '\n'.code.toByte()) {
                return if (count > 0 && out[count - 1] == '\r'.code.toByte()) {
                    out.copyOf(count - 1).decodeToString()
                } else {
                    out.copyOf(count).decodeToString()
                }
            }
            if (count >= maxBytes) throw HttpProtocolException(overflowStatus, overflowMessage)
            out[count++] = next
        }
    }

    suspend fun readExact(byteCount: Int): ByteArray? {
        require(byteCount >= 0) { "byteCount must be non-negative" }
        if (byteCount == 0) return ByteArray(0)
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

    private suspend fun readByte(): Byte? {
        val bytes = readExact(1) ?: return null
        return bytes[0]
    }
}
