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

package net.sergeych.lyng.io.process

import kotlinx.coroutines.flow.Flow
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.*
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.statement
import net.sergeych.lyngio.process.*
import net.sergeych.lyngio.process.security.ProcessAccessDeniedException
import net.sergeych.lyngio.process.security.ProcessAccessPolicy

/**
 * Install Lyng module `lyng.io.process` into the given scope's ImportManager.
 */
fun createProcessModule(policy: ProcessAccessPolicy, scope: Scope): Boolean =
    createProcessModule(policy, scope.importManager)

/** Same as [createProcessModule] but with explicit [ImportManager]. */
fun createProcessModule(policy: ProcessAccessPolicy, manager: ImportManager): Boolean {
    val name = "lyng.io.process"
    if (manager.packageNames.contains(name)) return false

    manager.addPackage(name) { module ->
        buildProcessModule(module, policy)
    }
    return true
}

private suspend fun buildProcessModule(module: ModuleScope, policy: ProcessAccessPolicy) {
    val runner = try {
        SecuredLyngProcessRunner(getSystemProcessRunner(), policy)
    } catch (e: Exception) {
        null
    }

    val runningProcessType = object : ObjClass("RunningProcess") {}
    
    runningProcessType.apply {
        addFnDoc(
            name = "stdout",
            doc = "Get standard output stream as a Flow of lines.",
            returns = type("lyng.Flow"),
            moduleName = module.packageName
        ) {
            val self = thisAs<ObjRunningProcess>()
            self.process.stdout.toLyngFlow(this)
        }
        addFnDoc(
            name = "stderr",
            doc = "Get standard error stream as a Flow of lines.",
            returns = type("lyng.Flow"),
            moduleName = module.packageName
        ) {
            val self = thisAs<ObjRunningProcess>()
            self.process.stderr.toLyngFlow(this)
        }
        addFnDoc(
            name = "signal",
            doc = "Send a signal to the process (e.g. 'SIGINT', 'SIGTERM', 'SIGKILL').",
            params = listOf(ParamDoc("signal", type("lyng.String"))),
            moduleName = module.packageName
        ) {
            processGuard {
                val sigStr = requireOnlyArg<ObjString>().value.uppercase()
                val sig = try {
                    ProcessSignal.valueOf(sigStr)
                } catch (e: Exception) {
                    try {
                        ProcessSignal.valueOf("SIG$sigStr")
                    } catch (e2: Exception) {
                        raiseIllegalArgument("Unknown signal: $sigStr")
                    }
                }
                thisAs<ObjRunningProcess>().process.sendSignal(sig)
                ObjVoid
            }
        }
        addFnDoc(
            name = "waitFor",
            doc = "Wait for the process to exit and return its exit code.",
            returns = type("lyng.Int"),
            moduleName = module.packageName
        ) {
            processGuard {
                thisAs<ObjRunningProcess>().process.waitFor().toObj()
            }
        }
        addFnDoc(
            name = "destroy",
            doc = "Forcefully terminate the process.",
            moduleName = module.packageName
        ) {
            thisAs<ObjRunningProcess>().process.destroy()
            ObjVoid
        }
    }

    val processType = object : ObjClass("Process") {}
    
    processType.apply {
        addClassFnDoc(
            name = "execute",
            doc = "Execute a process with arguments.",
            params = listOf(ParamDoc("executable", type("lyng.String")), ParamDoc("args", type("lyng.List"))),
            returns = type("RunningProcess"),
            moduleName = module.packageName
        ) {
            if (runner == null) raiseError("Processes are not supported on this platform")
            processGuard {
                val executable = requiredArg<ObjString>(0).value
                val args = requiredArg<ObjList>(1).list.map { it.toString() }
                val lp = runner.execute(executable, args)
                ObjRunningProcess(runningProcessType, lp)
            }
        }
        addClassFnDoc(
            name = "shell",
            doc = "Execute a command via system shell.",
            params = listOf(ParamDoc("command", type("lyng.String"))),
            returns = type("RunningProcess"),
            moduleName = module.packageName
        ) {
            if (runner == null) raiseError("Processes are not supported on this platform")
            processGuard {
                val command = requireOnlyArg<ObjString>().value
                val lp = runner.shell(command)
                ObjRunningProcess(runningProcessType, lp)
            }
        }
    }

    val platformType = object : ObjClass("Platform") {}
    
    platformType.apply {
        addClassFnDoc(
            name = "details",
            doc = "Get platform core details.",
            returns = type("lyng.Map"),
            moduleName = module.packageName
        ) {
            val d = getPlatformDetails()
            ObjMap(mutableMapOf(
                ObjString("name") to ObjString(d.name),
                ObjString("version") to ObjString(d.version),
                ObjString("arch") to ObjString(d.arch),
                ObjString("kernelVersion") to (d.kernelVersion?.toObj() ?: ObjNull)
            ))
        }
        addClassFnDoc(
            name = "isSupported",
            doc = "Check if processes are supported on this platform.",
            returns = type("lyng.Bool"),
            moduleName = module.packageName
        ) {
            isProcessSupported().toObj()
        }
    }

    module.addConstDoc(
        name = "Process",
        value = processType,
        doc = "Process execution and control.",
        type = type("Process"),
        moduleName = module.packageName
    )
    module.addConstDoc(
        name = "Platform",
        value = platformType,
        doc = "Platform information.",
        type = type("Platform"),
        moduleName = module.packageName
    )
    module.addConstDoc(
        name = "RunningProcess",
        value = runningProcessType,
        doc = "Handle to a running process.",
        type = type("RunningProcess"),
        moduleName = module.packageName
    )
}

class ObjRunningProcess(
    override val objClass: ObjClass,
    val process: LyngProcess
) : Obj() {
    override fun toString(): String = "RunningProcess($process)"
}

private suspend inline fun Scope.processGuard(crossinline block: suspend () -> Obj): Obj {
    return try {
        block()
    } catch (e: ProcessAccessDeniedException) {
        raiseError(ObjIllegalOperationException(this, e.reasonDetail ?: "process access denied"))
    } catch (e: Exception) {
        raiseError(ObjIllegalOperationException(this, e.message ?: "process error"))
    }
}

private fun Flow<String>.toLyngFlow(flowScope: Scope): ObjFlow {
    val producer = statement {
        val builder = (this as? net.sergeych.lyng.ClosureScope)?.callScope?.thisObj as? ObjFlowBuilder
            ?: this.thisObj as? ObjFlowBuilder

        this@toLyngFlow.collect {
            try {
                builder?.output?.send(ObjString(it))
            } catch (e: Exception) {
                // Channel closed or other error, stop collecting
                return@collect
            }
        }
        ObjVoid
    }
    return ObjFlow(producer, flowScope)
}
