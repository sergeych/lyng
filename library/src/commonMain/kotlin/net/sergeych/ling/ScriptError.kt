@file:Suppress("CanBeParameter")

package net.sergeych.ling

open class ScriptError(val pos: Pos, val errorMessage: String) : Exception(
    """
        $pos: Error: $errorMessage
        ${pos.currentLine}
        ${"-".repeat(pos.column)}^
    """.trimIndent()
)

class ExecutionError(val errorObject: ObjError) : ScriptError(errorObject.context.pos, errorObject.message)
