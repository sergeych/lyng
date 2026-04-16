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

package net.sergeych.lyng_cli

import kotlinx.cinterop.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SIGKILL
import platform.posix.SIGSEGV
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.execvp
import platform.posix.fork
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.kill
import platform.posix.open
import platform.posix.usleep
import platform.posix.waitpid
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class CliWebSocketNativeRegressionTest {
    @Test
    fun releaseCliDoesNotSegfaultOnConcurrentWebSocketClients() {
        val executable = getenv("LYNG_CLI_NATIVE_RELEASE_BIN")?.toKString()
            ?: error("LYNG_CLI_NATIVE_RELEASE_BIN is not set")
        val fs = FileSystem.SYSTEM
        val repoRoot = ascend(executable.toPath(), 6)
        val scriptPath = repoRoot / "bugs" / "ws-segfault.lyng"
        check(fs.exists(scriptPath)) { "bug repro script not found at $scriptPath" }

        val tempDir = "/tmp/lyng_ws_native_${getpid()}_${kotlin.random.Random.nextInt()}".toPath()
        val stdoutPath = tempDir / "stdout.txt"
        val stderrPath = tempDir / "stderr.txt"

        fs.createDirectories(tempDir)
        try {
            val pid = launchCli(executable, scriptPath, stdoutPath, stderrPath)
            usleep(5_000_000u)

            if (kill(pid, 0) == 0) {
                kill(pid, SIGKILL)
            }

            val status = waitForPid(pid)
            val termSignal = status and 0x7f
            val stdout = readUtf8IfExists(fs, stdoutPath)
            val stderr = readUtf8IfExists(fs, stderrPath)
            val allOutput = "$stdout\n$stderr"

            assertFalse(termSignal == SIGSEGV, "native CLI crashed with SIGSEGV. Output:\n$allOutput")
            assertTrue(
                stdout.lineSequence().count { it == "test send to ws://127.0.0.1:9998... OK" } == 2,
                "expected both websocket clients to finish. Output:\n$allOutput"
            )
            assertFalse(allOutput.contains("Segmentation fault"), "process output reported a segmentation fault:\n$allOutput")
        } finally {
            fs.deleteRecursively(tempDir, mustExist = false)
        }
    }

    private fun ascend(path: Path, levels: Int): Path {
        var current = path
        repeat(levels) {
            current = current.parent ?: error("cannot ascend $levels levels from $path")
        }
        return current
    }

    private fun readUtf8IfExists(fs: FileSystem, path: Path): String {
        return if (fs.exists(path)) fs.read(path) { readUtf8() } else ""
    }

    private fun waitForPid(pid: Int): Int = memScoped {
        val status = alloc<IntVar>()
        val waited = waitpid(pid, status.ptr, 0)
        check(waited == pid) { "waitpid failed for $pid" }
        status.value
    }

    private fun launchCli(
        executable: String,
        scriptPath: Path,
        stdoutPath: Path,
        stderrPath: Path,
    ): Int = memScoped {
        val pid = fork()
        check(pid >= 0) { "fork failed" }
        if (pid == 0) {
            val stdoutFd = open(stdoutPath.toString(), O_WRONLY or O_CREAT or O_TRUNC, 0x1A4)
            val stderrFd = open(stderrPath.toString(), O_WRONLY or O_CREAT or O_TRUNC, 0x1A4)
            if (stdoutFd < 0 || stderrFd < 0) {
                _exit(2)
            }
            dup2(stdoutFd, 1)
            dup2(stderrFd, 2)
            close(stdoutFd)
            close(stderrFd)

            val argv = allocArray<CPointerVar<ByteVar>>(3)
            argv[0] = executable.cstr.ptr
            argv[1] = scriptPath.toString().cstr.ptr
            argv[2] = null
            execvp(executable, argv)
            _exit(127)
        }
        pid
    }
}
