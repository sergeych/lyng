/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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

package net.sergeych.lyng.bytecode

import java.io.File
import java.time.Instant

private val vmIterLogFilePath: String =
    System.getenv("LYNG_VM_DEBUG_LOG")
        ?.takeIf { it.isNotBlank() }
        ?: "/tmp/lyng_vm_iter_debug.log"

private val vmIterLogLock = Any()

internal actual fun vmIterDebug(message: String, error: Throwable?) {
    runCatching {
        val line = buildString {
            append(Instant.now().toString())
            append(" [vm-iter] ")
            append(message)
            append('\n')
            if (error != null) {
                append(error.stackTraceToString())
                append('\n')
            }
        }
        synchronized(vmIterLogLock) {
            File(vmIterLogFilePath).appendText(line)
        }
    }
}
