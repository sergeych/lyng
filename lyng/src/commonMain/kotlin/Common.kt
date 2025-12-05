/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.LyngVersion
import net.sergeych.lyng.Script
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.Source
import net.sergeych.lyng.io.fs.createFs
import net.sergeych.lyng.obj.*
import net.sergeych.lyngio.fs.security.PermitAllAccessPolicy
import net.sergeych.mp_tools.globalDefer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use

// common code

expect fun exit(code: Int)

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

val baseScopeDefer = globalDefer {
    Script.newScope().apply {
        addFn("exit") {
            exit(requireOnlyArg<ObjInt>().toInt())
            ObjVoid
        }
        // Install lyng.io.fs module with full access by default for the CLI tool's Scope.
        // Scripts still need to `import lyng.io.fs` to use Path API.
        createFs(PermitAllAccessPolicy, this)
    }
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

private class Fmt : CliktCommand(name = "fmt") {
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

private class Lyng(val launcher: (suspend () -> Unit) -> Unit) : CliktCommand() {

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
            The Lyng script language interpreter, language version is $LyngVersion.
            
            Please refer form more information to the project site:
            https://gitea.sergeych.net/SergeychWorks/lyng
            
        """.trimIndent()

    override fun run() {
        // If a subcommand (like `fmt`) was invoked, do nothing in the root command.
        // This prevents the root from printing help before the subcommand runs.
        if (currentContext.invokedSubcommand != null) return

        runBlocking {
            val baseScope = baseScopeDefer.await()
            when {
                version -> {
                    println("Lyng language version ${LyngVersion}")
                }

                execute != null -> {
                    val objargs = mutableListOf<String>()
                    script?.let { objargs += it }
                    objargs += args
                    baseScope.addConst(
                        "ARGV", ObjList(
                            objargs.map { ObjString(it) }.toMutableList()
                        )
                    )
                    launcher {
                        // there is no script name, it is a first argument instead:
                        processErrors {
                            baseScope.eval(execute!!)
                        }
                    }
                }

                else -> {
                    if (script == null) {
                        println("Error: no script specified.\n")
                        echoFormattedHelp()
                    } else {
                        baseScope.addConst("ARGV", ObjList(args.map { ObjString(it) }.toMutableList()))
                        launcher { executeFile(script!!) }
                    }
                }
            }
        }
    }
}

fun executeFileWithArgs(fileName: String, args: List<String>) {
    runBlocking {
        baseScopeDefer.await().addConst("ARGV", ObjList(args.map { ObjString(it) }.toMutableList()))
        executeFile(fileName)
    }
}

suspend fun executeFile(fileName: String) {
    var text = FileSystem.SYSTEM.source(fileName.toPath()).use { fileSource ->
        fileSource.buffer().use { bs ->
            bs.readUtf8()
        }
    }
    if( text.startsWith("#!") ) {
        // skip shebang
        val pos = text.indexOf('\n')
        text = text.substring(pos + 1)
    }
    processErrors {
        baseScopeDefer.await().eval(Source(fileName, text))
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
