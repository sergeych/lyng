package net.sergeych.lyngio.http

import kotlin.test.*

class HttpRequestFailureTest {
    private val request = LyngHttpRequest("GET", "https://user:secret@example.com/page?key=private#fragment")
    @Test fun savedFatalTlsAlertGetsSpecificCauseAndNoUnchangedRetryAdvice() {
        val saved = IllegalStateException("Received alert during handshake. Level: FATAL, code: InternalError")
        val message = LyngHttpRequestException(request, saved).message!!
        assertTrue(message.startsWith("HTTP_TLS_FAILED: GET https://[redacted]@example.com/page"))
        assertTrue(message.contains("FATAL, code: InternalError"))
        assertTrue(message.contains("Do not retry this URL unchanged"))
        assertTrue(message.contains("changing its path/query will not repair TLS"))
        assertFalse(message.contains("secret")); assertFalse(message.contains("private"))
    }
    @Test fun nestedCausesAndTimeoutDnsAndConnectionFailuresRemainDistinct() {
        val nested = IllegalStateException("request failed", IllegalArgumentException("TLS certificate rejected"))
        assertTrue(describeHttpFailure(request, nested).contains("HTTP_TLS_FAILED"))
        for ((detail, code) in listOf("request timed out" to "HTTP_TIMEOUT",
            "UnknownHostException: example.com" to "HTTP_DNS_FAILED",
            "Connection refused" to "HTTP_CONNECTION_FAILED")) {
            assertTrue(describeHttpFailure(request, IllegalStateException(detail)).startsWith(code))
        }
    }
}
