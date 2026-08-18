package net.sergeych.lyngio.http

import io.ktor.client.engine.js.Js

actual fun getSystemHttpEngine(config: LyngHttpConfig): LyngHttpEngine = createKtorHttpEngine(Js, config)
