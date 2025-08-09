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
import net.sergeych.lyng.obj.*
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
    }
}

fun runMain(args: Array<String>) {
    if(args.isNotEmpty()) {
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

private class Lyng(val launcher: (suspend () -> Unit) -> Unit) : CliktCommand() {

    override val printHelpOnEmptyArgs = true

    val version by option("-v", "--version", help = "Print version and exit").flag()
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
