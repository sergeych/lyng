package net.sergeych.lyngio.ws

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
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

internal fun createKtorWsEngine(
    engineFactory: HttpClientEngineFactory<HttpClientEngineConfig>,
    shareClient: Boolean = true,
): LyngWsEngine = KtorLyngWsEngine(engineFactory, shareClient)

private class KtorLyngWsEngine(
    engineFactory: HttpClientEngineFactory<HttpClientEngineConfig>,
    private val shareClient: Boolean,
) : LyngWsEngine {
    private val clientFactory: () -> HttpClient = {
        HttpClient(engineFactory) {
            install(WebSockets)
        }
    }
    private val sharedClientResult = if (shareClient) runCatching { clientFactory() } else null
    private val supportProbe = if (!shareClient) runCatching { clientFactory().close() } else null

    override val isSupported: Boolean
        get() = sharedClientResult?.isSuccess ?: supportProbe?.isSuccess ?: true

    override suspend fun connect(url: String, headers: Map<String, String>): LyngWsSession {
        val client = (sharedClientResult?.getOrElse {
            throw UnsupportedOperationException(it.message ?: "WebSocket client is not supported")
        } ?: runCatching { clientFactory() }.getOrElse {
            throw UnsupportedOperationException(it.message ?: "WebSocket client is not supported")
        })
        val session = client.webSocketSession {
            url(url)
            headers.forEach { (name, value) -> header(name, value) }
        }
        return KtorLyngWsSession(
            targetUrl = url,
            session = session,
            ownedClient = client.takeUnless { shareClient },
        )
    }
}

private class KtorLyngWsSession(
    private val targetUrl: String,
    private val session: DefaultWebSocketSession,
    private val ownedClient: HttpClient? = null,
) : LyngWsSession {
    private var closed = false

    override fun isOpen(): Boolean = !closed

    override fun url(): String = targetUrl

    override suspend fun sendText(text: String) {
        ensureOpen()
        try {
            session.send(text)
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    override suspend fun sendBytes(data: ByteArray) {
        ensureOpen()
        try {
            session.send(data)
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    override suspend fun receive(): LyngWsMessage? {
        if (closed) return null
        val frame = try {
            session.incoming.receive()
        } catch (_: ClosedReceiveChannelException) {
            release()
            return null
        } catch (e: Throwable) {
            release()
            throw e
        }
        return when (frame) {
            is Frame.Text -> LyngWsMessage(isText = true, text = frame.readText())
            is Frame.Binary -> LyngWsMessage(isText = false, data = frame.data.copyOf())
            is Frame.Close -> {
                release()
                null
            }
            else -> receive()
        }
    }

    override suspend fun close(code: Int, reason: String) {
        if (closed) return
        try {
            session.close(CloseReason(code.toShort(), reason))
        } finally {
            release()
        }
    }

    private fun ensureOpen() {
        if (closed) throw IllegalStateException("websocket session is closed")
    }

    private fun release() {
        if (closed) return
        closed = true
        ownedClient?.close()
    }
}
