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

package net.sergeych.lyngio.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

actual fun getPlatformDetails(): PlatformDetails {
    val osName = System.getProperty("os.name")
    return PlatformDetails(
        name = osName,
        version = System.getProperty("os.version"),
        arch = System.getProperty("os.arch"),
        kernelVersion = if (osName.lowercase().contains("linux")) {
            System.getProperty("os.version")
        } else null
    )
}

actual fun isProcessSupported(): Boolean = true

actual fun getSystemProcessRunner(): LyngProcessRunner = JvmProcessRunner

object JvmProcessRunner : LyngProcessRunner {
    override suspend fun execute(executable: String, args: List<String>): LyngProcess {
        val process = ProcessBuilder(listOf(executable) + args)
            .start()
        return JvmLyngProcess(process)
    }

    override suspend fun shell(command: String): LyngProcess {
        val os = System.getProperty("os.name").lowercase()
        val shellCmd = if (os.contains("win")) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }
        val process = ProcessBuilder(shellCmd)
            .start()
        return JvmLyngProcess(process)
    }
}

class JvmLyngProcess(private val process: Process) : LyngProcess {
    override val stdout: Flow<String> = flow {
        val reader = process.inputStream.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            emit(line)
        }
    }

    override val stderr: Flow<String> = flow {
        val reader = process.errorStream.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            emit(line)
        }
    }

    override suspend fun sendSignal(signal: ProcessSignal) {
        when (signal) {
            ProcessSignal.SIGINT -> {
                // SIGINT is hard on JVM without native calls or external 'kill'
                val os = System.getProperty("os.name").lowercase()
                if (os.contains("win")) {
                    throw UnsupportedOperationException("SIGINT not supported on Windows JVM")
                } else {
                    // Try to use kill -2 <pid>
                    try {
                        val pid = process.pid()
                        Runtime.getRuntime().exec(arrayOf("kill", "-2", pid.toString())).waitFor()
                    } catch (e: Exception) {
                        throw UnsupportedOperationException("Failed to send SIGINT: ${e.message}")
                    }
                }
            }
            ProcessSignal.SIGTERM -> process.destroy()
            ProcessSignal.SIGKILL -> process.destroyForcibly()
        }
    }

    override suspend fun waitFor(): Int = withContext(Dispatchers.IO) {
        process.waitFor()
    }

    override fun destroy() {
        process.destroy()
    }
}
