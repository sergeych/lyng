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
    if(args.isNotEmpty()) {
        // CLI formatter: lyng fmt [--check] [--in-place] <files...>
        if (args[0] == "fmt") {
            formatCli(args.drop(1))
            return
        }
        if( args.size >= 2 && args[0] == "--" ) {
            // -- -file.lyng <args>
            executeFileWithArgs(args[1], args.drop(2))
            return
        } else if( args[0][0] != '-') {
            // file.lyng <args>
            executeFileWithArgs(args[0], args.drop(1))
            return
        }
    }
    // normal processing
    Lyng { runBlocking { it() } }.main(args)
}

private fun formatCli(args: List<String>) {
    var checkOnly = false
    var inPlace = true
    var enableSpacing = false
    var enableWrapping = false
    val files = mutableListOf<String>()
    for (a in args) {
        when (a) {
            "--check" -> { checkOnly = true; inPlace = false }
            "--in-place", "-i" -> inPlace = true
            "--spacing" -> enableSpacing = true
            "--wrap", "--wrapping" -> enableWrapping = true
            else -> files += a
        }
    }
    if (files.isEmpty()) {
        println("Usage: lyng fmt [--check] [--in-place|-i] [--spacing] [--wrap] <file1.lyng> [file2.lyng ...]")
        exit(1)
        return
    }
    var changed = false
    val cfg = net.sergeych.lyng.format.LyngFormatConfig(
        applySpacing = enableSpacing,
        applyWrapping = enableWrapping,
    )
    for (path in files) {
        val p = path.toPath()
        val original = FileSystem.SYSTEM.source(p).use { it.buffer().use { bs -> bs.readUtf8() } }
        val formatted = net.sergeych.lyng.format.LyngFormatter.format(original, cfg)
        if (formatted != original) {
            changed = true
            if (checkOnly) {
                println(path)
            } else if (inPlace) {
                FileSystem.SYSTEM.write(p) { writeUtf8(formatted) }
            } else {
                // default to stdout if not in-place and not --check
                println("--- $path (formatted) ---\n$formatted")
            }
        }
    }
    if (checkOnly) {
        exit(if (changed) 2 else 0)
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
                        println(
                            """
                        
                        Error: no script specified.
                        
                    """.trimIndent()
                        )
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
