package net.sergeych.lyng

class Source(val fileName: String, text: String) {

    val lines = text.lines().map { it.trimEnd() }

    companion object {
        val builtIn: Source by lazy { Source("built-in", "") }
        val UNKNOWN: Source by lazy { Source("UNKNOWN", "") }
    }

    val startPos: Pos = Pos(this, 0, 0)

    fun posAt(line: Int, column: Int): Pos = Pos(this, line, column)

    fun extractPackageName(): String {
        for ((n,line) in lines.withIndex()) {
            if( line.isBlank() || line.isEmpty() )
                continue
            if( line.startsWith("package ") )
                return line.substring(8).trim()
            else throw ScriptError(Pos(this, n, 0),"package declaration expected")
        }
        throw ScriptError(Pos(this, 0, 0),"package declaration expected")
    }
}