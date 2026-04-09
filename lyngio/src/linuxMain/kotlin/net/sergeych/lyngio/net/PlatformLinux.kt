package net.sergeych.lyngio.net

private val systemNetEngine: LyngNetEngine = createNativeKtorNetEngine(
    isSupported = true,
    isTcpAvailable = true,
    isTcpServerAvailable = true,
    isUdpAvailable = true,
)

actual fun getSystemNetEngine(): LyngNetEngine = systemNetEngine

actual fun shutdownSystemNetEngine() {
    shutdownNativeKtorNetEngine(systemNetEngine)
}
