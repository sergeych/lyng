package net.sergeych.lyngio.http.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.sergeych.lyngio.net.LyngNetEngine
import net.sergeych.lyngio.net.LyngSocketAddress
import net.sergeych.lyngio.net.LyngTcpServer
import net.sergeych.lyngio.net.LyngTcpSocket
import net.sergeych.lyngio.net.getSystemNetEngine

internal fun startHttpServer(
    config: HttpServerConfig = HttpServerConfig(),
    netEngine: LyngNetEngine = getSystemNetEngine(),
    handler: HttpHandler,
): HttpServer {
    if (!netEngine.isSupported || !netEngine.isTcpServerAvailable) {
        throw UnsupportedOperationException("HTTP server is not supported on this runtime")
    }
    return StartedHttpServer(config, netEngine, handler)
}

private class StartedHttpServer(
    private val config: HttpServerConfig,
    private val netEngine: LyngNetEngine,
    private val handler: HttpHandler,
) : HttpServer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serverRef: LyngTcpServer? = null
    private var open = true

    init {
        scope.launch {
            val server = netEngine.tcpListen(
                host = config.host,
                port = config.port,
                backlog = config.backlog,
                reuseAddress = config.reuseAddress,
            )
            serverRef = server
            acceptLoop(server)
        }
    }

    override fun isOpen(): Boolean = open && (serverRef?.isOpen() ?: true)

    override fun localAddress(): LyngSocketAddress =
        serverRef?.localAddress() ?: throw IllegalStateException("server is not bound yet")

    override fun close() {
        if (!open) return
        open = false
        serverRef?.close()
        scope.cancel()
    }

    private suspend fun acceptLoop(server: LyngTcpServer) {
        try {
            while (open && server.isOpen()) {
                val socket = try {
                    server.accept()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    if (!open || !server.isOpen()) break
                    continue
                }
                scope.launch {
                    handleConnection(socket)
                }
            }
        } finally {
            open = false
            server.close()
        }
    }

    private suspend fun handleConnection(socket: LyngTcpSocket) {
        val reader = BufferedSocketReader(socket)
        try {
            while (socket.isOpen()) {
                val request = try {
                    withTimeout(config.keepAliveTimeoutMillis) {
                        parseHttpRequest(reader, config)
                    }
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (e: HttpProtocolException) {
                    safeWriteError(socket, e.status, e.message ?: defaultReason(e.status))
                    break
                } catch (_: Throwable) {
                    safeWriteError(socket, 400, defaultReason(400))
                    break
                } ?: break

                val result = try {
                    handler.handle(request)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (_: Throwable) {
                    HttpHandlerResult.Response(HttpResponse(status = 500, close = true))
                }

                when (result) {
                    is HttpHandlerResult.Response -> {
                        val close = request.head.wantsClose || result.response.close
                        writeHttpResponse(socket, result.response, closeConnection = close)
                        if (close) break
                    }
                    is HttpHandlerResult.WebSocket -> {
                        if (!request.head.wantsWebSocketUpgrade) {
                            writeHttpResponse(
                                socket,
                                HttpResponse(status = 400, close = true, body = "WebSocket upgrade required".encodeToByteArray()),
                                closeConnection = true,
                            )
                            break
                        }
                        val session = upgradeToWebSocket(socket, request)
                        try {
                            result.handler(session)
                        } finally {
                            session.close()
                        }
                        break
                    }
                }
            }
        } catch (_: CancellationException) {
        } finally {
            socket.close()
        }
    }

    private suspend fun safeWriteError(socket: LyngTcpSocket, status: Int, message: String) {
        try {
            writeHttpResponse(
                socket,
                HttpResponse(status = status, body = message.encodeToByteArray(), close = true),
                closeConnection = true,
            )
        } catch (_: Throwable) {
        }
    }
}
