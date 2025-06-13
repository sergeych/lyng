package net.sergeych

// Alternative implementation for native targets
actual class ShellCommandExecutor() {
    actual fun executeCommand(command: String): CommandResult {
        val process = ProcessBuilder("/bin/sh", "-c", command).start()
        val exitCode = process.waitFor()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()

        return CommandResult(
            exitCode = exitCode,
            output = output.trim(),
            error = error.trim()
        )
    }

    actual companion object {
        actual fun create(): ShellCommandExecutor = ShellCommandExecutor()
    }
}