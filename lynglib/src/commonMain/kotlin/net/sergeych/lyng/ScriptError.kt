@file:Suppress("CanBeParameter")

package net.sergeych.lyng

import net.sergeych.lyng.obj.ObjException

open class ScriptError(val pos: Pos, val errorMessage: String, cause: Throwable? = null) : Exception(
    """
        $pos: Error: $errorMessage
        
        ${pos.currentLine}
        ${"-".repeat(pos.column)}^
    """.trimIndent(),
    cause
)

class ExecutionError(val errorObject: ObjException) : ScriptError(errorObject.scope.pos, errorObject.message)

class ImportException(pos: Pos, message: String) : ScriptError(pos, message)