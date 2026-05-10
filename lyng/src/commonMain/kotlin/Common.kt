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

package net.sergeych

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.LyngVersion
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Script
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.Source
import net.sergeych.lyng.asFacade
import net.sergeych.lyng.io.console.createConsoleModule
import net.sergeych.lyng.io.db.createDbModule
import net.sergeych.lyng.io.db.jdbc.createJdbcModule
import net.sergeych.lyng.io.db.sqlite.createSqliteModule
import net.sergeych.lyng.io.fs.createFs
import net.sergeych.lyng.io.html.createHtmlModule
import net.sergeych.lyng.io.http.createHttpModule
import net.sergeych.lyng.io.http.server.createHttpServerModule
import net.sergeych.lyng.io.net.createNetModule
import net.sergeych.lyng.io.process.createProcessModule
import net.sergeych.lyng.io.ws.createWsModule
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyngio.console.security.PermitAllConsoleAccessPolicy
import net.sergeych.lyngio.fs.security.PermitAllAccessPolicy
import net.sergeych.lyngio.http.security.PermitAllHttpAccessPolicy
import net.sergeych.lyngio.net.security.PermitAllNetAccessPolicy
import net.sergeych.lyngio.net.shutdownSystemNetEngine
import net.sergeych.lyngio.process.security.PermitAllProcessAccessPolicy
import net.sergeych.lyngio.ws.security.PermitAllWsAccessPolicy
import net.sergeych.mp_tools.globalDefer
import okio.*
import okio.Path.Companion.toPath

// common code

expect fun exit(code: Int)

internal expect class CliPlatformShutdownHooks {
    fun uninstall()

    companion object {
        fun install(runtime: CliExecutionRuntime): CliPlatformShutdownHooks
    }
}

expect class ShellCommandExecutor {
    fun executeCommand(command: String): CommandResult

    companion object {
        fun create(): ShellCommandExecutor
    }
}

data class CommandResult(
    val exitCode: Int,
    val output: String,
    val error: String
)

private const val cliBuiltinsDeclarations = """
extern fun atExit(append: Bool=true, handler: ()->Void)
"""

private class CliExitRequested(val code: Int) : RuntimeException("CLI exit requested: $code")

internal class CliExecutionRuntime(
    private val session: EvalSession,
    private val rootScope: Scope
) {
    private val shutdownMutex = Mutex()
    private var shutdownStarted = false
    private val exitHandlers = mutableListOf<Obj>()

    fun registerAtExit(handler: Obj, append: Boolean) {
        if (append) {
            exitHandlers += handler
        } else {
            exitHandlers.add(0, handler)
        }
    }

    suspend fun shutdown() {
        shutdownMutex.withLock {
            if (shutdownStarted) return
            shutdownStarted = true
        }
        val handlers = exitHandlers.toList()
        val facade = rootScope.asFacade()
        for (handler in handlers) {
            runCatching {
                facade.call(handler)
            }
        }
        session.cancel()
        shutdownSystemNetEngine()
        session.join()
    }

    fun shutdownBlocking() {
        runBlocking {
            shutdown()
        }
    }
}

private val baseCliImportManagerDefer = globalDefer {
    val manager = Script.defaultImportManager.copy().apply {
        installCliModules(this)
    }
    manager.newStdScope()
    manager
}

private fun ImportManager.invalidateCliModuleCaches() {
    invalidatePackageCache("lyng.io.fs")
    invalidatePackageCache("lyng.io.console")
    invalidatePackageCache("lyng.io.db.jdbc")
    invalidatePackageCache("lyng.io.db.sqlite")
    invalidatePackageCache("lyng.io.html")
    invalidatePackageCache("lyng.io.http")
    invalidatePackageCache("lyng.io.http.server")
    invalidatePackageCache("lyng.io.process")
    invalidatePackageCache("lyng.io.ws")
    invalidatePackageCache("lyng.io.net")
}

val baseScopeDefer = globalDefer {
    baseCliImportManagerDefer.await().copy().apply {
        invalidateCliModuleCaches()
    }.newStdScope().apply {
        installCliBuiltins()
        installCliDeclarations()
        addConst("ARGV", ObjList(mutableListOf()))
    }
}

private suspend fun Scope.installCliDeclarations() {
    eval(Source("<cli-builtins>", cliBuiltinsDeclarations))
}

private fun Scope.installCliBuiltins(runtime: CliExecutionRuntime? = null) {
    addFn("exit") {
        val code = requireOnlyArg<ObjInt>().toInt()
        if (runtime == null) {
            exit(code)
        }
        throw CliExitRequested(code)
    }
    addFn("atExit") {
        if (runtime == null) {
            raiseIllegalState("atExit is only available while running a CLI script")
        }
        if (args.list.size > 2) {
            raiseError("Expected at most 2 positional arguments, got ${args.list.size}")
        }
        var append = true
        var appendSet = false
        var handler: Obj? = null

        when (args.list.size) {
            1 -> {
                val only = args.list[0]
                if (only.isInstanceOf("Callable")) {
                    handler = only
                } else {
                    append = only.toBool()
                    appendSet = true
                }
            }
            2 -> {
                append = args.list[0].toBool()
                appendSet = true
                handler = args.list[1]
            }
        }

        for ((name, value) in args.named) {
            when (name) {
                "append" -> {
                    if (appendSet) {
                        raiseIllegalArgument("argument 'append' is already set")
                    }
                    append = value.toBool()
                    appendSet = true
                }
                "handler" -> {
                    if (handler != null) {
                        raiseIllegalArgument("argument 'handler' is already set")
                    }
                    handler = value
                }
                else -> raiseIllegalArgument("unknown argument '$name'")
            }
        }

        val handlerValue = handler ?: raiseError("argument 'handler' is required")
        if (!handlerValue.isInstanceOf("Callable")) {
            raiseClassCastError("Expected handler to be callable")
        }
        runtime.registerAtExit(handlerValue, append)
        ObjVoid
    }
}

private fun installCliModules(manager: ImportManager) {
    // Scripts still need to import the modules they use explicitly.
    createFs(PermitAllAccessPolicy, manager)
    createConsoleModule(PermitAllConsoleAccessPolicy, manager)
    createDbModule(manager)
    createJdbcModule(manager)
    createSqliteModule(manager)
    createHtmlModule(manager)
    createHttpModule(PermitAllHttpAccessPolicy, manager)
    createHttpServerModule(PermitAllNetAccessPolicy, manager)
    createProcessModule(PermitAllProcessAccessPolicy, manager)
    createWsModule(PermitAllWsAccessPolicy, manager)
    createNetModule(PermitAllNetAccessPolicy, manager)
}

private data class LocalCliModule(
    val packageName: String,
    val source: Source
)

private fun readUtf8(path: Path): String =
    FileSystem.SYSTEM.source(path).use { fileSource ->
        fileSource.buffer().use { bs ->
            bs.readUtf8()
        }
    }

private fun stripShebang(text: String): String {
    if (!text.startsWith("#!")) return text
    val pos = text.indexOf('\n')
    return if (pos >= 0) text.substring(pos + 1) else ""
}

private fun extractDeclaredPackageNameOrNull(source: Source): String? {
    for (line in source.lines) {
        if (line.isBlank()) continue
        return if (line.startsWith("package ")) {
            line.substring(8).trim()
        } else {
            null
        }
    }
    return null
}

private fun canonicalPath(path: Path): Path = FileSystem.SYSTEM.canonicalize(path)

private fun relativeModuleName(rootDir: Path, file: Path): String {
    val rootText = rootDir.toString().trimEnd('/', '\\')
    val fileText = file.toString()
    val prefix = "$rootText/"
    if (!fileText.startsWith(prefix)) {
        throw ScriptError(Pos.builtIn, "local import root mismatch: $fileText is not under $rootText")
    }
    val relative = fileText.removePrefix(prefix)
    val modulePath = relative.removeSuffix(".lyng")
    return modulePath
        .split('/', '\\')
        .filter { it.isNotEmpty() }
        .joinToString(".")
}

private fun scanLyngFiles(rootDir: Path): List<Path> {
    val system = FileSystem.SYSTEM
    val pending = ArrayDeque<Path>()
    val visited = linkedSetOf<String>()
    val files = mutableListOf<Path>()
    pending.add(rootDir)
    while (pending.isNotEmpty()) {
        val dir = pending.removeLast()
        val canonicalDir = canonicalPath(dir)
        if (!visited.add(canonicalDir.toString())) continue
        val children = try {
            system.list(canonicalDir)
        } catch (_: Exception) {
            continue
        }
        for (child in children) {
            val meta = try {
                system.metadata(child)
            } catch (_: Exception) {
                continue
            }
            when {
                meta.isDirectory -> pending.add(child)
                child.name.endsWith(".lyng") -> {
                    val canonicalFile = try {
                        canonicalPath(child)
                    } catch (_: Exception) {
                        continue
                    }
                    files += canonicalFile
                }
            }
        }
    }
    return files
}

private fun discoverLocalCliModules(entryFile: Path): List<LocalCliModule> {
    val rootDir = entryFile.parent ?: ".".toPath()
    val seenPackages = linkedMapOf<String, Path>()
    return scanLyngFiles(rootDir)
        .asSequence()
        .filter { it != entryFile }
        .map { file ->
            val text = stripShebang(readUtf8(file))
            val source = Source(file.toString(), text)
            val expectedPackage = relativeModuleName(rootDir, file)
            val declaredPackage = extractDeclaredPackageNameOrNull(source)
            if (declaredPackage != null && declaredPackage != expectedPackage) {
                throw ScriptError(
                    source.startPos,
                    "local module package mismatch: expected '$expectedPackage' for ${file.toString()} but found '$declaredPackage'"
                )
            }
            val packageName = declaredPackage ?: expectedPackage
            val previous = seenPackages[packageName]
            if (previous != null) {
                throw ScriptError(
                    source.startPos,
                    "duplicate local module '$packageName': ${previous.toString()} and ${file.toString()}"
                )
            }
            seenPackages[packageName] = file
            LocalCliModule(packageName, source)
        }
        .toList()
}

private fun registerLocalCliModules(manager: ImportManager, modules: List<LocalCliModule>) {
    for (module in modules) {
        manager.addPackage(module.packageName) { scope ->
            scope.eval(module.source)
        }
    }
}

private suspend fun ImportManager.newCliScope(argv: List<String>): Scope =
    newStdScope().apply {
        installCliBuiltins()
        installCliDeclarations()
        addConst("ARGV", ObjList(argv.map { ObjString(it) }.toMutableList()))
    }

internal suspend fun newCliScope(argv: List<String>, entryFileName: String? = null): Scope {
    val baseManager = baseCliImportManagerDefer.await().copy().apply {
        invalidateCliModuleCaches()
    }
    if (entryFileName == null) {
        return baseManager.newCliScope(argv)
    }
    val entryFile = canonicalPath(entryFileName.toPath())
    val localModules = discoverLocalCliModules(entryFile)
    if (localModules.isEmpty()) {
        return baseManager.newCliScope(argv)
    }
    registerLocalCliModules(baseManager, localModules)
    return baseManager.newCliScope(argv)
}

fun runMain(args: Array<String>) {
    // Fast paths for legacy/positional script execution that should work without requiring explicit options
    if (args.isNotEmpty()) {
        // Support: jyng -- -file.lyng <args>
        if (args.size >= 2 && args[0] == "--") {
            executeFileWithArgs(args[1], args.drop(2))
            return
        }
        // Support: jyng script.lyng <args> (when first token is not an option and not a subcommand name)
        if (!args[0].startsWith('-') && args[0] != "fmt") {
            executeFileWithArgs(args[0], args.drop(1))
            return
        }
    }

    // Delegate all other parsing and dispatching to Clikt with proper subcommands.
    Lyng { runBlocking { it() } }
        .subcommands(Fmt())
        .main(args)
}

private class Fmt : CoreCliktCommand(name = "fmt") {
    private val checkOnly by option("--check", help = "Check only; print files that would change").flag()
    private val inPlace by option("-i", "--in-place", help = "Write changes back to files").flag()
    private val enableSpacing by option("--spacing", help = "Apply spacing normalization").flag()
    private val enableWrapping by option("--wrap", "--wrapping", help = "Enable line wrapping").flag()
    private val files by argument(help = "One or more .lyng files to format").multiple()

    override fun help(context: Context): String = "Format Lyng source files"

    override fun run() {
        // Validate inputs
        if (files.isEmpty()) {
            println("Error: no files specified. See --help for usage.")
            exit(1)
        }
        if (checkOnly && inPlace) {
            println("Error: --check and --in-place cannot be used together")
            exit(1)
        }

        val cfg = net.sergeych.lyng.format.LyngFormatConfig(
            applySpacing = enableSpacing,
            applyWrapping = enableWrapping,
            stringDelimiterPolicy = net.sergeych.lyng.format.LyngStringDelimiterPolicy.PreferFewerEscapes,
        )

        var anyChanged = false
        val multiFile = files.size > 1

        for (path in files) {
            val p = path.toPath()
            val original = FileSystem.SYSTEM.source(p).use { it.buffer().use { bs -> bs.readUtf8() } }
            val formatted = net.sergeych.lyng.format.LyngFormatter.format(original, cfg)
            val changed = formatted != original
            if (checkOnly) {
                if (changed) {
                    println(path)
                    anyChanged = true
                }
            } else if (inPlace) {
                // Write back regardless, but only touch file if content differs
                if (changed) {
                    FileSystem.SYSTEM.write(p) { writeUtf8(formatted) }
                }
            } else {
                // Default: stdout output
                if (multiFile) {
                    println("--- $path ---")
                }
                println(formatted)
            }
        }

        if (checkOnly) {
            exit(if (anyChanged) 2 else 0)
        }
    }
}

private class Lyng(val launcher: (suspend () -> Unit) -> Unit) : CoreCliktCommand() {

    override val invokeWithoutSubcommand = true
    override val printHelpOnEmptyArgs = true

    val version by option("-v", "--version", help = "Print version and exit").flag()
    val benchmark by option("--benchmark", help = "Run JVM microbenchmarks and exit").flag()
    val script by argument(help = "one or more scripts to execute").optional()
    val execute: String? by option(
        "-x", "--execute", help = """
        execute string <text>, the rest of command line is passed to Lyng as ARGV
        """.trimIndent()
    )

    val args by argument(help = "arguments for script").multiple()

    override fun help(context: Context): String =
        """
            The Lyng script language runtime, language version is $LyngVersion.
            
            Please refer form more information to the project site:
            https://gitea.sergeych.net/SergeychWorks/lyng
            
        """.trimIndent()

    override fun run() {
        // If a subcommand (like `fmt`) was invoked, do nothing in the root command.
        // This prevents the root from printing help before the subcommand runs.
        if (currentContext.invokedSubcommand != null) return

        runBlocking {
            when {
                version -> {
                    println("Lyng language version ${LyngVersion}")
                }

                execute != null -> {
                    val objargs = mutableListOf<String>()
                    script?.let { objargs += it }
                    objargs += args
                    launcher {
                        // there is no script name, it is a first argument instead:
                        processErrors {
                            executeSource(
                                Source("<eval>", execute!!),
                                newCliScope(objargs)
                            )
                        }
                    }
                }

                else -> {
                    if (script == null) {
                        println("Error: no script specified.\n")
                        echoFormattedHelp()
                    } else {
                        launcher { executeFile(script!!, args) }
                    }
                }
            }
        }
    }
}

fun executeFileWithArgs(fileName: String, args: List<String>) {
    runBlocking {
        executeFile(fileName, args)
    }
}

suspend fun executeSource(source: Source, initialScope: Scope? = null) {
    val session = EvalSession(initialScope ?: baseScopeDefer.await())
    val rootScope = session.getScope()
    val runtime = CliExecutionRuntime(session, rootScope)
    rootScope.installCliBuiltins(runtime)
    val shutdownHooks = CliPlatformShutdownHooks.install(runtime)
    var requestedExitCode: Int? = null
    try {
        try {
            evalOnCliDispatcher(session, source)
        } catch (e: CliExitRequested) {
            requestedExitCode = e.code
        } catch (e: ExecutionError) {
            val cliExit = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<CliExitRequested>()
                .firstOrNull()
            if (cliExit != null) {
                requestedExitCode = cliExit.code
            } else {
                throw e
            }
        }
    } finally {
        shutdownHooks.uninstall()
        runtime.shutdown()
    }
    requestedExitCode?.let { exit(it) }
}

internal suspend fun evalOnCliDispatcher(session: EvalSession, source: Source): Obj =
    withContext(Dispatchers.Default) {
        session.eval(source)
    }

suspend fun executeFile(fileName: String, args: List<String> = emptyList()) {
    val canonicalFile = canonicalPath(fileName.toPath())
    val text = stripShebang(readUtf8(canonicalFile))
    processErrors {
        executeSource(
            Source(canonicalFile.toString(), text),
            newCliScope(args, canonicalFile.toString())
        )
    }
}

suspend fun processErrors(block: suspend () -> Unit) {
    try {
        block()
    }
    catch (e: ScriptError) {
        println("\nError executing the script:\n$e\n")
    }
}
