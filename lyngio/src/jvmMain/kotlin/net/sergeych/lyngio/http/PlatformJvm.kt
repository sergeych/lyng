package net.sergeych.lyngio.http

import io.ktor.client.engine.cio.CIO

actual fun getSystemHttpEngine(): LyngHttpEngine = createKtorHttpEngine(CIO)
