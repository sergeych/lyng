package net.sergeych.lyngio.ws

import io.ktor.client.engine.cio.CIO

actual fun getSystemWsEngine(): LyngWsEngine = createKtorWsEngine(CIO)
