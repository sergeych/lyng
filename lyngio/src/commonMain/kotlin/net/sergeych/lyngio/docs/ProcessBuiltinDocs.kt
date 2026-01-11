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

package net.sergeych.lyngio.docs

import net.sergeych.lyng.miniast.BuiltinDocRegistry
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.type

object ProcessBuiltinDocs {
    private var registered = false

    fun ensure() {
        if (registered) return
        BuiltinDocRegistry.module("lyng.io.process") {
            classDoc(
                name = "Process",
                doc = "Process execution and control."
            ) {
                method(
                    name = "execute",
                    doc = "Execute a process with arguments.",
                    params = listOf(ParamDoc("executable", type("lyng.String")), ParamDoc("args", type("lyng.List"))),
                    returns = type("RunningProcess"),
                    isStatic = true
                )
                method(
                    name = "shell",
                    doc = "Execute a command via system shell.",
                    params = listOf(ParamDoc("command", type("lyng.String"))),
                    returns = type("RunningProcess"),
                    isStatic = true
                )
            }

            classDoc(
                name = "Platform",
                doc = "Platform information."
            ) {
                method(
                    name = "details",
                    doc = "Get platform core details.",
                    returns = type("lyng.Map"),
                    isStatic = true
                )
                method(
                    name = "isSupported",
                    doc = "Check if processes are supported on this platform.",
                    returns = type("lyng.Bool"),
                    isStatic = true
                )
            }

            classDoc(
                name = "RunningProcess",
                doc = "Handle to a running process."
            ) {
                method(
                    name = "stdout",
                    doc = "Get standard output stream as a Flow of lines.",
                    returns = type("lyng.Flow")
                )
                method(
                    name = "stderr",
                    doc = "Get standard error stream as a Flow of lines.",
                    returns = type("lyng.Flow")
                )
                method(
                    name = "signal",
                    doc = "Send a signal to the process (e.g. 'SIGINT', 'SIGTERM', 'SIGKILL').",
                    params = listOf(ParamDoc("signal", type("lyng.String")))
                )
                method(
                    name = "waitFor",
                    doc = "Wait for the process to exit and return its exit code.",
                    returns = type("lyng.Int")
                )
                method(
                    name = "destroy",
                    doc = "Forcefully terminate the process."
                )
            }

            // Top-level exported constants
            valDoc(
                name = "Process",
                doc = "Process execution and control.",
                type = type("Process")
            )
            valDoc(
                name = "Platform",
                doc = "Platform information.",
                type = type("Platform")
            )
            valDoc(
                name = "RunningProcess",
                doc = "Handle to a running process.",
                type = type("RunningProcess")
            )
        }
        registered = true
    }
}
