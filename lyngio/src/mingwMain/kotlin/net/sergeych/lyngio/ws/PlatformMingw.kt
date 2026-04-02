package net.sergeych.lyngio.ws

import io.ktor.client.engine.winhttp.WinHttp

actual fun getSystemWsEngine(): LyngWsEngine = createKtorWsEngine(WinHttp)
