/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

@file:OptIn(ExperimentalForeignApi::class, ExperimentalForeignApi::class, ExperimentalForeignApi::class,
    ExperimentalForeignApi::class
)

package net.sergeych

import kotlinx.cinterop.*
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import kotlin.system.exitProcess

actual class ShellCommandExecutor() {
    actual fun executeCommand(command: String): CommandResult {
        val outputBuilder = StringBuilder()
        val errorBuilder = StringBuilder()

        val fp = popen(command, "r") ?: return CommandResult(
            exitCode = -1,
            output = "",
            error = "Failed to execute command"
        )

        val buffer = ByteArray(4096)
        while (true) {
            val bytesRead = buffer.usePinned { pinned ->
                fgets(pinned.addressOf(0), buffer.size.convert(), fp)
            }
            if (bytesRead == null) break
            outputBuilder.append(bytesRead.toKString())
        }

        val status = pclose(fp)
        val exitCode = if (status == 0) 0 else 1

        return CommandResult(
            exitCode = exitCode,
            output = outputBuilder.toString().trim(),
            error = errorBuilder.toString().trim()
        )
    }

    actual companion object {
        actual fun create(): ShellCommandExecutor = ShellCommandExecutor()
    }
}

actual fun exit(code: Int) {
    exitProcess(code)
}