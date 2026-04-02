package net.sergeych.lyngio.http

import io.ktor.client.engine.curl.Curl

actual fun getSystemHttpEngine(): LyngHttpEngine = createKtorHttpEngine(Curl)
