package net.sergeych.lyng.io.http.server

import kotlinx.serialization.json.Json
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Source
import net.sergeych.lyng.Arguments
import net.sergeych.lyng.CallSignature
import net.sergeych.lyng.TypeDecl
import net.sergeych.lyng.asFacade
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjExternCallable
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjList
import net.sergeych.lyng.obj.ObjMap
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjProperty
import net.sergeych.lyng.obj.ObjRegex
import net.sergeych.lyng.obj.ObjRegexMatch
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjTypeExpr
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.obj.thisAs
import net.sergeych.lyng.io.http.ObjHttpHeaders
import net.sergeych.lyng.io.http.createHttpTypesModule
import net.sergeych.lyng.io.ws.ObjWsMessage
import net.sergeych.lyng.io.ws.createWsTypesModule
import net.sergeych.lyng.serialization.ObjJsonClass
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
import net.sergeych.lyngio.http.server.decodePathSegment
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
    val requestContextClass = ObjServerExchange.type(module.requireClass("RequestContext"))
    module.addConst("HttpHeaders", ObjHttpHeaders.type)
    module.addConst("WsMessage", ObjWsMessage.type)
    module.addConst("ServerRequest", ObjServerRequest.type)
    module.addConst("RequestContext", requestContextClass)
    module.addConst("ServerWebSocket", ObjServerWebSocket.type)
    module.addConst("HttpServerHandle", ObjHttpServerHandle.type)
    module.addConst("Router", ObjLyngRouter.type(requestContextClass))
    module.addConst("HttpServer", ObjLyngHttpServer.type(policy, requestContextClass))
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

private suspend fun RegisteredCallable.callWithReceiver(receiver: Obj, vararg args: Obj): Obj =
    scope.asFacade().call(callable, Arguments(args.toList()), receiver)

private val stringType = TypeDecl.Simple("String", false)
private val nullableStringType = TypeDecl.Simple("String", true)
private val boolType = TypeDecl.Simple("Bool", false)
private val intType = TypeDecl.Simple("Int", false)
private val bufferType = TypeDecl.Simple("Buffer", false)
private val nullableBufferType = TypeDecl.Simple("Buffer", true)
private val regexType = TypeDecl.Simple("Regex", false)
private val nullableRegexMatchType = TypeDecl.Simple("RegexMatch", true)
private val voidType = TypeDecl.Simple("Void", false)
private val httpHeadersType = TypeDecl.Simple("HttpHeaders", false)
private val serverRequestType = TypeDecl.Simple("ServerRequest", false)
private val requestContextType = TypeDecl.Simple("RequestContext", false)
private val serverWebSocketType = TypeDecl.Simple("ServerWebSocket", false)
private val nullableServerWsMessageType = TypeDecl.Simple("WsMessage", true)
private val httpServerHandleType = TypeDecl.Simple("HttpServerHandle", false)
private val httpServerType = TypeDecl.Simple("HttpServer", false)
private val routerType = TypeDecl.Simple("Router", false)
private val nullableAnyType = TypeDecl.TypeNullableAny

private fun listType(item: TypeDecl) = TypeDecl.Generic("List", listOf(item), false)
private fun mapType(key: TypeDecl, value: TypeDecl) = TypeDecl.Generic("Map", listOf(key, value), false)
private fun unionType(vararg options: TypeDecl) = TypeDecl.Union(options.toList(), nullable = false)

private fun fnType(returnType: TypeDecl, vararg params: TypeDecl) =
    TypeDecl.Function(receiver = null, params = params.toList(), returnType = returnType)

private fun receiverFnType(receiver: TypeDecl, returnType: TypeDecl, vararg params: TypeDecl) =
    TypeDecl.Function(receiver = receiver, params = params.toList(), returnType = returnType)

private fun receiverCallSignature(receiverTypeName: String) =
    CallSignature(tailBlockReceiverType = receiverTypeName)

private fun bridgeFn(
    owner: ObjClass,
    name: String,
    typeDecl: TypeDecl.Function,
    callSignature: net.sergeych.lyng.CallSignature? = null,
    code: suspend ScopeFacade.() -> Obj,
) {
    owner.createField(
        name = name,
        initialValue = ObjExternCallable.fromBridge { code() },
        type = net.sergeych.lyng.obj.ObjRecord.Type.Fun,
        typeDecl = typeDecl,
        callSignature = callSignature,
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

private fun requireRoutePattern(scope: ScopeFacade, index: Int): RoutePattern = when (val path = scope.args.list.getOrNull(index)) {
    is ObjString -> {
        if (!path.value.startsWith('/')) scope.raiseIllegalArgument("path must start with '/'")
        RoutePattern.Exact(path.value)
    }
    is ObjRegex -> RoutePattern.Regex(path)
    else -> scope.raiseClassCastError("path must be String or Regex")
}

private fun requirePathTemplate(scope: ScopeFacade, index: Int): String {
    val template = scope.requiredArg<ObjString>(index).value
    if (!template.startsWith('/')) scope.raiseIllegalArgument("pathTemplate must start with '/'")
    return template
}

private class RouteRegistry(
    private val exchangeClass: ObjClass,
) {
    private val methodRoutes = linkedMapOf<String, LinkedHashMap<String, RegisteredCallable>>()
    private val methodRegexRoutes = linkedMapOf<String, MutableList<RegisteredRegexRoute>>()
    private val anyRoutes = linkedMapOf<String, RegisteredCallable>()
    private val anyRegexRoutes = mutableListOf<RegisteredRegexRoute>()
    private val wsRoutes = linkedMapOf<String, RegisteredCallable>()
    private val wsRegexRoutes = mutableListOf<RegisteredRegexRoute>()
    private var fallback: RegisteredCallable? = null

    suspend fun registerRoute(method: String, scope: ScopeFacade) {
        val path = requireRoutePattern(scope, 0)
        val handler = captureCallable(scope.requireScope(), scope.args.list[1])
        when (path) {
            is RoutePattern.Exact -> {
                val routes = methodRoutes.getOrPut(method) { linkedMapOf() }
                if (routes.containsKey(path.path)) scope.raiseIllegalArgument("duplicate route for $method ${path.path}")
                routes[path.path] = handler
            }
            is RoutePattern.Regex -> {
                val routes = methodRegexRoutes.getOrPut(method) { mutableListOf() }
                if (routes.any { it.pattern.regex.pattern == path.regex.regex.pattern }) {
                    scope.raiseIllegalArgument("duplicate regex route for $method ${path.regex.regex.pattern}")
                }
                routes += RegisteredRegexRoute(path.regex, handler)
            }
        }
    }

    suspend fun registerTemplateRoute(method: String, scope: ScopeFacade) {
        val template = requirePathTemplate(scope, 0)
        val handler = captureCallable(scope.requireScope(), scope.args.list[1])
        val routes = methodRegexRoutes.getOrPut(method) { mutableListOf() }
        val compiled = compilePathTemplate(template, scope)
        if (routes.any { it.identity == compiled.identity }) {
            scope.raiseIllegalArgument("duplicate path route for $method $template")
        }
        routes += RegisteredRegexRoute(compiled.pattern, handler, compiled.paramNames, compiled.identity)
    }

    suspend fun registerAny(scope: ScopeFacade) {
        val path = requireRoutePattern(scope, 0)
        val handler = captureCallable(scope.requireScope(), scope.args.list[1])
        when (path) {
            is RoutePattern.Exact -> {
                if (anyRoutes.containsKey(path.path)) scope.raiseIllegalArgument("duplicate route for ANY ${path.path}")
                anyRoutes[path.path] = handler
            }
            is RoutePattern.Regex -> {
                if (anyRegexRoutes.any { it.pattern.regex.pattern == path.regex.regex.pattern }) {
                    scope.raiseIllegalArgument("duplicate regex route for ANY ${path.regex.regex.pattern}")
                }
                anyRegexRoutes += RegisteredRegexRoute(path.regex, handler)
            }
        }
    }

    suspend fun registerTemplateAny(scope: ScopeFacade) {
        val template = requirePathTemplate(scope, 0)
        val handler = captureCallable(scope.requireScope(), scope.args.list[1])
        val compiled = compilePathTemplate(template, scope)
        if (anyRegexRoutes.any { it.identity == compiled.identity }) {
            scope.raiseIllegalArgument("duplicate path route for ANY $template")
        }
        anyRegexRoutes += RegisteredRegexRoute(compiled.pattern, handler, compiled.paramNames, compiled.identity)
    }

    suspend fun registerWs(scope: ScopeFacade) {
        val path = requireRoutePattern(scope, 0)
        val handler = captureCallable(scope.requireScope(), scope.args.list[1])
        when (path) {
            is RoutePattern.Exact -> {
                if (wsRoutes.containsKey(path.path)) scope.raiseIllegalArgument("duplicate websocket route for ${path.path}")
                wsRoutes[path.path] = handler
            }
            is RoutePattern.Regex -> {
                if (wsRegexRoutes.any { it.pattern.regex.pattern == path.regex.regex.pattern }) {
                    scope.raiseIllegalArgument("duplicate websocket regex route for ${path.regex.regex.pattern}")
                }
                wsRegexRoutes += RegisteredRegexRoute(path.regex, handler)
            }
        }
    }

    suspend fun registerTemplateWs(scope: ScopeFacade) {
        val template = requirePathTemplate(scope, 0)
        val handler = captureCallable(scope.requireScope(), scope.args.list[1])
        val compiled = compilePathTemplate(template, scope)
        if (wsRegexRoutes.any { it.identity == compiled.identity }) {
            scope.raiseIllegalArgument("duplicate websocket path route for $template")
        }
        wsRegexRoutes += RegisteredRegexRoute(compiled.pattern, handler, compiled.paramNames, compiled.identity)
    }

    suspend fun registerFallback(scope: ScopeFacade) {
        fallback = captureCallable(scope.requireScope(), scope.args.list[0])
    }

    fun mount(scope: ScopeFacade, other: RouteRegistry) {
        other.methodRoutes.forEach { (method, routes) ->
            val target = methodRoutes.getOrPut(method) { linkedMapOf() }
            routes.forEach { (path, handler) ->
                if (target.containsKey(path)) scope.raiseIllegalArgument("duplicate route for $method $path")
                target[path] = handler
            }
        }
        other.methodRegexRoutes.forEach { (method, routes) ->
            val target = methodRegexRoutes.getOrPut(method) { mutableListOf() }
            routes.forEach { route ->
                if (target.any { it.identity == route.identity }) {
                    scope.raiseIllegalArgument("duplicate route for $method ${route.identity.removePrefix("path:")}")
                }
                target += route
            }
        }
        other.anyRoutes.forEach { (path, handler) ->
            if (anyRoutes.containsKey(path)) scope.raiseIllegalArgument("duplicate route for ANY $path")
            anyRoutes[path] = handler
        }
        other.anyRegexRoutes.forEach { route ->
            if (anyRegexRoutes.any { it.identity == route.identity }) {
                scope.raiseIllegalArgument("duplicate route for ANY ${route.identity.removePrefix("path:")}")
            }
            anyRegexRoutes += route
        }
        other.wsRoutes.forEach { (path, handler) ->
            if (wsRoutes.containsKey(path)) scope.raiseIllegalArgument("duplicate websocket route for $path")
            wsRoutes[path] = handler
        }
        other.wsRegexRoutes.forEach { route ->
            if (wsRegexRoutes.any { it.identity == route.identity }) {
                scope.raiseIllegalArgument("duplicate websocket route for ${route.identity.removePrefix("path:")}")
            }
            wsRegexRoutes += route
        }
        if (other.fallback != null) {
            if (fallback != null) scope.raiseIllegalArgument("fallback is already defined")
            fallback = other.fallback
        }
    }

    suspend fun dispatchRequest(request: HttpRequest): HttpHandlerResult {
        val path = request.head.path
        if (request.head.wantsWebSocketUpgrade) {
            wsRoutes[path]?.let { route ->
                return HttpHandlerResult.WebSocket { session ->
                    val exchange = ObjServerExchange(request, null, emptyMap(), exchangeClass)
                    route.callWithReceiver(exchange, ObjServerWebSocket(session))
                }
            }
            matchRegexRoute(wsRegexRoutes, path)?.let { matched ->
                return HttpHandlerResult.WebSocket { session ->
                    val exchange = ObjServerExchange(request, ObjRegexMatch(matched.match), matched.params, exchangeClass)
                    matched.handler.callWithReceiver(exchange, ObjServerWebSocket(session))
                }
            }
        }

        val method = request.head.method.uppercase()
        val exactRoute = methodRoutes[method]?.get(path) ?: anyRoutes[path]
        if (exactRoute != null) {
            val exchange = ObjServerExchange(request, null, emptyMap(), exchangeClass)
            exactRoute.callWithReceiver(exchange)
            return exchangeResult(exactRoute === fallback, exchange)
        }

        matchRegexRoute(methodRegexRoutes[method], path)?.let { matched ->
            val exchange = ObjServerExchange(request, ObjRegexMatch(matched.match), matched.params, exchangeClass)
            matched.handler.callWithReceiver(exchange)
            return exchangeResult(false, exchange)
        }

        matchRegexRoute(anyRegexRoutes, path)?.let { matched ->
            val exchange = ObjServerExchange(request, ObjRegexMatch(matched.match), matched.params, exchangeClass)
            matched.handler.callWithReceiver(exchange)
            return exchangeResult(false, exchange)
        }

        val fallbackRoute = fallback ?: return HttpHandlerResult.Response(
            HttpResponse(status = 404, body = "not found".encodeToByteArray())
        )
        val exchange = ObjServerExchange(request, null, emptyMap(), exchangeClass)
        fallbackRoute.callWithReceiver(exchange)
        return exchangeResult(true, exchange)
    }

    private fun exchangeResult(isFallback: Boolean, exchange: ObjServerExchange): HttpHandlerResult = when (val result = exchange.result) {
        is ExchangeResult.Http -> result.value
        is ExchangeResult.WebSocket -> result.value
        ExchangeResult.Unhandled -> {
            if (isFallback) {
                HttpHandlerResult.Response(HttpResponse(status = 404, body = "not found".encodeToByteArray()))
            } else {
                HttpHandlerResult.Response(HttpResponse(status = 500, body = "route handler did not handle exchange".encodeToByteArray(), close = true))
            }
        }
    }
}

private class ObjLyngHttpServer(
    private val netPolicy: NetAccessPolicy,
    private val requestContextClass: ObjClass,
) : Obj() {
    private val routes = RouteRegistry(requestContextClass)
    private var handle: net.sergeych.lyngio.http.server.HttpServer? = null

    override val objClass: ObjClass
        get() = type(netPolicy, requestContextClass)

    companion object {
        private val types = mutableMapOf<Pair<NetAccessPolicy, ObjClass>, ObjClass>()

        fun type(netPolicy: NetAccessPolicy, requestContextClass: ObjClass): ObjClass =
            types.getOrPut(netPolicy to requestContextClass) {
                object : ObjClass("HttpServer") {
                    override suspend fun callOn(scope: Scope): Obj {
                        if (scope.args.list.isNotEmpty()) scope.raiseError("HttpServer() does not accept arguments")
                        return ObjLyngHttpServer(netPolicy, requestContextClass)
                    }
                }.apply {
                    val routeArgType = unionType(stringType, regexType)
                    val exchangeHandlerType = receiverFnType(requestContextType, nullableAnyType)
                    val webSocketHandlerType = receiverFnType(requestContextType, nullableAnyType, serverWebSocketType)
                    val exchangeHandlerSignature = receiverCallSignature("RequestContext")

                    bridgeFn(this, "get", fnType(httpServerType, routeArgType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerRoute("GET", this)
                    }
                    bridgeFn(this, "getPath", fnType(httpServerType, stringType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerTemplateRoute("GET", this)
                    }
                    bridgeFn(this, "post", fnType(httpServerType, routeArgType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerRoute("POST", this)
                    }
                    bridgeFn(this, "postPath", fnType(httpServerType, stringType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerTemplateRoute("POST", this)
                    }
                    bridgeFn(this, "put", fnType(httpServerType, routeArgType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerRoute("PUT", this)
                    }
                    bridgeFn(this, "putPath", fnType(httpServerType, stringType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerTemplateRoute("PUT", this)
                    }
                    bridgeFn(this, "delete", fnType(httpServerType, routeArgType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerRoute("DELETE", this)
                    }
                    bridgeFn(this, "deletePath", fnType(httpServerType, stringType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerTemplateRoute("DELETE", this)
                    }
                    bridgeFn(this, "any", fnType(httpServerType, routeArgType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerAny(this)
                    }
                    bridgeFn(this, "anyPath", fnType(httpServerType, stringType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerTemplateAny(this)
                    }
                    bridgeFn(this, "ws", fnType(httpServerType, routeArgType, webSocketHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerWs(this)
                    }
                    bridgeFn(this, "wsPath", fnType(httpServerType, stringType, webSocketHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerTemplateWs(this)
                    }
                    bridgeFn(this, "fallback", fnType(httpServerType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngHttpServer>().registerFallback(this)
                    }
                    bridgeFn(this, "mount", fnType(httpServerType, routerType)) {
                        thisAs<ObjLyngHttpServer>().mount(this)
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

    private suspend fun registerRoute(method: String, scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        routes.registerRoute(method, scope)
        scope.thisObj
    }

    private suspend fun registerTemplateRoute(method: String, scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        routes.registerTemplateRoute(method, scope)
        scope.thisObj
    }

    private suspend fun registerAny(scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        routes.registerAny(scope)
        scope.thisObj
    }

    private suspend fun registerTemplateAny(scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        routes.registerTemplateAny(scope)
        scope.thisObj
    }

    private suspend fun registerWs(scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        routes.registerWs(scope)
        scope.thisObj
    }

    private suspend fun registerTemplateWs(scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        routes.registerTemplateWs(scope)
        scope.thisObj
    }

    private suspend fun registerFallback(scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        routes.registerFallback(scope)
        scope.thisObj
    }

    private suspend fun mount(scope: ScopeFacade): Obj = scope.httpServerGuard {
        ensureMutable(scope)
        routes.mount(scope, scope.requiredArg<ObjLyngRouter>(0).routes)
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
            routes.dispatchRequest(request)
        }
        handle = started
        ObjHttpServerHandle(started)
    }
}

private class ObjLyngRouter(
    private val requestContextClass: ObjClass,
) : Obj() {
    val routes = RouteRegistry(requestContextClass)

    override val objClass: ObjClass
        get() = type(requestContextClass)

    companion object {
        private val types = mutableMapOf<ObjClass, ObjClass>()

        fun type(requestContextClass: ObjClass): ObjClass =
            types.getOrPut(requestContextClass) {
                object : ObjClass("Router") {
                    override suspend fun callOn(scope: Scope): Obj {
                        if (scope.args.list.isNotEmpty()) scope.raiseError("Router() does not accept arguments")
                        return ObjLyngRouter(requestContextClass)
                    }
                }.apply {
                    val routeArgType = unionType(stringType, regexType)
                    val exchangeHandlerType = receiverFnType(requestContextType, nullableAnyType)
                    val webSocketHandlerType = receiverFnType(requestContextType, nullableAnyType, serverWebSocketType)
                    val exchangeHandlerSignature = receiverCallSignature("RequestContext")

                    bridgeFn(this, "get", fnType(routerType, routeArgType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerRoute("GET", this)
                    }
                    bridgeFn(this, "getPath", fnType(routerType, stringType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerTemplateRoute("GET", this)
                    }
                    bridgeFn(this, "post", fnType(routerType, routeArgType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerRoute("POST", this)
                    }
                    bridgeFn(this, "postPath", fnType(routerType, stringType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerTemplateRoute("POST", this)
                    }
                    bridgeFn(this, "put", fnType(routerType, routeArgType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerRoute("PUT", this)
                    }
                    bridgeFn(this, "putPath", fnType(routerType, stringType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerTemplateRoute("PUT", this)
                    }
                    bridgeFn(this, "delete", fnType(routerType, routeArgType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerRoute("DELETE", this)
                    }
                    bridgeFn(this, "deletePath", fnType(routerType, stringType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerTemplateRoute("DELETE", this)
                    }
                    bridgeFn(this, "any", fnType(routerType, routeArgType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerAny(this)
                    }
                    bridgeFn(this, "anyPath", fnType(routerType, stringType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerTemplateAny(this)
                    }
                    bridgeFn(this, "ws", fnType(routerType, routeArgType, webSocketHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerWs(this)
                    }
                    bridgeFn(this, "wsPath", fnType(routerType, stringType, webSocketHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerTemplateWs(this)
                    }
                    bridgeFn(this, "fallback", fnType(routerType, exchangeHandlerType), exchangeHandlerSignature) {
                        thisAs<ObjLyngRouter>().registerFallback(this)
                    }
                    bridgeFn(this, "mount", fnType(routerType, routerType)) {
                        thisAs<ObjLyngRouter>().mount(this)
                    }
                }
            }
    }

    private suspend fun registerRoute(method: String, scope: ScopeFacade): Obj = scope.httpServerGuard {
        routes.registerRoute(method, scope)
        scope.thisObj
    }

    private suspend fun registerTemplateRoute(method: String, scope: ScopeFacade): Obj = scope.httpServerGuard {
        routes.registerTemplateRoute(method, scope)
        scope.thisObj
    }

    private suspend fun registerAny(scope: ScopeFacade): Obj = scope.httpServerGuard {
        routes.registerAny(scope)
        scope.thisObj
    }

    private suspend fun registerTemplateAny(scope: ScopeFacade): Obj = scope.httpServerGuard {
        routes.registerTemplateAny(scope)
        scope.thisObj
    }

    private suspend fun registerWs(scope: ScopeFacade): Obj = scope.httpServerGuard {
        routes.registerWs(scope)
        scope.thisObj
    }

    private suspend fun registerTemplateWs(scope: ScopeFacade): Obj = scope.httpServerGuard {
        routes.registerTemplateWs(scope)
        scope.thisObj
    }

    private suspend fun registerFallback(scope: ScopeFacade): Obj = scope.httpServerGuard {
        routes.registerFallback(scope)
        scope.thisObj
    }

    private suspend fun mount(scope: ScopeFacade): Obj = scope.httpServerGuard {
        routes.mount(scope, scope.requiredArg<ObjLyngRouter>(0).routes)
        scope.thisObj
    }
}

private sealed interface RoutePattern {
    data class Exact(val path: String) : RoutePattern
    data class Regex(val regex: ObjRegex) : RoutePattern
}

private data class RegisteredRegexRoute(
    val pattern: ObjRegex,
    val handler: RegisteredCallable,
    val paramNames: List<String> = emptyList(),
    val identity: String = "re:${pattern.regex.pattern}",
)

private data class MatchedRegexRoute(
    val handler: RegisteredCallable,
    val match: MatchResult,
    val params: Map<String, String>,
)

private fun matchRegexRoute(routes: List<RegisteredRegexRoute>?, path: String): MatchedRegexRoute? {
    if (routes == null) return null
    for (route in routes) {
        val match = route.pattern.regex.matchEntire(path) ?: continue
        val params = if (route.paramNames.isEmpty()) {
            emptyMap()
        } else {
            route.paramNames.withIndex().associateTo(linkedMapOf()) { (index, name) ->
                name to decodePathSegment(match.groupValues[index + 1])
            }
        }
        return MatchedRegexRoute(route.handler, match, params)
    }
    return null
}

private data class CompiledPathTemplate(
    val pattern: ObjRegex,
    val paramNames: List<String>,
    val identity: String,
)

private fun compilePathTemplate(template: String, scope: ScopeFacade): CompiledPathTemplate {
    val segments = if (template == "/") emptyList() else template.removePrefix("/").split('/')
    val names = mutableListOf<String>()
    val pattern = buildString {
        append('^')
        if (segments.isEmpty()) {
            append('/')
        } else {
            for (segment in segments) {
                append('/')
                if (segment.startsWith('{') && segment.endsWith('}')) {
                    val name = segment.substring(1, segment.length - 1)
                    if (!isValidPathParamName(name)) {
                        scope.raiseIllegalArgument("invalid path parameter name: $name")
                    }
                    if (!names.add(name)) {
                        scope.raiseIllegalArgument("duplicate path parameter name: $name")
                    }
                    append("([^/]+)")
                } else if ('{' in segment || '}' in segment) {
                    scope.raiseIllegalArgument("path template segments must be literal text or {name}")
                } else {
                    append(Regex.escape(segment))
                }
            }
        }
        append('$')
    }
    return CompiledPathTemplate(
        pattern = ObjRegex(Regex(pattern)),
        paramNames = names,
        identity = "path:$template"
    )
}

private fun isValidPathParamName(name: String): Boolean =
    name.isNotEmpty() &&
        (name.first() == '_' || name.first().isLetter()) &&
        name.drop(1).all { it == '_' || it.isLetterOrDigit() }

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
            bridgeProperty(this, "pathParts", listType(stringType)) {
                ObjList(thisAs<ObjServerRequest>().request.head.pathParts.map(::ObjString).toMutableList())
            }
            bridgeProperty(this, "queryString", nullableStringType) {
                thisAs<ObjServerRequest>().request.head.queryString?.let(::ObjString) ?: ObjNull
            }
            bridgeProperty(this, "query", mapType(stringType, stringType)) {
                thisAs<ObjServerRequest>().request.head.query.toObjMap()
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
    private val routeMatch: ObjRegexMatch?,
    private val routeParams: Map<String, String>,
    private val type: ObjClass,
) : Obj() {
    private val responseHeaders = linkedMapOf<String, MutableList<String>>()
    var result: ExchangeResult = ExchangeResult.Unhandled
        private set

    override val objClass: ObjClass
        get() = type

    companion object {
        private val types = mutableMapOf<ObjClass, ObjClass>()

        fun type(base: ObjClass): ObjClass =
            types.getOrPut(base) {
                object : ObjClass("RequestContext") {
                    override suspend fun callOn(scope: Scope): Obj {
                        scope.raiseError("RequestContext cannot be created directly")
                    }
                }.apply {
                    bridgeProperty(this, "request", serverRequestType) {
                        ObjServerRequest(thisAs<ObjServerExchange>().request)
                    }
                    bridgeProperty(this, "routeMatch", nullableRegexMatchType) {
                        thisAs<ObjServerExchange>().routeMatch ?: ObjNull
                    }
                    bridgeProperty(this, "routeParams", mapType(stringType, stringType)) {
                        thisAs<ObjServerExchange>().routeParams.toObjMap()
                    }
                    bridgeFn(
                        this,
                        "jsonBody",
                        base.getInstanceMemberOrNull("jsonBody")?.typeDecl as? TypeDecl.Function
                            ?: fnType(nullableAnyType),
                        callSignature = base.getInstanceMemberOrNull("jsonBody")?.callSignature
                    ) {
                        val self = thisAs<ObjServerExchange>()
                        val targetType = resolveJsonTargetType(requireScope())
                        val text = self.request.body.decodeToString()
                        ObjJsonClass.decodeFromJsonElement(requireScope(), Json.parseToJsonElement(text), targetType)
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
                    bridgeFn(
                        this,
                        "respondJson",
                        base.getInstanceMemberOrNull("respondJson")?.typeDecl as? TypeDecl.Function
                            ?: fnType(voidType, nullableAnyType, intType),
                        callSignature = base.getInstanceMemberOrNull("respondJson")?.callSignature
                    ) {
                        val self = thisAs<ObjServerExchange>()
                        val body = args.list.getOrNull(0) ?: ObjNull
                        val status = args.list.getOrNull(1)?.let { objToInt(this, it, "status") } ?: 200
                        self.ensureMutable(this)
                        self.responseHeaders["Content-Type"] = mutableListOf("application/json; charset=utf-8")
                        val bodyText = if (body === ObjNull) {
                            "null"
                        } else {
                            (body.invokeInstanceMethod(requireScope(), "toJsonString") as ObjString).value
                        }
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
                        fnType(voidType, receiverFnType(requestContextType, nullableAnyType, serverWebSocketType)),
                        receiverCallSignature("RequestContext")
                    ) {
                        val self = thisAs<ObjServerExchange>()
                        val registered = captureCallable(requireScope(), args.list[0])
                        self.ensureMutable(this)
                        self.result = ExchangeResult.WebSocket(
                            HttpHandlerResult.WebSocket { session ->
                                registered.callWithReceiver(self, ObjServerWebSocket(session))
                            }
                        )
                        ObjVoid
                    }
                    bridgeFn(this, "isHandled", fnType(boolType)) {
                        ObjBool(thisAs<ObjServerExchange>().result !== ExchangeResult.Unhandled)
                    }
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

private fun resolveJsonTargetType(scope: Scope): TypeDecl {
    val explicit = scope.args.explicitTypeArgs.singleOrNull()
    if (explicit != null) return explicit
    val bound = scope["T"]?.value
    return when (bound) {
        is ObjTypeExpr -> bound.typeDecl
        is ObjClass -> TypeDecl.Simple(bound.className, false)
        else -> scope.raiseIllegalArgument("jsonBody requires exactly one type argument")
    }
}

private fun Map<String, String>.toObjMap(): ObjMap =
    ObjMap(entries.associate { ObjString(it.key) to ObjString(it.value) }.toMutableMap())
