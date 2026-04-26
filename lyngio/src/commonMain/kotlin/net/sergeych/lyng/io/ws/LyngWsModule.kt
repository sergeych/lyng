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

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjInt
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
import net.sergeych.lyngio.stdlib_included.wsLyng
import net.sergeych.lyngio.stdlib_included.ws_typesLyng
import net.sergeych.lyngio.ws.LyngWsEngine
import net.sergeych.lyngio.ws.LyngWsMessage
import net.sergeych.lyngio.ws.LyngWsSession
import net.sergeych.lyngio.ws.getSystemWsEngine
import net.sergeych.lyngio.ws.security.WsAccessDeniedException
import net.sergeych.lyngio.ws.security.WsAccessOp
import net.sergeych.lyngio.ws.security.WsAccessPolicy

private const val WS_MODULE_NAME = "lyng.io.ws"
internal const val WS_TYPES_MODULE_NAME = "lyng.io.ws.types"

fun createWsModule(policy: WsAccessPolicy, scope: Scope): Boolean =
    createWsModule(policy, scope.importManager)

fun createWs(policy: WsAccessPolicy, scope: Scope): Boolean = createWsModule(policy, scope)

fun createWsModule(policy: WsAccessPolicy, manager: ImportManager): Boolean {
    createWsTypesModule(manager)
    if (manager.packageNames.contains(WS_MODULE_NAME)) return false
    manager.addPackage(WS_MODULE_NAME) { module ->
        buildWsModule(module, policy)
    }
    return true
}

fun createWs(policy: WsAccessPolicy, manager: ImportManager): Boolean = createWsModule(policy, manager)

internal fun createWsTypesModule(manager: ImportManager): Boolean {
    if (manager.packageNames.contains(WS_TYPES_MODULE_NAME)) return false
    manager.addPackage(WS_TYPES_MODULE_NAME) { module ->
        buildWsTypesModule(module)
    }
    return true
}

private suspend fun buildWsTypesModule(module: ModuleScope) {
    module.eval(Source(WS_TYPES_MODULE_NAME, ws_typesLyng))
    module.addConst("WsMessage", ObjWsMessage.type)
}

private suspend fun buildWsModule(module: ModuleScope, policy: WsAccessPolicy) {
    module.eval(Source(WS_MODULE_NAME, wsLyng))
    val engine = getSystemWsEngine()

    val wsType = object : ObjClass("Ws") {}
    wsType.addClassFn("isSupported") { ObjBool(engine.isSupported) }
    wsType.addClassFn("connect") {
        wsGuard {
            val url = requiredArg<ObjString>(0).value
            val headers = parseHeaderEntries(args.list.drop(1))
            policy.require(WsAccessOp.Connect(url))
            ObjWsSession(url, engine.connect(url, headers), policy)
        }
    }

    module.addConst("Ws", wsType)
    module.addConst("WsMessage", ObjWsMessage.type)
    module.addConst("WsSession", ObjWsSession.type)
}

private suspend inline fun ScopeFacade.wsGuard(crossinline block: suspend () -> Obj): Obj {
    return try {
        block()
    } catch (e: WsAccessDeniedException) {
        raiseIllegalOperation(e.reasonDetail ?: "websocket access denied")
    } catch (e: Exception) {
        raiseIllegalOperation(e.message ?: "websocket error")
    }
}

internal class ObjWsMessage(
    private val message: LyngWsMessage,
) : Obj() {
    override val objClass: ObjClass
        get() = type

    companion object {
        val type = object : ObjClass("WsMessage") {
            override suspend fun callOn(scope: Scope): Obj {
                scope.raiseError("WsMessage cannot be created directly")
            }
        }.apply {
            addProperty("isText", getter = { ObjBool(thisAs<ObjWsMessage>().message.isText) })
            addProperty("text", getter = {
                thisAs<ObjWsMessage>().message.text?.let(::ObjString) ?: ObjNull
            })
            addProperty("data", getter = {
                thisAs<ObjWsMessage>().message.data?.let { ObjBuffer(it.toUByteArray()) } ?: ObjNull
            })
        }

        internal fun from(message: LyngWsMessage): ObjWsMessage = ObjWsMessage(message)
    }
}

private class ObjWsSession(
    private val targetUrl: String,
    private val session: LyngWsSession,
    private val policy: WsAccessPolicy,
) : Obj() {
    override val objClass: ObjClass
        get() = type

    companion object {
        val type = object : ObjClass("WsSession") {
            override suspend fun callOn(scope: Scope): Obj {
                scope.raiseError("WsSession cannot be created directly")
            }
        }.apply {
            addFn("isOpen") {
                ObjBool(thisAs<ObjWsSession>().session.isOpen())
            }
            addFn("url") {
                ObjString(thisAs<ObjWsSession>().targetUrl)
            }
            addFn("sendText") {
                val self = thisAs<ObjWsSession>()
                val text = requiredArg<ObjString>(0).value
                self.policy.require(WsAccessOp.Send(self.targetUrl, text.encodeToByteArray().size, isText = true))
                self.session.sendText(text)
                ObjVoid
            }
            addFn("sendBytes") {
                val self = thisAs<ObjWsSession>()
                val data = requiredArg<ObjBuffer>(0).byteArray.toByteArray()
                self.policy.require(WsAccessOp.Send(self.targetUrl, data.size, isText = false))
                self.session.sendBytes(data)
                ObjVoid
            }
            addFn("receive") {
                val self = thisAs<ObjWsSession>()
                self.policy.require(WsAccessOp.Receive(self.targetUrl))
                self.session.receive()?.let(ObjWsMessage::from) ?: ObjNull
            }
            addFn("close") {
                val self = thisAs<ObjWsSession>()
                val code = args.list.getOrNull(0)?.let { objToInt(this, it, "code") } ?: 1000
                val reason = args.list.getOrNull(1)?.let { objOrNullToString(this, it, "reason") } ?: ""
                self.session.close(code, reason)
                ObjVoid
            }
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

private suspend fun objOrNullToString(scope: ScopeFacade, value: Obj, name: String): String? = when (value) {
    ObjNull -> null
    else -> scope.toStringOf(value).value
}

private fun objToInt(scope: ScopeFacade, value: Obj, name: String): Int = when (value) {
    is ObjInt -> value.value.toInt()
    else -> scope.raiseClassCastError("$name must be Int")
}
