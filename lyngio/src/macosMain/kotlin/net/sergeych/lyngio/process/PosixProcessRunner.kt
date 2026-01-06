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

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
internal class NativeLyngProcess(
    private val pid: pid_t,
    private val stdoutFd: Int,
    private val stderrFd: Int
) : LyngProcess {
    override val stdout: Flow<String> = createPipeFlow(stdoutFd)
    override val stderr: Flow<String> = createPipeFlow(stderrFd)

    override suspend fun sendSignal(signal: ProcessSignal) {
        val sig = when (signal) {
            ProcessSignal.SIGINT -> SIGINT
            ProcessSignal.SIGTERM -> SIGTERM
            ProcessSignal.SIGKILL -> SIGKILL
        }
        if (kill(pid, sig) != 0) {
            throw RuntimeException("Failed to send signal $signal to process $pid: ${strerror(errno)?.toKString()}")
        }
    }

    override suspend fun waitFor(): Int = withContext(Dispatchers.Default) {
        memScoped {
            val status = alloc<IntVar>()
            if (waitpid(pid, status.ptr, 0) == -1) {
                throw RuntimeException("Failed to wait for process $pid: ${strerror(errno)?.toKString()}")
            }
            val s = status.value
            if ((s and 0x7f) == 0) (s shr 8) and 0xff else -1
        }
    }

    override fun destroy() {
        kill(pid, SIGKILL)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createPipeFlow(fd: Int): Flow<String> = flow {
    val buffer = ByteArray(4096)
    val lineBuffer = StringBuilder()
    
    try {
        while (true) {
            val bytesRead = buffer.usePinned { pinned ->
                read(fd, pinned.addressOf(0), buffer.size.toULong())
            }
            
            if (bytesRead <= 0L) break
            
            val text = buffer.decodeToString(endIndex = bytesRead.toInt())
            lineBuffer.append(text)
            
            var newlineIdx = lineBuffer.indexOf('\n')
            while (newlineIdx != -1) {
                val line = lineBuffer.substring(0, newlineIdx)
                emit(line)
                lineBuffer.deleteRange(0, newlineIdx + 1)
                newlineIdx = lineBuffer.indexOf('\n')
            }
        }
        if (lineBuffer.isNotEmpty()) {
            emit(lineBuffer.toString())
        }
    } finally {
        close(fd)
    }
}.flowOn(Dispatchers.Default)

@OptIn(ExperimentalForeignApi::class)
object PosixProcessRunner : LyngProcessRunner {
    override suspend fun execute(executable: String, args: List<String>): LyngProcess = withContext(Dispatchers.Default) {
        memScoped {
            val pipeStdout = allocArray<IntVar>(2)
            val pipeStderr = allocArray<IntVar>(2)
            
            if (pipe(pipeStdout) != 0) throw RuntimeException("Failed to create stdout pipe")
            if (pipe(pipeStderr) != 0) {
                close(pipeStdout[0])
                close(pipeStdout[1])
                throw RuntimeException("Failed to create stderr pipe")
            }
            
            val pid = fork()
            if (pid == -1) {
                close(pipeStdout[0])
                close(pipeStdout[1])
                close(pipeStderr[0])
                close(pipeStderr[1])
                throw RuntimeException("Failed to fork: ${strerror(errno)?.toKString()}")
            }
            
            if (pid == 0) {
                // Child process
                dup2(pipeStdout[1], 1)
                dup2(pipeStderr[1], 2)
                
                close(pipeStdout[0])
                close(pipeStdout[1])
                close(pipeStderr[0])
                close(pipeStderr[1])
                
                val argv = allocArray<CPointerVar<ByteVar>>(args.size + 2)
                argv[0] = executable.cstr.ptr
                for (i in args.indices) {
                    argv[i + 1] = args[i].cstr.ptr
                }
                argv[args.size + 1] = null
                
                execvp(executable, argv)
                
                // If we are here, exec failed
                _exit(1)
            }
            
            // Parent process
            close(pipeStdout[1])
            close(pipeStderr[1])
            
            NativeLyngProcess(pid, pipeStdout[0], pipeStderr[0])
        }
    }

    override suspend fun shell(command: String): LyngProcess {
        return execute("/bin/sh", listOf("-c", command))
    }
}
