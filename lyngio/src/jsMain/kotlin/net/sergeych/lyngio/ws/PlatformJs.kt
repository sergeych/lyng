package net.sergeych.lyngio.ws

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.ClosedReceiveChannelException

actual fun getSystemWsEngine(): LyngWsEngine = JsKtorWsEngine

private object JsKtorWsEngine : LyngWsEngine {
    private val clientResult by lazy {
        runCatching {
            HttpClient(Js) {
                install(WebSockets)
            }
        }
    }

    override val isSupported: Boolean
        get() = clientResult.isSuccess

    override suspend fun connect(url: String, headers: Map<String, String>): LyngWsSession {
        val client = clientResult.getOrElse {
            throw UnsupportedOperationException(it.message ?: "WebSocket client is not supported")
        }
        val session = client.webSocketSession {
            url(url)
            headers.forEach { (name, value) -> header(name, value) }
        }
        return JsLyngWsSession(url, session)
    }
}

private class JsLyngWsSession(
    private val targetUrl: String,
    private val session: DefaultWebSocketSession,
) : LyngWsSession {
    private var closed = false

    override fun isOpen(): Boolean = !closed

    override fun url(): String = targetUrl

    override suspend fun sendText(text: String) {
        ensureOpen()
        session.send(text)
    }

    override suspend fun sendBytes(data: ByteArray) {
        ensureOpen()
        session.send(data)
    }

    override suspend fun receive(): LyngWsMessage? {
        if (closed) return null
        val frame = try {
            session.incoming.receive()
        } catch (_: ClosedReceiveChannelException) {
            closed = true
            return null
        }
        return when (frame) {
            is Frame.Text -> LyngWsMessage(isText = true, text = frame.readText())
            is Frame.Binary -> LyngWsMessage(isText = false, data = frame.data.copyOf())
            is Frame.Close -> {
                closed = true
                null
            }
            else -> receive()
        }
    }

    override suspend fun close(code: Int, reason: String) {
        if (closed) return
        closed = true
        session.close(CloseReason(code.toShort(), reason))
    }

    private fun ensureOpen() {
        if (closed) throw IllegalStateException("websocket session is closed")
    }
}
