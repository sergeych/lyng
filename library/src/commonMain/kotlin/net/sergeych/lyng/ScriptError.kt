@file:Suppress("CanBeParameter")

package net.sergeych.lyng

open class ScriptError(val pos: Pos, val errorMessage: String,cause: Throwable?=null) : Exception(
    """
        $pos: Error: $errorMessage
        
        ${pos.currentLine}
        ${"-".repeat(pos.column)}^
    """.trimIndent(),
    cause
)

class ExecutionError(val errorObject: ObjException) : ScriptError(errorObject.context.pos, errorObject.message)
