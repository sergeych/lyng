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

package net.sergeych.lyngio.http

data class LyngHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val bodyText: String? = null,
    val bodyBytes: ByteArray? = null,
    val timeoutMillis: Long? = null,
)

data class LyngHttpResponse(
    val status: Int,
    val statusText: String,
    val headers: Map<String, List<String>>,
    val bodyBytes: ByteArray,
)

interface LyngHttpEngine {
    val isSupported: Boolean
    suspend fun request(request: LyngHttpRequest): LyngHttpResponse
}

internal object UnsupportedHttpEngine : LyngHttpEngine {
    override val isSupported: Boolean = false

    override suspend fun request(request: LyngHttpRequest): LyngHttpResponse {
        throw UnsupportedOperationException("HTTP client is not supported on this runtime")
    }
}

expect fun getSystemHttpEngine(): LyngHttpEngine
