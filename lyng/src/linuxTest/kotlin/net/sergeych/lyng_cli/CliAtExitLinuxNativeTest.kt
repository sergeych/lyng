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
import platform.posix.SIGTERM
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class CliAtExitLinuxNativeTest {
    @Test
    fun atExitRunsOnSigtermForNativeCli() {
        val executable = getenv("LYNG_CLI_NATIVE_BIN")?.toKString()
            ?: error("LYNG_CLI_NATIVE_BIN is not set")
        val fs = FileSystem.SYSTEM
        val tempDir = "/tmp/lyng_cli_native_${getpid()}_${kotlin.random.Random.nextInt()}".toPath()
        val scriptPath = tempDir / "sigterm.lyng"
        val stdoutPath = tempDir / "stdout.txt"
        val stderrPath = tempDir / "stderr.txt"

        fs.createDirectories(tempDir)
        try {
            fs.write(scriptPath) {
                writeUtf8(
                    """
                    atExit {
                        println("cleanup-native")
                    }
                    while(true) {
                        yield()
                    }
                    """.trimIndent()
                )
            }

            val pid = launchCli(executable, scriptPath, stdoutPath, stderrPath)
            usleep(300_000u)
            assertEquals(0, kill(pid, SIGTERM), "failed to send SIGTERM")

            val status = waitForPid(pid)
            val exitCode = if ((status and 0x7f) == 0) (status shr 8) and 0xff else -1
            val stdout = readUtf8IfExists(fs, stdoutPath)
            val stderr = readUtf8IfExists(fs, stderrPath)

            assertEquals(143, exitCode, "unexpected native CLI exit status; stderr=$stderr")
            assertTrue(stdout.contains("cleanup-native"), "stdout did not contain cleanup marker. stdout=$stdout stderr=$stderr")
        } finally {
            fs.deleteRecursively(tempDir, mustExist = false)
        }
    }

    private fun readUtf8IfExists(fs: FileSystem, path: Path): String {
        return if (fs.exists(path)) {
            fs.read(path) { readUtf8() }
        } else {
            ""
        }
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
        stderrPath: Path
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
