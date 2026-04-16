package net.sergeych.lyngio.ws

import io.ktor.client.engine.curl.Curl

actual fun getSystemWsEngine(): LyngWsEngine =
    createSocketWsEngine(secureFallback = createKtorWsEngine(Curl, shareClient = false))
