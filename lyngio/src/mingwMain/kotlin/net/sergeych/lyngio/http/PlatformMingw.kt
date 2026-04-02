package net.sergeych.lyngio.http

import io.ktor.client.engine.winhttp.WinHttp

actual fun getSystemHttpEngine(): LyngHttpEngine = createKtorHttpEngine(WinHttp)
