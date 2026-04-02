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

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.headers
import io.ktor.http.takeFrom

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

private object UnsupportedHttpEngine : LyngHttpEngine {
    override val isSupported: Boolean = false

    override suspend fun request(request: LyngHttpRequest): LyngHttpResponse {
        throw UnsupportedOperationException("HTTP client is not supported on this runtime")
    }
}

private object KtorLyngHttpEngine : LyngHttpEngine {
    private val clientResult by lazy {
        runCatching {
            HttpClient(CIO) {
                expectSuccess = false
            }
        }
    }

    override val isSupported: Boolean
        get() = clientResult.isSuccess

    override suspend fun request(request: LyngHttpRequest): LyngHttpResponse {
        val httpClient = clientResult.getOrElse {
            throw UnsupportedOperationException(it.message ?: "HTTP client is not supported")
        }

        val response = httpClient.request {
            applyRequest(request)
        }
        return LyngHttpResponse(
            status = response.status.value,
            statusText = response.status.description,
            headers = response.headers.entries().associate { it.key to it.value.toList() },
            bodyBytes = response.body<ByteArray>(),
        )
    }

    private fun HttpRequestBuilder.applyRequest(request: LyngHttpRequest) {
        method = HttpMethod.parse(request.method.uppercase())
        url.takeFrom(request.url)
        headers {
            request.headers.forEach { (name, value) -> append(name, value) }
        }
        request.timeoutMillis?.let { timeout { requestTimeoutMillis = it } }
        when {
            request.bodyBytes != null && request.bodyText != null ->
                throw IllegalArgumentException("Only one of bodyText or bodyBytes may be set")
            request.bodyBytes != null -> setBody(request.bodyBytes)
            request.bodyText != null -> setBody(request.bodyText)
        }
    }
}

fun getSystemHttpEngine(): LyngHttpEngine = KtorLyngHttpEngine
