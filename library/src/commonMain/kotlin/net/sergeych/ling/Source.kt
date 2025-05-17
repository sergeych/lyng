package net.sergeych.ling

class Source(val fileName: String, text: String) {

    val lines = text.lines().map { it.trimEnd() }

    companion object {
        val builtIn: Source by lazy { Source("built-in", "") }
    }

    val startPos: Pos = Pos(this, 0, 0)

    fun posAt(line: Int, column: Int): Pos = Pos(this, line, column)
}