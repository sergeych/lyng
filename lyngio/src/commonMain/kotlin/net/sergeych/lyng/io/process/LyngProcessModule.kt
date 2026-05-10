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

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Source
import net.sergeych.lyng.miniast.*
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.requireScope
import net.sergeych.lyngio.process.*
import net.sergeych.lyngio.process.security.ProcessAccessDeniedException
import net.sergeych.lyngio.process.security.ProcessAccessPolicy
import net.sergeych.lyngio.stdlib_included.processLyng
import kotlin.Boolean
import kotlin.Exception
import kotlin.Int
import kotlin.String
import kotlin.also
import kotlin.apply
import kotlin.collections.joinToString
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.toTypedArray
import kotlin.let
import kotlin.takeIf
import kotlin.text.isNotBlank
import kotlin.text.uppercase
import kotlin.to

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
    module.eval(Source("lyng.io.process", processLyng))

    val runner = try {
        SecuredLyngProcessRunner(getSystemProcessRunner(), policy)
    } catch (e: Exception) {
        null
    }

    val runningProcessType = object : ObjClass("RunningProcess") {}
    val commandRunType = object : ObjClass("CommandRun") {}
    
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

    commandRunType.apply {
        addPropertyDoc(
            name = "command",
            doc = "Original shell command or argv-style command display text.",
            type = type("lyng.String"),
            moduleName = module.packageName,
            getter = { ObjString(thisAs<ObjCommandRun>().command) }
        )
        addPropertyDoc(
            name = "out",
            doc = "Captured standard output as a string. Captures both stdout and stderr concurrently.",
            type = type("lyng.String"),
            moduleName = module.packageName,
            getter = { ObjString(thisAs<ObjCommandRun>().captureAll().stdoutText) }
        )
        addPropertyDoc(
            name = "err",
            doc = "Captured standard error as a string. Captures both stdout and stderr concurrently.",
            type = type("lyng.String"),
            moduleName = module.packageName,
            getter = { ObjString(thisAs<ObjCommandRun>().captureAll().stderrText) }
        )
        addPropertyDoc(
            name = "lines",
            doc = "Streaming standard output lines. Use this for large output instead of `out`.",
            type = type("lyng.Flow"),
            moduleName = module.packageName,
            getter = { thisAs<ObjCommandRun>().process.stdout.toLyngFlow(this) }
        )
        addPropertyDoc(
            name = "errorLines",
            doc = "Streaming standard error lines. Use this for large stderr output instead of `err`.",
            type = type("lyng.Flow"),
            moduleName = module.packageName,
            getter = { thisAs<ObjCommandRun>().process.stderr.toLyngFlow(this) }
        )
        addPropertyDoc(
            name = "code",
            doc = "Exit code after `wait`, `check`, `out`, or `err`; otherwise null.",
            type = type("lyng.Int?"),
            moduleName = module.packageName,
            getter = {
                val code = thisAs<ObjCommandRun>().knownExitCode()
                code?.toObj() ?: ObjNull
            }
        )
        addPropertyDoc(
            name = "ok",
            doc = "True if the known exit code is zero, false if non-zero, or null before the process is known to have exited.",
            type = type("lyng.Bool?"),
            moduleName = module.packageName,
            getter = {
                val code = thisAs<ObjCommandRun>().knownExitCode()
                code?.let { (it == 0).toObj() } ?: ObjNull
            }
        )
        addFnDoc(
            name = "wait",
            doc = "Wait for the process to exit and return its exit code.",
            returns = type("lyng.Int"),
            moduleName = module.packageName
        ) {
            thisAs<ObjCommandRun>().waitFor().toObj()
        }
        addFnDoc(
            name = "check",
            doc = "Capture output, wait for completion, and fail if the exit code is non-zero.",
            returns = type("CommandRun"),
            moduleName = module.packageName
        ) {
            val command = thisAs<ObjCommandRun>()
            val captured = command.captureAll()
            if (captured.exitCode != 0) {
                val detail = captured.stderrText.takeIf { it.isNotBlank() } ?: captured.stdoutText
                val suffix = detail.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
                raiseError("command failed with exit code ${captured.exitCode}: ${command.command}$suffix")
            }
            command
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
    module.addConstDoc(
        name = "CommandRun",
        value = commandRunType,
        doc = "Shell-script friendly handle for a running command.",
        type = type("CommandRun"),
        moduleName = module.packageName
    )

    module.addFnDoc(
        "sh",
        doc = "Run a command via the system shell and return an active command handle.",
        params = listOf(ParamDoc("command", type("lyng.String"))),
        returns = type("CommandRun"),
        moduleName = module.packageName
    ) {
        if (runner == null) raiseError("Processes are not supported on this platform")
        processGuard {
            val command = requireOnlyArg<ObjString>().value
            ObjCommandRun(commandRunType, command, runner.shell(command))
        }
    }
    module.addFnDoc(
        "exec",
        doc = "Run an executable with argv-style arguments and return an active command handle.",
        params = listOf(ParamDoc("executable", type("lyng.String")), ParamDoc("args", type("lyng.List"))),
        returns = type("CommandRun"),
        moduleName = module.packageName
    ) {
        if (runner == null) raiseError("Processes are not supported on this platform")
        processGuard {
            if (args.list.size > 2) {
                raiseError("Expected at most 2 arguments, got ${args.list.size}")
            }
            val executable = requiredArg<ObjString>(0).value
            val rawArgs = if (args.list.size >= 2) requiredArg<ObjList>(1) else ObjList(mutableListOf())
            val argv = rawArgs.list.map { (it as? ObjString)?.value ?: it.toString() }
            ObjCommandRun(commandRunType, listOf(executable, *argv.toTypedArray()).joinToString(" "), runner.execute(executable, argv))
        }
    }
}

class ObjRunningProcess(
    override val objClass: ObjClass,
    val process: LyngProcess
) : Obj() {
    override fun toString(): String = "RunningProcess($process)"
}

private data class CapturedCommandOutput(
    val exitCode: Int,
    val stdoutText: String,
    val stderrText: String
)

private class ObjCommandRun(
    override val objClass: ObjClass,
    val command: String,
    val process: LyngProcess
) : Obj() {
    private val captureMutex = Mutex()
    private var captured: CapturedCommandOutput? = null
    private var exitCode: Int? = null

    fun knownExitCode(): Int? = captured?.exitCode ?: exitCode

    suspend fun waitFor(): Int {
        captured?.let { return it.exitCode }
        exitCode?.let { return it }
        return process.waitFor().also { exitCode = it }
    }

    suspend fun captureAll(): CapturedCommandOutput = captureMutex.withLock {
        captured?.let { return@withLock it }
        coroutineScope {
            val stdout = async { process.stdout.toList().joinToString("\n") }
            val stderr = async { process.stderr.toList().joinToString("\n") }
            val code = async { process.waitFor() }
            CapturedCommandOutput(
                exitCode = code.await(),
                stdoutText = stdout.await(),
                stderrText = stderr.await()
            ).also {
                captured = it
                exitCode = it.exitCode
            }
        }
    }

    override fun toString(): String = "CommandRun($command)"
}

private suspend inline fun ScopeFacade.processGuard(crossinline block: suspend () -> Obj): Obj {
    return try {
        block()
    } catch (e: ProcessAccessDeniedException) {
        raiseIllegalOperation(e.reasonDetail ?: "process access denied")
    } catch (e: Exception) {
        raiseIllegalOperation(e.message ?: "process error")
    }
}

private fun Flow<String>.toLyngFlow(flowScope: ScopeFacade): ObjFlow {
    val producer = net.sergeych.lyng.obj.ObjExternCallable.fromBridge {
        val scope = requireScope()
        val builder = (scope as? net.sergeych.lyng.BytecodeClosureScope)?.callScope?.thisObj as? ObjFlowBuilder
            ?: scope.thisObj as? ObjFlowBuilder

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
    return ObjFlow(producer, flowScope.requireScope())
}
