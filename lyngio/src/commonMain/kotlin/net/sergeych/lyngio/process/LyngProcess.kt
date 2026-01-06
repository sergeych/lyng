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

import kotlinx.coroutines.flow.Flow
import net.sergeych.lyngio.process.security.ProcessAccessOp
import net.sergeych.lyngio.process.security.ProcessAccessPolicy

/**
 * Common signals for process control.
 */
enum class ProcessSignal {
    SIGINT, SIGTERM, SIGKILL
}

/**
 * Multiplatform process representation.
 */
interface LyngProcess {
    /**
     * Standard output stream as a flow of strings (lines).
     */
    val stdout: Flow<String>

    /**
     * Standard error stream as a flow of strings (lines).
     */
    val stderr: Flow<String>

    /**
     * Send a signal to the process.
     * Throws exception if signals are not supported on the platform or for this process.
     */
    suspend fun sendSignal(signal: ProcessSignal)

    /**
     * Wait for the process to exit and return the exit code.
     */
    suspend fun waitFor(): Int

    /**
     * Forcefully terminate the process.
     */
    fun destroy()
}

/**
 * Interface for running processes.
 */
interface LyngProcessRunner {
    /**
     * Execute a process with the given executable and arguments.
     */
    suspend fun execute(executable: String, args: List<String>): LyngProcess

    /**
     * Execute a command via the platform's default shell.
     */
    suspend fun shell(command: String): LyngProcess
}

/**
 * Secured implementation of [LyngProcessRunner] that checks against a [ProcessAccessPolicy].
 */
class SecuredLyngProcessRunner(
    private val runner: LyngProcessRunner,
    private val policy: ProcessAccessPolicy
) : LyngProcessRunner {
    override suspend fun execute(executable: String, args: List<String>): LyngProcess {
        policy.require(ProcessAccessOp.Execute(executable, args))
        return runner.execute(executable, args)
    }

    override suspend fun shell(command: String): LyngProcess {
        policy.require(ProcessAccessOp.Shell(command))
        return runner.shell(command)
    }
}
