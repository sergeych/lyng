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

package net.sergeych

import kotlin.system.exitProcess

// Allow tests to override JVM exit behavior without terminating the whole VM.
// In production, this points to exitProcess; tests can replace it to throw.
@PublishedApi
internal var jvmExitImpl: (Int) -> Nothing = { code -> exitProcess(code) }

internal actual class CliPlatformShutdownHooks private constructor(
    private val shutdownHook: Thread?
) {
    actual fun uninstall() {
        val hook = shutdownHook ?: return
        runCatching {
            Runtime.getRuntime().removeShutdownHook(hook)
        }
    }

    actual companion object {
        actual fun install(runtime: CliExecutionRuntime): CliPlatformShutdownHooks {
            val hook = Thread(
                {
                    runtime.shutdownBlocking()
                },
                "lyng-cli-shutdown"
            )
            return runCatching {
                Runtime.getRuntime().addShutdownHook(hook)
                CliPlatformShutdownHooks(hook)
            }.getOrElse {
                CliPlatformShutdownHooks(null)
            }
        }
    }
}

actual fun exit(code: Int) {
    jvmExitImpl(code)
}
