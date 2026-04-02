package net.sergeych.lyngio.net

actual fun getSystemNetEngine(): LyngNetEngine = createNativeKtorNetEngine(
    isSupported = true,
    isTcpAvailable = true,
    isTcpServerAvailable = true,
    isUdpAvailable = true,
)
