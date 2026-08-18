@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package net.sergeych.lyngio.http

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.HttpResponseRedirectEvent
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.authority
import io.ktor.http.isSecure
import io.ktor.http.takeFrom

private val redirectMethods = setOf(HttpMethod.Get, HttpMethod.Head)

internal class HttpRedirectLimitExceededException(
    val limit: Int,
    val initialUrl: String,
    val lastUrl: String,
) : IllegalStateException(
    "HTTP_REDIRECT_LIMIT_EXCEEDED: limit=$limit, initialUrl=$initialUrl, lastUrl=$lastUrl"
)

internal class CappedHttpRedirectConfig {
    var maxRedirects: Int = LyngHttpConfig.DEFAULT_MAX_REDIRECTS
    var checkHttpMethod: Boolean = true
    var allowHttpsDowngrade: Boolean = false
}

internal val CappedHttpRedirect: ClientPlugin<CappedHttpRedirectConfig> = createClientPlugin(
    "CappedHttpRedirect",
    ::CappedHttpRedirectConfig,
) {
    val maxRedirects = pluginConfig.maxRedirects.also {
        require(it >= 0) { "maxRedirects must not be negative" }
    }
    val checkHttpMethod = pluginConfig.checkHttpMethod
    val allowHttpsDowngrade = pluginConfig.allowHttpsDowngrade

    suspend fun Send.Sender.followRedirects(
        context: HttpRequestBuilder,
        origin: HttpClientCall,
        client: HttpClient,
    ): HttpClientCall {
        var call = origin
        var requestBuilder = context
        val initialUrl = origin.request.url.toString()
        var redirectCount = 0

        while (call.response.status.isRedirectStatus()) {
            val location = call.response.headers[HttpHeaders.Location] ?: return call
            val previousProtocol = call.request.url.protocol
            val previousAuthority = call.request.url.authority
            requestBuilder = HttpRequestBuilder().apply {
                takeFrom(requestBuilder)
                url.parameters.clear()
                url.takeFrom(location)
                if (!allowHttpsDowngrade && previousProtocol.isSecure() && !url.protocol.isSecure()) {
                    return call
                }
                if (previousAuthority != url.authority) {
                    headers.remove(HttpHeaders.Authorization)
                }
            }
            if (redirectCount >= maxRedirects) {
                throw HttpRedirectLimitExceededException(
                    maxRedirects,
                    initialUrl,
                    requestBuilder.url.buildString(),
                )
            }
            redirectCount += 1
            client.monitor.raise(HttpResponseRedirectEvent, call.response)
            call = proceed(requestBuilder)
        }
        return call
    }

    on(Send) { request ->
        val origin = proceed(request)
        if (checkHttpMethod && origin.request.method !in redirectMethods) {
            return@on origin
        }
        followRedirects(request, origin, client)
    }
}

private fun HttpStatusCode.isRedirectStatus(): Boolean = when (value) {
    HttpStatusCode.MovedPermanently.value,
    HttpStatusCode.Found.value,
    HttpStatusCode.TemporaryRedirect.value,
    HttpStatusCode.PermanentRedirect.value,
    HttpStatusCode.SeeOther.value -> true
    else -> false
}
