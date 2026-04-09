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

package net.sergeych.lyngio.net

enum class LyngIpVersion {
    IPV4,
    IPV6,
}

data class LyngSocketAddress(
    val host: String,
    val port: Int,
    val ipVersion: LyngIpVersion,
    val resolved: Boolean,
)

data class LyngDatagram(
    val data: ByteArray,
    val address: LyngSocketAddress,
)

interface LyngTcpSocket {
    fun isOpen(): Boolean
    fun localAddress(): LyngSocketAddress
    fun remoteAddress(): LyngSocketAddress
    suspend fun read(maxBytes: Int): ByteArray?
    suspend fun readLine(): String?
    suspend fun write(data: ByteArray)
    suspend fun writeUtf8(text: String)
    suspend fun flush()
    fun close()
}

interface LyngTcpServer {
    fun isOpen(): Boolean
    fun localAddress(): LyngSocketAddress
    suspend fun accept(): LyngTcpSocket
    fun close()
}

interface LyngUdpSocket {
    fun isOpen(): Boolean
    fun localAddress(): LyngSocketAddress
    suspend fun receive(maxBytes: Int): LyngDatagram?
    suspend fun send(data: ByteArray, host: String, port: Int)
    fun close()
}

interface LyngNetEngine {
    val isSupported: Boolean
    val isTcpAvailable: Boolean
    val isTcpServerAvailable: Boolean
    val isUdpAvailable: Boolean

    suspend fun resolve(host: String, port: Int): List<LyngSocketAddress>
    suspend fun tcpConnect(host: String, port: Int, timeoutMillis: Long?, noDelay: Boolean): LyngTcpSocket
    suspend fun tcpListen(host: String?, port: Int, backlog: Int, reuseAddress: Boolean): LyngTcpServer
    suspend fun udpBind(host: String?, port: Int, reuseAddress: Boolean): LyngUdpSocket
}

internal object UnsupportedLyngNetEngine : LyngNetEngine {
    override val isSupported: Boolean = false
    override val isTcpAvailable: Boolean = false
    override val isTcpServerAvailable: Boolean = false
    override val isUdpAvailable: Boolean = false

    override suspend fun resolve(host: String, port: Int): List<LyngSocketAddress> {
        throw UnsupportedOperationException("Raw networking is not supported on this runtime")
    }

    override suspend fun tcpConnect(host: String, port: Int, timeoutMillis: Long?, noDelay: Boolean): LyngTcpSocket {
        throw UnsupportedOperationException("TCP client sockets are not supported on this runtime")
    }

    override suspend fun tcpListen(host: String?, port: Int, backlog: Int, reuseAddress: Boolean): LyngTcpServer {
        throw UnsupportedOperationException("TCP server sockets are not supported on this runtime")
    }

    override suspend fun udpBind(host: String?, port: Int, reuseAddress: Boolean): LyngUdpSocket {
        throw UnsupportedOperationException("UDP sockets are not supported on this runtime")
    }
}

expect fun getSystemNetEngine(): LyngNetEngine

expect fun shutdownSystemNetEngine()
