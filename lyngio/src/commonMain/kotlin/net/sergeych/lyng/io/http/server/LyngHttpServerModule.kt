package net.sergeych.lyng.io.http.server

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Source
import net.sergeych.lyng.Arguments
import net.sergeych.lyng.TypeDecl
import net.sergeych.lyng.asFacade
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjExternCallable
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjList
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjProperty
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.obj.thisAs
import net.sergeych.lyng.io.http.ObjHttpHeaders
import net.sergeych.lyng.io.http.createHttpTypesModule
import net.sergeych.lyng.io.ws.ObjWsMessage
import net.sergeych.lyng.io.ws.createWsTypesModule
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.raiseIllegalOperation
import net.sergeych.lyng.requireNoArgs
import net.sergeych.lyng.requireScope
import net.sergeych.lyngio.http.server.HttpHandlerResult
import net.sergeych.lyngio.http.server.HttpHeader
import net.sergeych.lyngio.http.server.HttpRequest
import net.sergeych.lyngio.http.server.HttpResponse
import net.sergeych.lyngio.http.server.HttpServerConfig
import net.sergeych.lyngio.http.server.HttpWebSocketSession
import net.sergeych.lyngio.http.server.defaultReason
import net.sergeych.lyngio.http.server.startHttpServer
import net.sergeych.lyngio.net.security.NetAccessDeniedException
import net.sergeych.lyngio.net.security.NetAccessOp
import net.sergeych.lyngio.net.security.NetAccessPolicy
import net.sergeych.lyngio.stdlib_included.http_serverLyng
import net.sergeych.lyng.bytecode.BytecodeLambdaCallable

private const val HTTP_SERVER_MODULE_NAME = "lyng.io.http.server"

fun createHttpServerModule(policy: NetAccessPolicy, scope: Scope): Boolean =
    createHttpServerModule(policy, scope.importManager)

fun createHttpServer(policy: NetAccessPolicy, scope: Scope): Boolean = createHttpServerModule(policy, scope)

fun createHttpServerModule(policy: NetAccessPolicy, manager: ImportManager): Boolean {
    createHttpTypesModule(manager)
    createWsTypesModule(manager)
    if (manager.packageNames.contains(HTTP_SERVER_MODULE_NAME)) return false
    manager.addPackage(HTTP_SERVER_MODULE_NAME) { module ->
        buildHttpServerModule(module, policy)
    }
    return true
}

fun createHttpServer(policy: NetAccessPolicy, manager: ImportManager): Boolean = createHttpServerModule(policy, manager)

private suspend fun buildHttpServerModule(module: ModuleScope, policy: NetAccessPolicy) {
    module.eval(Source(HTTP_SERVER_MODULE_NAME, http_serverLyng))
    module.addConst("HttpHeaders", ObjHttpHeaders.type)
    module.addConst("WsMessage", ObjWsMessage.type)
    module.addConst("ServerRequest", ObjServerRequest.type)
    module.addConst("ServerExchange", ObjServerExchange.type)
    module.addConst("ServerWebSocket", ObjServerWebSocket.type)
    module.addConst("HttpServerHandle", ObjHttpServerHandle.type)
    module.addConst("HttpServer", ObjLyngHttpServer.type(policy))
}

private suspend inline fun ScopeFacade.httpServerGuard(crossinline block: suspend () -> Obj): Obj {
    return try {
        block()
    } catch (e: NetAccessDeniedException) {
        raiseIllegalOperation(e.reasonDetail ?: "http server access denied")
    } catch (e: Exception) {
        raiseIllegalOperation(e.message ?: "http server error")
    }
}

private data class RegisteredCallable(
    val callable: Obj,
    val scope: Scope,
)

private fun captureCallable(scope: Scope, rawCallable: Obj): RegisteredCallable {
    val captured = if (scope is ModuleScope) scope else scope.snapshotForClosure()
    val callable = (rawCallable as? BytecodeLambdaCallable)?.freezeForLaunch(captured) ?: rawCallable
    return RegisteredCallable(callable, captured)
}

private suspend fun RegisteredCallable.call(vararg args: Obj): Obj =
    scope.asFacade().call(callable, Arguments(args.toList()))

private val stringType = TypeDecl.Simple("String", false)
private val nullableStringType = TypeDecl.Simple("String", true)
private val boolType = TypeDecl.Simple("Bool", false)
private val intType = TypeDecl.Simple("Int", false)
private val bufferType = TypeDecl.Simple("Buffer", false)
private val nullableBufferType = TypeDecl.Simple("Buffer", true)
private val voidType = TypeDecl.Simple("Void", false)
private val httpHeadersType = TypeDecl.Simple("HttpHeaders", false)
private val serverRequestType = TypeDecl.Simple("ServerRequest", false)
private val serverExchangeType = TypeDecl.Simple("ServerExchange", false)
private val serverWebSocketType = TypeDecl.Simple("ServerWebSocket", false)
private val nullableServerWsMessageType = TypeDecl.Simple("WsMessage", true)
private val httpServerHandleType = TypeDecl.Simple("HttpServerHandle", false)
private val httpServerType = TypeDecl.Simple("HttpServer", false)
private val nullableAnyType = TypeDecl.TypeNullableAny

private fun listType(item: TypeDecl) = TypeDecl.Generic("List", listOf(item), false)

private fun fnType(returnType: TypeDecl, vararg params: TypeDecl) =
    TypeDecl.Function(receiver = null, params = params.toList(), returnType = returnType)

private fun bridgeFn(
    owner: ObjClass,
    name: String,
    typeDecl: TypeDecl.Function,
    code: suspend ScopeFacade.() -> Obj,
) {
    owner.createField(
        name = name,
        initialValue = ObjExternCallable.fromBridge { code() },
        type = net.sergeych.lyng.obj.ObjRecord.Type.Fun,
        typeDecl = typeDecl,
    )
}

private fun bridgeProperty(
    owner: ObjClass,
    name: String,
    typeDecl: TypeDecl,
    getter: suspend ScopeFacade.() -> Obj,
) {
    owner.createField(
        name = name,
        initialValue = ObjProperty(name, ObjExternCallable.fromBridge { getter() }, null),
        type = net.sergeych.lyng.obj.ObjRecord.Type.Property,
        typeDecl = typeDecl,
    )
}

private class ObjLyngHttpServer(
    private val netPolicy: NetAccessPolicy,
) : Obj() {
    private val methodRoutes = linkedMapOf<String, LinkedHashMap<String, RegisteredCallable>>()
    private val anyRoutes = linkedMapOf<String, RegisteredCallable>()
    private val wsRoutes = linkedMapOf<String, RegisteredCallable>()
    private var fallback: RegisteredCallable? = null
    private var handle: net.sergeych.lyngio.http.server.HttpServer? = null

    override val objClass: ObjClass
        get() = type(netPolicy)

    companion object {
        private val types = mutableMapOf<NetAccessPolicy, ObjClass>()

        fun type(netPolicy: NetAccessPolicy): ObjClass =
            types.getOrPut(netPolicy) {
                object : ObjClass("HttpServer") {
                    override suspend fun callOn(scope: Scope): Obj {
                        if (scope.args.list.isNotEmpty()) scope.raiseError("HttpServer() does not accept arguments")
                        return ObjLyngHttpServer(netPolicy)
                    }
                }.apply {
                    val exchangeHandlerType = fnType(nullableAnyType, serverExchangeType)
                    val webSocketHandlerType = fnType(nullableAnyType, serverWebSocketType, serverExchangeType)

                    bridgeFn(this, "get", fnType(httpServerType, stringType, exchangeHandlerType)) {
                        thisAs<ObjLyngHttpServer>().registerRoute("GET", this)
                    }
                    bridgeFn(this, "post", fnType(httpServerType, stringType, exchangeHandlerType)) {
                        thisAs<ObjLyngHttpServer>().registerRoute("POST", this)
                    }
                    bridgeFn(this, "put", fnType(httpServerType, stringType, exchangeHandlerType)) {
                        thisAs<ObjLyngHttpServer>().registerRoute("PUT", this)
                    }
                    bridgeFn(this, "delete", fnType(httpServerType, stringType, exchangeHandlerType)) {
                        thisAs<ObjLyngHttpServer>().registerRoute("DELETE", this)
                    }
                    bridgeFn(this, "any", fnType(httpServerType, stringType, exchangeHandlerType)) {
                        thisAs<ObjLyngHttpServer>().registerAny(this)
                    }
                    bridgeFn(this, "ws", fnType(httpServerType, stringType, webSocketHandlerType)) {
                        thisAs<ObjLyngHttpServer>().registerWs(this)
                    }
                    bridgeFn(this, "fallback", fnType(httpServerType, exchangeHandlerType)) {
                        thisAs<ObjLyngHttpServer>().registerFallback(this)
                    }
                    bridgeFn(this, "listen", fnType(httpServerHandleType, intType, nullableStringType, intType)) {
                        thisAs<ObjLyngHttpServer>().listen(this)
                    }
                }
            }
    }

    private fun ensureMutable(scope: ScopeFacade) {
        if (handle != null) scope.raiseIllegalState("HttpServer routes cannot be modified after listen()")
    }

    private fun requirePath(scope: ScopeFacade, index: Int): String {
        val path = scope.requiredArg<ObjString>(index).value
        if (!path.startsWith('/')) scope.raiseIllegalArgument("path must start with '/'")
        return path
    }

    private suspend fun registerRoute(method: String, scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        val path = requirePath(scope, 0)
        val handler = captureCallable(scope.requireScope(), scope.args.list[1])
        val routes = methodRoutes.getOrPut(method) { linkedMapOf() }
        if (routes.containsKey(path)) scope.raiseIllegalArgument("duplicate route for $method $path")
        routes[path] = handler
        scope.thisObj
    }

    private suspend fun registerAny(scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        val path = requirePath(scope, 0)
        val handler = captureCallable(scope.requireScope(), scope.args.list[1])
        if (anyRoutes.containsKey(path)) scope.raiseIllegalArgument("duplicate route for ANY $path")
        anyRoutes[path] = handler
        scope.thisObj
    }

    private suspend fun registerWs(scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        val path = requirePath(scope, 0)
        val handler = captureCallable(scope.requireScope(), scope.args.list[1])
        if (wsRoutes.containsKey(path)) scope.raiseIllegalArgument("duplicate websocket route for $path")
        wsRoutes[path] = handler
        scope.thisObj
    }

    private suspend fun registerFallback(scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        fallback = captureCallable(scope.requireScope(), scope.args.list[0])
        scope.thisObj
    }

    private suspend fun listen(scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        val port = scope.requiredArg<ObjInt>(0).value.toInt()
        val host = scope.args.list.getOrNull(1)?.let { objOrNullToString(scope, it, "host") }
        val backlog = scope.args.list.getOrNull(2)?.let { objToInt(scope, it, "backlog") } ?: 128
        if (port !in 0..65535) scope.raiseIllegalArgument("port must be in 0..65535")
        if (backlog <= 0) scope.raiseIllegalArgument("backlog must be positive")
        netPolicy.require(NetAccessOp.TcpListen(host, port, backlog))
        val started = startHttpServer(
            config = HttpServerConfig(host = host ?: "127.0.0.1", port = port, backlog = backlog),
        ) { request ->
            dispatchRequest(request)
        }
        handle = started
        ObjHttpServerHandle(started)
    }

    private suspend fun dispatchRequest(request: HttpRequest): HttpHandlerResult {
        val path = request.head.path
        if (request.head.wantsWebSocketUpgrade) {
            wsRoutes[path]?.let { route ->
                return HttpHandlerResult.WebSocket { session ->
                    val exchange = ObjServerExchange(request)
                    route.call(ObjServerWebSocket(session), exchange)
                }
            }
        }

        val route = methodRoutes[request.head.method.uppercase()]?.get(path)
            ?: anyRoutes[path]
            ?: fallback

        if (route == null) {
            return HttpHandlerResult.Response(HttpResponse(status = 404, body = "not found".encodeToByteArray()))
        }

        val exchange = ObjServerExchange(request)
        route.call(exchange)
        return when (val result = exchange.result) {
            is ExchangeResult.Http -> result.value
            is ExchangeResult.WebSocket -> result.value
            ExchangeResult.Unhandled -> {
                if (route === fallback) {
                    HttpHandlerResult.Response(HttpResponse(status = 404, body = "not found".encodeToByteArray()))
                } else {
                    HttpHandlerResult.Response(HttpResponse(status = 500, body = "route handler did not handle exchange".encodeToByteArray(), close = true))
                }
            }
        }
    }
}

private class ObjHttpServerHandle(
    private val handle: net.sergeych.lyngio.http.server.HttpServer,
) : Obj() {
    override val objClass: ObjClass
        get() = type

    companion object {
        val type = object : ObjClass("HttpServerHandle") {
            override suspend fun callOn(scope: Scope): Obj {
                scope.raiseError("HttpServerHandle cannot be created directly")
            }
        }.apply {
            addFn("localPort") {
                ObjInt(thisAs<ObjHttpServerHandle>().handle.localAddress().port.toLong())
            }
            addFn("close") {
                requireNoArgs()
                thisAs<ObjHttpServerHandle>().handle.close()
                ObjVoid
            }
        }
    }
}

private class ObjServerRequest(
    private val request: HttpRequest,
) : Obj() {
    override val objClass: ObjClass
        get() = type

    companion object {
        val type = object : ObjClass("ServerRequest") {
            override suspend fun callOn(scope: Scope): Obj {
                scope.raiseError("ServerRequest cannot be created directly")
            }
        }.apply {
            bridgeProperty(this, "method", stringType) {
                ObjString(thisAs<ObjServerRequest>().request.head.method)
            }
            bridgeProperty(this, "target", stringType) {
                ObjString(thisAs<ObjServerRequest>().request.head.target)
            }
            bridgeProperty(this, "path", stringType) {
                ObjString(thisAs<ObjServerRequest>().request.head.path)
            }
            bridgeProperty(this, "query", nullableStringType) {
                thisAs<ObjServerRequest>().request.head.query?.let(::ObjString) ?: ObjNull
            }
            bridgeProperty(this, "headers", httpHeadersType) {
                requestHeadersObj(thisAs<ObjServerRequest>().request.head.headers)
            }
            bridgeProperty(this, "body", bufferType) {
                ObjBuffer(thisAs<ObjServerRequest>().request.body.toUByteArray())
            }
            bridgeFn(this, "text", fnType(stringType)) {
                ObjString(thisAs<ObjServerRequest>().request.body.decodeToString())
            }
            bridgeFn(this, "isWebSocketUpgrade", fnType(boolType)) {
                ObjBool(thisAs<ObjServerRequest>().request.head.wantsWebSocketUpgrade)
            }
        }
    }
}

private sealed interface ExchangeResult {
    data object Unhandled : ExchangeResult
    data class Http(val value: HttpHandlerResult.Response) : ExchangeResult
    data class WebSocket(val value: HttpHandlerResult.WebSocket) : ExchangeResult
}

private class ObjServerExchange(
    private val request: HttpRequest,
) : Obj() {
    private val responseHeaders = linkedMapOf<String, MutableList<String>>()
    var result: ExchangeResult = ExchangeResult.Unhandled
        private set

    override val objClass: ObjClass
        get() = type

    companion object {
        val type = object : ObjClass("ServerExchange") {
            override suspend fun callOn(scope: Scope): Obj {
                scope.raiseError("ServerExchange cannot be created directly")
            }
        }.apply {
            bridgeProperty(this, "request", serverRequestType) {
                ObjServerRequest(thisAs<ObjServerExchange>().request)
            }
            bridgeFn(this, "respond", fnType(voidType, intType, nullableBufferType)) {
                val self = thisAs<ObjServerExchange>()
                val status = args.list.getOrNull(0)?.let { objToInt(this, it, "status") } ?: 200
                val body = args.list.getOrNull(1)?.let { objBufferOrNull(this, it, "body") }
                self.setHttpResponse(status, body?.byteArray?.toByteArray() ?: ByteArray(0))
                ObjVoid
            }
            bridgeFn(this, "respondText", fnType(voidType, intType, stringType)) {
                val self = thisAs<ObjServerExchange>()
                val status = args.list.getOrNull(0)?.let { objToInt(this, it, "status") } ?: 200
                val bodyText = args.list.getOrNull(1)?.let { objOrNullToString(this, it, "bodyText") } ?: ""
                self.setHttpResponse(status, bodyText.encodeToByteArray())
                ObjVoid
            }
            bridgeFn(this, "setHeader", fnType(voidType, stringType, stringType)) {
                val self = thisAs<ObjServerExchange>()
                val name = requiredArg<ObjString>(0).value
                val value = requiredArg<ObjString>(1).value
                self.ensureMutable(this)
                self.responseHeaders[name] = mutableListOf(value)
                ObjVoid
            }
            bridgeFn(this, "addHeader", fnType(voidType, stringType, stringType)) {
                val self = thisAs<ObjServerExchange>()
                val name = requiredArg<ObjString>(0).value
                val value = requiredArg<ObjString>(1).value
                self.ensureMutable(this)
                self.responseHeaders.getOrPut(name) { mutableListOf() }.add(value)
                ObjVoid
            }
            bridgeFn(
                this,
                "acceptWebSocket",
                fnType(voidType, fnType(nullableAnyType, serverWebSocketType, serverExchangeType))
            ) {
                val self = thisAs<ObjServerExchange>()
                val registered = captureCallable(requireScope(), args.list[0])
                self.ensureMutable(this)
                self.result = ExchangeResult.WebSocket(
                    HttpHandlerResult.WebSocket { session ->
                        registered.call(ObjServerWebSocket(session), self)
                    }
                )
                ObjVoid
            }
            bridgeFn(this, "isHandled", fnType(boolType)) {
                ObjBool(thisAs<ObjServerExchange>().result !== ExchangeResult.Unhandled)
            }
        }
    }

    private fun ensureMutable(scope: ScopeFacade) {
        if (result !== ExchangeResult.Unhandled) {
            scope.raiseIllegalState("exchange has already been handled")
        }
    }

    private fun setHttpResponse(status: Int, body: ByteArray) {
        result = ExchangeResult.Http(
            HttpHandlerResult.Response(
                HttpResponse(
                    status = status,
                    reason = defaultReason(status),
                    headers = responseHeaders.entries.flatMap { (name, values) -> values.map { HttpHeader(name, it) } },
                    body = body,
                )
            )
        )
    }
}

private class ObjServerWebSocket(
    private val session: HttpWebSocketSession,
) : Obj() {
    override val objClass: ObjClass
        get() = type

    companion object {
        val type = object : ObjClass("ServerWebSocket") {
            override suspend fun callOn(scope: Scope): Obj {
                scope.raiseError("ServerWebSocket cannot be created directly")
            }
        }.apply {
            bridgeFn(this, "isOpen", fnType(boolType)) {
                ObjBool(thisAs<ObjServerWebSocket>().session.isOpen())
            }
            bridgeFn(this, "sendText", fnType(voidType, stringType)) {
                thisAs<ObjServerWebSocket>().session.sendText(requiredArg<ObjString>(0).value)
                ObjVoid
            }
            bridgeFn(this, "sendBytes", fnType(voidType, bufferType)) {
                thisAs<ObjServerWebSocket>().session.sendBytes(requiredArg<ObjBuffer>(0).byteArray.toByteArray())
                ObjVoid
            }
            bridgeFn(this, "receive", fnType(nullableServerWsMessageType)) {
                thisAs<ObjServerWebSocket>().session.receive()?.let(ObjWsMessage::from) ?: ObjNull
            }
            bridgeFn(this, "close", fnType(voidType, intType, stringType)) {
                val code = args.list.getOrNull(0)?.let { objToInt(this, it, "code") } ?: 1000
                val reason = args.list.getOrNull(1)?.let { objOrNullToString(this, it, "reason") } ?: ""
                thisAs<ObjServerWebSocket>().session.close(code, reason)
                ObjVoid
            }
        }
    }
}

private fun requestHeadersObj(headers: net.sergeych.lyngio.http.server.HttpHeaders): ObjHttpHeaders {
    val all = headers.entries().groupBy(HttpHeader::name, HttpHeader::value)
    val single = all.mapValues { (_, values) -> values.first() }
    return ObjHttpHeaders.fromHeaders(single, all)
}

private suspend fun objOrNullToString(scope: ScopeFacade, value: Obj, name: String): String? = when (value) {
    ObjNull -> null
    else -> scope.toStringOf(value).value
}

private fun objToInt(scope: ScopeFacade, value: Obj, name: String): Int = when (value) {
    is ObjInt -> value.value.toInt()
    else -> scope.raiseClassCastError("$name must be Int")
}

private fun objBufferOrNull(scope: ScopeFacade, value: Obj, name: String): ObjBuffer? = when (value) {
    ObjNull -> null
    is ObjBuffer -> value
    else -> scope.raiseClassCastError("$name must be Buffer or null")
}
