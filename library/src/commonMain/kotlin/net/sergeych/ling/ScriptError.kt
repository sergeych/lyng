@file:Suppress("CanBeParameter")

package net.sergeych.ling

open class ScriptError(val pos: Pos, val errorMessage: String,cause: Throwable?=null) : Exception(
    """
        $pos: Error: $errorMessage
        ${pos.currentLine}
        ${"-".repeat(pos.column)}^
    """.trimIndent(),
    cause
)

class ExecutionError(val errorObject: ObjError) : ScriptError(errorObject.context.pos, errorObject.message)
