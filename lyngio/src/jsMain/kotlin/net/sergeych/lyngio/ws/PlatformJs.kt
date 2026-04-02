package net.sergeych.lyngio.ws

import io.ktor.client.engine.js.Js

actual fun getSystemWsEngine(): LyngWsEngine = createKtorWsEngine(Js)
