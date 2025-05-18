package net.sergeych.ling

data class Pos(val source: Source, val line: Int, val column: Int) {
    override fun toString(): String {
        return "${source.fileName}:$line:$column"
    }

    fun back(): Pos =
        if( column > 0) Pos(source, line, column-1)
        else if( line > 0) Pos(source, line-1, source.lines[line-1].length - 1)
        else throw IllegalStateException("can't go back from line 0, column 0")

    val currentLine: String get() = if( end ) "EOF" else source.lines[line]

    val end: Boolean get() = line >= source.lines.size

    companion object {
        val builtIn = Pos(Source.builtIn, 0, 0)
    }

}

class MutablePos(private val from: Pos) {

    val lines = from.source.lines
    var line = from.line
        private set
    var column = from.column
        private set

    val end: Boolean get() =
        line == lines.size

    fun toPos(): Pos = Pos(from.source, line, column)

    fun advance(): Char? {
        if (end) return null
        val current = lines[line]
        return if (column+1 < current.length) {
            current[column++]
        } else {
            column = 0
            while( ++line < lines.size && lines[line].isEmpty() ) {
                // skip empty lines
            }
            if(line >= lines.size) null
            else lines[line][column]
        }
    }

    fun back() {
        if (column > 0) column--
        else if (line > 0)
            column = lines[--line].length - 1
        else throw IllegalStateException("can't go back from line 0, column 0")
    }
    val currentChar: Char
        get() =
            if (end) 0.toChar()
            else lines[line][column]

    override fun toString() = "($line:$column)"

    init {
        if( lines[0].isEmpty()) advance()
    }
}
