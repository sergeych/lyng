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

package net.sergeych.lyngio.ws

data class LyngWsMessage(
    val isText: Boolean,
    val text: String? = null,
    val data: ByteArray? = null,
)

interface LyngWsSession {
    fun isOpen(): Boolean
    fun url(): String
    suspend fun sendText(text: String)
    suspend fun sendBytes(data: ByteArray)
    suspend fun receive(): LyngWsMessage?
    suspend fun close(code: Int, reason: String)
}

interface LyngWsEngine {
    val isSupported: Boolean
    suspend fun connect(url: String, headers: Map<String, String>): LyngWsSession
}

internal object UnsupportedLyngWsEngine : LyngWsEngine {
    override val isSupported: Boolean = false

    override suspend fun connect(url: String, headers: Map<String, String>): LyngWsSession {
        throw UnsupportedOperationException("WebSocket client is not supported on this runtime")
    }
}

expect fun getSystemWsEngine(): LyngWsEngine
