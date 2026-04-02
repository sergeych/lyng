package net.sergeych.lyngio.http

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.headers
import io.ktor.http.takeFrom

actual fun getSystemHttpEngine(): LyngHttpEngine = LinuxLyngHttpEngine

private object LinuxLyngHttpEngine : LyngHttpEngine {
    private val clientResult by lazy {
        runCatching {
            HttpClient(Curl) {
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
