package net.sergeych

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import net.sergeych.lyng.*
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

val baseContext = Context().apply {
    addFn("exit") {
        exit(requireOnlyArg<ObjInt>().toInt())
        ObjVoid
    }
//    ObjString.type.addFn("shell") {
//
//    }
}

class Lyng(val launcher: (suspend () -> Unit) -> Unit) : CliktCommand() {

    override val printHelpOnEmptyArgs = true

    val version by option("-v", "--version", help = "Print version and exit").flag()
    val script by argument(help = "one or more scripts to execute").optional()
    val execute: String? by option(
        "-x", "--execute", help = """
        execute string <text>, the rest of command line is passed to Lyng as ARGV
        """.trimIndent()
    )

    val args by argument(help = "arguments for script").multiple()

    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        """
            The Lyng script language interpreter, language version is $LyngVersion.
            
            Please refer form more information to the project site:
            https://gitea.sergeych.net/SergeychWorks/lyng
            
        """.trimIndent()

    override fun run() {
        when {
            version -> {
                println("Lyng language version ${LyngVersion}")
            }

            execute != null -> {
                val objargs = mutableListOf<String>()
                script?.let { objargs += it }
                objargs += args
                baseContext.addConst(
                    "ARGV", ObjList(
                        objargs.map { ObjString(it) }.toMutableList()
                    )
                )
                launcher {
                    // there is no script name, it is a first argument instead:
                    processErrors {
                        baseContext.eval(execute!!)
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
                    baseContext.addConst("ARGV", ObjList(args.map { ObjString(it) }.toMutableList()))
                    launcher { executeFile(script!!) }
                }
            }
        }
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
        Compiler().compile(Source(fileName, text)).execute(baseContext)
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
