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

@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

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