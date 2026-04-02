package net.sergeych.lyngio

import net.sergeych.lyngio.http.getSystemHttpEngine
import net.sergeych.lyngio.ws.getSystemWsEngine
import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformCapabilityJsTest {
    @Test
    fun testJsHttpAndWsCapabilitiesReportSupported() {
        assertTrue(getSystemHttpEngine().isSupported, "JS HTTP engine should be available")
        assertTrue(getSystemWsEngine().isSupported, "JS websocket engine should be available")
    }
}
