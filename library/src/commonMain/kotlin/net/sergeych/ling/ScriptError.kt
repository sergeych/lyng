package net.sergeych.ling

class ScriptError(val pos: Pos, val errorMessage: String) : Exception(
    """
        $pos: Error: $errorMessage
        ${pos.currentLine}
        ${"-".repeat(pos.column)}^
    """.trimIndent()
)
