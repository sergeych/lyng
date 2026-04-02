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

package net.sergeych.lyng.io.http

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjImmutableMap
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjList
import net.sergeych.lyng.obj.ObjMap
import net.sergeych.lyng.obj.ObjMapEntry
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.obj.thisAs
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.raiseIllegalOperation
import net.sergeych.lyng.requireNoArgs
import net.sergeych.lyng.requireScope
import net.sergeych.lyngio.http.LyngHttpRequest
import net.sergeych.lyngio.http.LyngHttpResponse
import net.sergeych.lyngio.http.getSystemHttpEngine
import net.sergeych.lyngio.http.security.HttpAccessDeniedException
import net.sergeych.lyngio.http.security.HttpAccessOp
import net.sergeych.lyngio.http.security.HttpAccessPolicy
import net.sergeych.lyngio.stdlib_included.httpLyng

private const val HTTP_MODULE_NAME = "lyng.io.http"

fun createHttpModule(policy: HttpAccessPolicy, scope: Scope): Boolean =
    createHttpModule(policy, scope.importManager)

fun createHttp(policy: HttpAccessPolicy, scope: Scope): Boolean = createHttpModule(policy, scope)

fun createHttpModule(policy: HttpAccessPolicy, manager: ImportManager): Boolean {
    if (manager.packageNames.contains(HTTP_MODULE_NAME)) return false
    manager.addPackage(HTTP_MODULE_NAME) { module ->
        buildHttpModule(module, policy)
    }
    return true
}

fun createHttp(policy: HttpAccessPolicy, manager: ImportManager): Boolean = createHttpModule(policy, manager)

private suspend fun buildHttpModule(module: ModuleScope, policy: HttpAccessPolicy) {
    module.eval(Source(HTTP_MODULE_NAME, httpLyng))
    val engine = getSystemHttpEngine()

    val headersType = ObjHttpHeaders.type
    val requestType = ObjHttpRequest.type
    val responseType = ObjHttpResponse.type

    val httpType = object : ObjClass("Http") {}
    httpType.addClassFn("isSupported") {
        ObjBool(engine.isSupported)
    }
    httpType.addClassFn("request") {
        httpGuard {
            val req = requiredArg<ObjHttpRequest>(0)
            val built = req.toRequest(this)
            policy.require(HttpAccessOp.Request(built.method, built.url))
            ObjHttpResponse.from(engine.request(built))
        }
    }
    httpType.addClassFn("get") {
        httpGuard {
            val url = requiredArg<ObjString>(0).value
            val headers = parseHeaderEntries(args.list.drop(1))
            policy.require(HttpAccessOp.Request("GET", url))
            ObjHttpResponse.from(engine.request(LyngHttpRequest(method = "GET", url = url, headers = headers)))
        }
    }
    httpType.addClassFn("post") {
        httpGuard {
            val url = requiredArg<ObjString>(0).value
            val bodyText = requiredArg<ObjString>(1).value
            val contentType = args.list.getOrNull(2)?.let { objOrNullToString(this, it) }
            val headers = parseHeaderEntries(args.list.drop(3)).toMutableMap()
            if (contentType != null && "Content-Type" !in headers) headers["Content-Type"] = contentType
            policy.require(HttpAccessOp.Request("POST", url))
            ObjHttpResponse.from(
                engine.request(
                    LyngHttpRequest(method = "POST", url = url, headers = headers, bodyText = bodyText)
                )
            )
        }
    }
    httpType.addClassFn("postBytes") {
        httpGuard {
            val url = requiredArg<ObjString>(0).value
            val body = requiredArg<ObjBuffer>(1).byteArray.toByteArray()
            val contentType = args.list.getOrNull(2)?.let { objOrNullToString(this, it) }
            val headers = parseHeaderEntries(args.list.drop(3)).toMutableMap()
            if (contentType != null && "Content-Type" !in headers) headers["Content-Type"] = contentType
            policy.require(HttpAccessOp.Request("POST", url))
            ObjHttpResponse.from(
                engine.request(
                    LyngHttpRequest(method = "POST", url = url, headers = headers, bodyBytes = body)
                )
            )
        }
    }

    module.addConst("Http", httpType)
    module.addConst("HttpHeaders", headersType)
    module.addConst("HttpRequest", requestType)
    module.addConst("HttpResponse", responseType)
}

private suspend inline fun ScopeFacade.httpGuard(crossinline block: suspend () -> Obj): Obj {
    return try {
        block()
    } catch (e: HttpAccessDeniedException) {
        raiseIllegalOperation(e.reasonDetail ?: "http access denied")
    } catch (e: Exception) {
        raiseIllegalOperation(e.message ?: "http error")
    }
}

private class ObjHttpHeaders(
    singleValueHeaders: Map<String, String> = emptyMap(),
    private val allHeaders: Map<String, List<String>> = emptyMap(),
) : Obj() {
    private val entries: LinkedHashMap<Obj, Obj> =
        LinkedHashMap(singleValueHeaders.entries.associate { ObjString(it.key) to ObjString(it.value) })

    override val objClass: ObjClass
        get() = type

    override suspend fun getAt(scope: Scope, index: Obj): Obj = findEntry(index)?.value ?: ObjNull

    override suspend fun contains(scope: Scope, other: Obj): Boolean = findEntry(other) != null

    override suspend fun defaultToString(scope: Scope): ObjString {
        val rendered = buildString {
            append("HttpHeaders(")
            var first = true
            for ((k, v) in entries) {
                if (!first) append(", ")
                append(k.toString(scope).value)
                append(" => ")
                append(v.toString(scope).value)
                first = false
            }
            append(")")
        }
        return ObjString(rendered)
    }

    companion object {
        val type = object : ObjClass("HttpHeaders", ObjMap.type) {
            override suspend fun callOn(scope: Scope): Obj = ObjHttpHeaders()
        }.apply {
            addFn("get") {
                val self = thisAs<ObjHttpHeaders>()
                val name = requiredArg<ObjString>(0).value
                self.firstValue(name)?.let(::ObjString) ?: ObjNull
            }
            addFn("getAll") {
                val self = thisAs<ObjHttpHeaders>()
                val name = requiredArg<ObjString>(0).value
                ObjList(self.valuesOf(name).map(::ObjString).toMutableList())
            }
            addFn("names") {
                val self = thisAs<ObjHttpHeaders>()
                ObjList(self.allHeaders.keys.map(::ObjString).toMutableList())
            }
            addFn("getOrNull") {
                val self = thisAs<ObjHttpHeaders>()
                val name = requiredArg<ObjString>(0).value
                self.firstValue(name)?.let(::ObjString) ?: ObjNull
            }
            addProperty("size", getter = { ObjInt(thisAs<ObjHttpHeaders>().entries.size.toLong()) })
            addProperty("keys", getter = { ObjList(thisAs<ObjHttpHeaders>().entries.keys.toMutableList()) })
            addProperty("values", getter = { ObjList(thisAs<ObjHttpHeaders>().entries.values.toMutableList()) })
            addFn("iterator") {
                ObjList(
                    thisAs<ObjHttpHeaders>().entries.map { (k, v) -> ObjMapEntry(k, v) }.toMutableList()
                ).invokeInstanceMethod(requireScope(), "iterator")
            }
        }
    }

    private fun valuesOf(name: String): List<String> = allHeaders[lookupKey(name)] ?: emptyList()

    private fun firstValue(name: String): String? = valuesOf(name).firstOrNull()

    private fun lookupKey(name: String): String =
        allHeaders.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name

    private fun findEntry(index: Obj): Map.Entry<Obj, Obj>? {
        if (index is ObjString) {
            return entries.entries.firstOrNull { (k, _) ->
                (k as? ObjString)?.value?.equals(index.value, ignoreCase = true) == true
            }
        }
        return entries.entries.firstOrNull { it.key == index }
    }
}

private class ObjHttpRequest(
    var method: String = "GET",
    var url: String = "",
    val headers: MutableMap<String, String> = linkedMapOf(),
    var bodyText: String? = null,
    var bodyBytes: ByteArray? = null,
    var timeoutMillis: Long? = null,
) : Obj() {
    override val objClass: ObjClass
        get() = type

    suspend fun toRequest(scope: ScopeFacade): LyngHttpRequest {
        if (bodyText != null && bodyBytes != null) {
            scope.raiseIllegalArgument("Only one of bodyText or bodyBytes may be set")
        }
        return LyngHttpRequest(
            method = method,
            url = url,
            headers = LinkedHashMap(headers),
            bodyText = bodyText,
            bodyBytes = bodyBytes,
            timeoutMillis = timeoutMillis,
        )
    }

    companion object {
        val type = object : ObjClass("HttpRequest") {
            override suspend fun callOn(scope: Scope): Obj {
                if (scope.args.list.isNotEmpty()) scope.raiseError("HttpRequest() does not accept arguments")
                return ObjHttpRequest()
            }
        }.apply {
            addProperty("method",
                getter = { ObjString(thisAs<ObjHttpRequest>().method) },
                setter = { value ->
                    thisAs<ObjHttpRequest>().method = objOrNullToString(this, value)
                        ?: raiseIllegalArgument("method cannot be null")
                }
            )
            addProperty("url",
                getter = { ObjString(thisAs<ObjHttpRequest>().url) },
                setter = { value ->
                    thisAs<ObjHttpRequest>().url = objOrNullToString(this, value)
                        ?: raiseIllegalArgument("url cannot be null")
                }
            )
            addProperty("headers",
                getter = { thisAs<ObjHttpRequest>().headers.toObjMap() },
                setter = { value ->
                    thisAs<ObjHttpRequest>().headers.clear()
                    thisAs<ObjHttpRequest>().headers.putAll(mapObjToStrings(this, value))
                }
            )
            addProperty("bodyText",
                getter = { thisAs<ObjHttpRequest>().bodyText?.let(::ObjString) ?: ObjNull },
                setter = { value ->
                    thisAs<ObjHttpRequest>().bodyText = objOrNullToString(this, value)
                }
            )
            addProperty("bodyBytes",
                getter = { thisAs<ObjHttpRequest>().bodyBytes?.let { ObjBuffer(it.toUByteArray()) } ?: ObjNull },
                setter = { value ->
                    thisAs<ObjHttpRequest>().bodyBytes = when (value) {
                        ObjNull -> null
                        is ObjBuffer -> value.byteArray.toByteArray()
                        else -> raiseClassCastError("bodyBytes must be Buffer or null")
                    }
                }
            )
            addProperty("timeoutMillis",
                getter = { thisAs<ObjHttpRequest>().timeoutMillis?.let { ObjInt(it) } ?: ObjNull },
                setter = { value ->
                    thisAs<ObjHttpRequest>().timeoutMillis = when (value) {
                        ObjNull -> null
                        is ObjInt -> value.value
                        else -> raiseClassCastError("timeoutMillis must be Int or null")
                    }
                }
            )
        }
    }
}

private class ObjHttpResponse(
    val status: Long,
    val statusText: String,
    val headers: ObjHttpHeaders,
    private val bodyBytes: ByteArray,
) : Obj() {
    override val objClass: ObjClass
        get() = type

    companion object {
        val type = object : ObjClass("HttpResponse") {
            override suspend fun callOn(scope: Scope): Obj {
                scope.raiseError("HttpResponse cannot be created directly")
            }
        }.apply {
            addProperty("status", getter = { ObjInt(thisAs<ObjHttpResponse>().status) })
            addProperty("statusText", getter = { ObjString(thisAs<ObjHttpResponse>().statusText) })
            addProperty("headers", getter = { thisAs<ObjHttpResponse>().headers })
            addFn("text") {
                ObjString(thisAs<ObjHttpResponse>().bodyBytes.decodeToString())
            }
            addFn("bytes") {
                ObjBuffer(thisAs<ObjHttpResponse>().bodyBytes.toUByteArray())
            }
        }

        fun from(response: LyngHttpResponse): ObjHttpResponse {
            val single = linkedMapOf<String, String>()
            response.headers.forEach { (name, values) ->
                if (values.isNotEmpty() && name !in single) single[name] = values.first()
            }
            return ObjHttpResponse(
                status = response.status.toLong(),
                statusText = response.statusText,
                headers = ObjHttpHeaders(singleValueHeaders = single, allHeaders = response.headers),
                bodyBytes = response.bodyBytes,
            )
        }
    }
}

private suspend fun ScopeFacade.parseHeaderEntries(values: List<Obj>): Map<String, String> {
    val out = linkedMapOf<String, String>()
    values.forEach { value ->
        when (value) {
            is ObjMapEntry -> {
                out[toStringOf(value.key).value] = toStringOf(value.value).value
            }
            else -> {
                if (!value.isInstanceOf(net.sergeych.lyng.obj.ObjArray)) {
                    raiseIllegalArgument("headers entries must be MapEntry or [key, value]")
                }
                val size = (value.invokeInstanceMethod(requireScope(), "size") as ObjInt).value.toInt()
                if (size != 2) {
                    raiseIllegalArgument("header entry array must contain exactly 2 items")
                }
                out[toStringOf(value.getAt(requireScope(), ObjInt.Zero)).value] =
                    toStringOf(value.getAt(requireScope(), ObjInt.One)).value
            }
        }
    }
    return out
}

private suspend fun mapObjToStrings(scope: ScopeFacade, value: Obj): MutableMap<String, String> {
    val entries = when (value) {
        is ObjMap -> value.map
        is ObjImmutableMap -> value.map
        ObjNull -> return linkedMapOf()
        else -> scope.raiseClassCastError("headers must be Map<String, String>")
    }
    return entries.entries.associateTo(linkedMapOf()) { (k, v) ->
        scope.toStringOf(k).value to scope.toStringOf(v).value
    }
}

private suspend fun objOrNullToString(scope: ScopeFacade, value: Obj): String? = when (value) {
    ObjNull -> null
    else -> scope.toStringOf(value).value
}

private fun Map<String, String>.toObjMap(): ObjMap =
    ObjMap(entries.associate { ObjString(it.key) to ObjString(it.value) }.toMutableMap())
