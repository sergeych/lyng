package net.sergeych.lyngio.ws

import io.ktor.client.engine.darwin.Darwin

actual fun getSystemWsEngine(): LyngWsEngine = createKtorWsEngine(Darwin)
