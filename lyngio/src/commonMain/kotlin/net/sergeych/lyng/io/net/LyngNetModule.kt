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

package net.sergeych.lyng.io.net

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjList
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.obj.thisAs
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.raiseIllegalOperation
import net.sergeych.lyng.requireNoArgs
import net.sergeych.lyngio.net.LyngDatagram
import net.sergeych.lyngio.net.LyngIpVersion
import net.sergeych.lyngio.net.LyngNetEngine
import net.sergeych.lyngio.net.LyngSocketAddress
import net.sergeych.lyngio.net.LyngTcpServer
import net.sergeych.lyngio.net.LyngTcpSocket
import net.sergeych.lyngio.net.LyngUdpSocket
import net.sergeych.lyngio.net.getSystemNetEngine
import net.sergeych.lyngio.net.security.NetAccessDeniedException
import net.sergeych.lyngio.net.security.NetAccessOp
import net.sergeych.lyngio.net.security.NetAccessPolicy
import net.sergeych.lyngio.stdlib_included.netLyng
import net.sergeych.lyngio.stdlib_included.net_typesLyng

private const val NET_MODULE_NAME = "lyng.io.net"
internal const val NET_TYPES_MODULE_NAME = "lyng.io.net.types"

fun createNetModule(policy: NetAccessPolicy, scope: Scope): Boolean =
    createNetModule(policy, scope.importManager)

fun createNet(policy: NetAccessPolicy, scope: Scope): Boolean = createNetModule(policy, scope)

fun createNetModule(policy: NetAccessPolicy, manager: ImportManager): Boolean {
    createNetTypesModule(manager)
    if (manager.packageNames.contains(NET_MODULE_NAME)) return false
    manager.addPackage(NET_MODULE_NAME) { module ->
        buildNetModule(module, policy)
    }
    return true
}

fun createNet(policy: NetAccessPolicy, manager: ImportManager): Boolean = createNetModule(policy, manager)

internal fun createNetTypesModule(manager: ImportManager): Boolean {
    if (manager.packageNames.contains(NET_TYPES_MODULE_NAME)) return false
    manager.addPackage(NET_TYPES_MODULE_NAME) { module ->
        buildNetTypesModule(module)
    }
    return true
}

private suspend fun buildNetTypesModule(module: ModuleScope) {
    module.eval(Source(NET_TYPES_MODULE_NAME, net_typesLyng))
    val enumValues = NetEnumValues.load(module)
    module.addConst("SocketAddress", ObjSocketAddress.type(enumValues))
    module.addConst("Datagram", ObjDatagram.type(enumValues))
}

private suspend fun buildNetModule(module: ModuleScope, policy: NetAccessPolicy) {
    module.eval(Source(NET_MODULE_NAME, netLyng))
    val engine = getSystemNetEngine()
    val enumValues = NetEnumValues.load(module)

    val netType = object : ObjClass("Net") {}
    netType.addClassFn("isSupported") { ObjBool(engine.isSupported) }
    netType.addClassFn("isTcpAvailable") { ObjBool(engine.isTcpAvailable) }
    netType.addClassFn("isTcpServerAvailable") { ObjBool(engine.isTcpServerAvailable) }
    netType.addClassFn("isUdpAvailable") { ObjBool(engine.isUdpAvailable) }
    netType.addClassFn("resolve") {
        netGuard {
            val host = requiredArg<ObjString>(0).value
            val port = requirePort(requiredArg<ObjInt>(1).value)
            policy.require(NetAccessOp.Resolve(host, port))
            ObjList(engine.resolve(host, port).map { ObjSocketAddress(it, enumValues) }.toMutableList())
        }
    }
    netType.addClassFn("tcpConnect") {
        netGuard {
            val host = requiredArg<ObjString>(0).value
            val port = requirePort(requiredArg<ObjInt>(1).value)
            val timeoutMillis = args.list.getOrNull(2)?.let { objOrNullToLong(this, it, "timeoutMillis") }
            val noDelay = args.list.getOrNull(3)?.let { objToBool(this, it, "noDelay") } ?: true
            policy.require(NetAccessOp.TcpConnect(host, port))
            ObjTcpSocket(engine.tcpConnect(host, port, timeoutMillis, noDelay), enumValues)
        }
    }
    netType.addClassFn("tcpListen") {
        netGuard {
            val port = requirePort(requiredArg<ObjInt>(0).value)
            val host = args.list.getOrNull(1)?.let { objOrNullToString(this, it, "host") }
            val backlog = args.list.getOrNull(2)?.let { objToInt(this, it, "backlog") } ?: 128
            requirePositive(backlog, "backlog")
            val reuseAddress = args.list.getOrNull(3)?.let { objToBool(this, it, "reuseAddress") } ?: true
            policy.require(NetAccessOp.TcpListen(host, port, backlog))
            ObjTcpServer(engine.tcpListen(host, port, backlog, reuseAddress), enumValues)
        }
    }
    netType.addClassFn("udpBind") {
        netGuard {
            val port = args.list.getOrNull(0)?.let { objToInt(this, it, "port") } ?: 0
            requirePort(port)
            val host = args.list.getOrNull(1)?.let { objOrNullToString(this, it, "host") }
            val reuseAddress = args.list.getOrNull(2)?.let { objToBool(this, it, "reuseAddress") } ?: true
            policy.require(NetAccessOp.UdpBind(host, port))
            ObjUdpSocket(engine.udpBind(host, port, reuseAddress), enumValues)
        }
    }

    module.addConst("Net", netType)
    module.addConst("SocketAddress", ObjSocketAddress.type(enumValues))
    module.addConst("Datagram", ObjDatagram.type(enumValues))
    module.addConst("TcpSocket", ObjTcpSocket.type(enumValues))
    module.addConst("TcpServer", ObjTcpServer.type(enumValues))
    module.addConst("UdpSocket", ObjUdpSocket.type(enumValues))
}

private suspend inline fun ScopeFacade.netGuard(crossinline block: suspend () -> Obj): Obj {
    return try {
        block()
    } catch (e: NetAccessDeniedException) {
        raiseIllegalOperation(e.reasonDetail ?: "network access denied")
    } catch (e: Exception) {
        raiseIllegalOperation(e.message ?: "network error")
    }
}

private class NetEnumValues(
    val ipv4: Obj,
    val ipv6: Obj,
) {
    fun of(version: LyngIpVersion): Obj = when (version) {
        LyngIpVersion.IPV4 -> ipv4
        LyngIpVersion.IPV6 -> ipv6
    }

    companion object {
        suspend fun load(module: ModuleScope): NetEnumValues {
            val ipVersionClass = module["IpVersion"]?.value as? ObjClass
                ?: error("lyng.io.net.IpVersion is missing after declaration load")
            return NetEnumValues(
                ipv4 = ipVersionClass.readField(module, "IPV4").value,
                ipv6 = ipVersionClass.readField(module, "IPV6").value,
            )
        }
    }
}

private class ObjSocketAddress(
    private val address: LyngSocketAddress,
    private val enumValues: NetEnumValues,
) : Obj() {
    override val objClass: ObjClass
        get() = type(enumValues)

    override suspend fun defaultToString(scope: Scope): ObjString = ObjString(renderAddress(address))

    companion object {
        private data class EnumKey(val ipv4: Obj, val ipv6: Obj)

        private val types = mutableMapOf<EnumKey, ObjClass>()

        fun type(enumValues: NetEnumValues): ObjClass =
            types.getOrPut(EnumKey(enumValues.ipv4, enumValues.ipv6)) {
                object : ObjClass("SocketAddress") {
                    override suspend fun callOn(scope: Scope): Obj {
                        scope.raiseError("SocketAddress cannot be created directly")
                    }
                }.apply {
                    addProperty("host", getter = { ObjString(thisAs<ObjSocketAddress>().address.host) })
                    addProperty("port", getter = { ObjInt(thisAs<ObjSocketAddress>().address.port.toLong()) })
                    addProperty("ipVersion", getter = { enumValues.of(thisAs<ObjSocketAddress>().address.ipVersion) })
                    addProperty("resolved", getter = { ObjBool(thisAs<ObjSocketAddress>().address.resolved) })
                    addFn("toString") { ObjString(renderAddress(thisAs<ObjSocketAddress>().address)) }
                }
            }
    }
}

private class ObjDatagram(
    private val datagram: LyngDatagram,
    private val enumValues: NetEnumValues,
) : Obj() {
    override val objClass: ObjClass
        get() = type(enumValues)

    companion object {
        private data class EnumKey(val ipv4: Obj, val ipv6: Obj)

        private val types = mutableMapOf<EnumKey, ObjClass>()

        fun type(enumValues: NetEnumValues): ObjClass =
            types.getOrPut(EnumKey(enumValues.ipv4, enumValues.ipv6)) {
                object : ObjClass("Datagram") {
                    override suspend fun callOn(scope: Scope): Obj {
                        scope.raiseError("Datagram cannot be created directly")
                    }
                }.apply {
                    addProperty("data", getter = {
                        ObjBuffer(thisAs<ObjDatagram>().datagram.data.toUByteArray())
                    })
                    addProperty("address", getter = {
                        ObjSocketAddress(thisAs<ObjDatagram>().datagram.address, enumValues)
                    })
                }
            }
    }
}

private class ObjTcpSocket(
    private val socket: LyngTcpSocket,
    private val enumValues: NetEnumValues,
) : Obj() {
    override val objClass: ObjClass
        get() = type(enumValues)

    companion object {
        private val types = mutableMapOf<NetEnumValues, ObjClass>()

        fun type(enumValues: NetEnumValues): ObjClass =
            types.getOrPut(enumValues) {
                object : ObjClass("TcpSocket") {
                    override suspend fun callOn(scope: Scope): Obj {
                        scope.raiseError("TcpSocket cannot be created directly")
                    }
                }.apply {
                    addFn("isOpen") { ObjBool(thisAs<ObjTcpSocket>().socket.isOpen()) }
                    addFn("localAddress") { ObjSocketAddress(thisAs<ObjTcpSocket>().socket.localAddress(), enumValues) }
                    addFn("remoteAddress") { ObjSocketAddress(thisAs<ObjTcpSocket>().socket.remoteAddress(), enumValues) }
                    addFn("read") {
                        val maxBytes = args.list.getOrNull(0)?.let { objToInt(this, it, "maxBytes") } ?: 65536
                        requirePositive(maxBytes, "maxBytes")
                        thisAs<ObjTcpSocket>().socket.read(maxBytes)?.let { ObjBuffer(it.toUByteArray()) } ?: ObjNull
                    }
                    addFn("readLine") {
                        thisAs<ObjTcpSocket>().socket.readLine()?.let(::ObjString) ?: ObjNull
                    }
                    addFn("write") {
                        val data = requiredArg<ObjBuffer>(0).byteArray.toByteArray()
                        thisAs<ObjTcpSocket>().socket.write(data)
                        ObjVoid
                    }
                    addFn("writeUtf8") {
                        val text = requiredArg<ObjString>(0).value
                        thisAs<ObjTcpSocket>().socket.writeUtf8(text)
                        ObjVoid
                    }
                    addFn("flush") {
                        requireNoArgs()
                        thisAs<ObjTcpSocket>().socket.flush()
                        ObjVoid
                    }
                    addFn("close") {
                        requireNoArgs()
                        thisAs<ObjTcpSocket>().socket.close()
                        ObjVoid
                    }
                }
            }
    }
}

private class ObjTcpServer(
    private val server: LyngTcpServer,
    private val enumValues: NetEnumValues,
) : Obj() {
    override val objClass: ObjClass
        get() = type(enumValues)

    companion object {
        private val types = mutableMapOf<NetEnumValues, ObjClass>()

        fun type(enumValues: NetEnumValues): ObjClass =
            types.getOrPut(enumValues) {
                object : ObjClass("TcpServer") {
                    override suspend fun callOn(scope: Scope): Obj {
                        scope.raiseError("TcpServer cannot be created directly")
                    }
                }.apply {
                    addFn("isOpen") { ObjBool(thisAs<ObjTcpServer>().server.isOpen()) }
                    addFn("localAddress") { ObjSocketAddress(thisAs<ObjTcpServer>().server.localAddress(), enumValues) }
                    addFn("accept") {
                        ObjTcpSocket(thisAs<ObjTcpServer>().server.accept(), enumValues)
                    }
                    addFn("close") {
                        requireNoArgs()
                        thisAs<ObjTcpServer>().server.close()
                        ObjVoid
                    }
                }
            }
    }
}

private class ObjUdpSocket(
    private val socket: LyngUdpSocket,
    private val enumValues: NetEnumValues,
) : Obj() {
    override val objClass: ObjClass
        get() = type(enumValues)

    companion object {
        private val types = mutableMapOf<NetEnumValues, ObjClass>()

        fun type(enumValues: NetEnumValues): ObjClass =
            types.getOrPut(enumValues) {
                object : ObjClass("UdpSocket") {
                    override suspend fun callOn(scope: Scope): Obj {
                        scope.raiseError("UdpSocket cannot be created directly")
                    }
                }.apply {
                    addFn("isOpen") { ObjBool(thisAs<ObjUdpSocket>().socket.isOpen()) }
                    addFn("localAddress") { ObjSocketAddress(thisAs<ObjUdpSocket>().socket.localAddress(), enumValues) }
                    addFn("receive") {
                        val maxBytes = args.list.getOrNull(0)?.let { objToInt(this, it, "maxBytes") } ?: 65536
                        requirePositive(maxBytes, "maxBytes")
                        thisAs<ObjUdpSocket>().socket.receive(maxBytes)?.let { ObjDatagram(it, enumValues) } ?: ObjNull
                    }
                    addFn("send") {
                        val data = requiredArg<ObjBuffer>(0).byteArray.toByteArray()
                        val host = requiredArg<ObjString>(1).value
                        val port = requirePort(requiredArg<ObjInt>(2).value)
                        thisAs<ObjUdpSocket>().socket.send(data, host, port)
                        ObjVoid
                    }
                    addFn("close") {
                        requireNoArgs()
                        thisAs<ObjUdpSocket>().socket.close()
                        ObjVoid
                    }
                }
            }
    }
}

private fun renderAddress(address: LyngSocketAddress): String =
    if (address.ipVersion == LyngIpVersion.IPV6) "[${address.host}]:${address.port}" else "${address.host}:${address.port}"

private fun ScopeFacade.requirePort(value: Long): Int {
    if (value !in 0..65535) raiseIllegalArgument("port must be in 0..65535")
    return value.toInt()
}

private fun ScopeFacade.requirePort(value: Int): Int {
    if (value !in 0..65535) raiseIllegalArgument("port must be in 0..65535")
    return value
}

private fun ScopeFacade.requirePositive(value: Int, name: String) {
    if (value <= 0) raiseIllegalArgument("$name must be positive")
}

private suspend fun objOrNullToString(scope: ScopeFacade, value: Obj, name: String): String? = when (value) {
    ObjNull -> null
    else -> scope.toStringOf(value).value
}

private fun objToInt(scope: ScopeFacade, value: Obj, name: String): Int = when (value) {
    is ObjInt -> value.value.toInt()
    else -> scope.raiseClassCastError("$name must be Int")
}

private fun objToBool(scope: ScopeFacade, value: Obj, name: String): Boolean = when (value) {
    is ObjBool -> value.value
    else -> scope.raiseClassCastError("$name must be Bool")
}

private fun objOrNullToLong(scope: ScopeFacade, value: Obj, name: String): Long? = when (value) {
    ObjNull -> null
    is ObjInt -> value.value
    else -> scope.raiseClassCastError("$name must be Int or null")
}
