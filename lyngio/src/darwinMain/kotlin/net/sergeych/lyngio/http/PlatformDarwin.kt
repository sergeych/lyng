package net.sergeych.lyngio.http

import io.ktor.client.engine.darwin.Darwin

actual fun getSystemHttpEngine(): LyngHttpEngine = createKtorHttpEngine(Darwin)
