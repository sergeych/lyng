package net.sergeych.ling

private val idFirstChars: Set<Char> = (
        ('a'..'z') + ('A'..'Z') + '_' + ('а'..'я') + ('А'..'Я')
        ).toSet()
val idNextChars: Set<Char> = idFirstChars + ('0'..'9')
val digits = ('0'..'9').toSet()
val hexDigits = digits + ('a'..'f') + ('A'..'F')

fun parseLing(source: Source): List<Token> {
    val p = Parser(fromPos = source.startPos)
    val tokens = mutableListOf<Token>()
    do {
        val t = p.nextToken()
        tokens += t
    } while(t.type != Token.Type.EOF)
    return tokens
}

private class Parser(fromPos: Pos) {

    private val pos = MutablePos(fromPos)

    /**
     * Immutable copy of current position
     */
    private val currentPos: Pos get() = pos.toPos()

    private fun raise(msg: String): Nothing = throw ScriptError(currentPos, msg)

    fun nextToken(): Token {
        skipws()
        if (pos.end) return Token("", currentPos, Token.Type.EOF)
        val from = currentPos
        return when (val ch = pos.currentChar.also { advance() }) {
            '(' -> Token("(", from, Token.Type.LPAREN)
            ')' -> Token(")", from, Token.Type.RPAREN)
            '{' -> Token("{", from, Token.Type.LBRACE)
            '}' -> Token("}", from, Token.Type.RBRACE)
            '[' -> Token("[", from, Token.Type.LBRACKET)
            ']' -> Token("]", from, Token.Type.RBRACKET)
            ',' -> Token(",", from, Token.Type.COMMA)
            ';' -> Token(";", from, Token.Type.SEMICOLON)
            '=' -> Token("=", from, Token.Type.ASSIGN)
            '+' -> Token("+", from, Token.Type.PLUS)
            '-' -> Token("-", from, Token.Type.MINUS)
            '*' -> Token("*", from, Token.Type.STAR)
            '/' -> Token("/", from, Token.Type.SLASH)
            '.' -> Token(".", from, Token.Type.DOT)
            '<' -> Token("<", from, Token.Type.LT)
            '>' -> Token(">", from, Token.Type.GT)
            '!' -> Token("!", from, Token.Type.NOT)
            '"' -> loadStringToken()
            in digits -> {
                pos.back()
                decodeNumber(loadChars(digits), from)
            }
            in idFirstChars -> {
                // it includes first char, so from current position
                Token(ch + loadChars(idNextChars), from, Token.Type.ID)
            }
            else -> raise("can't parse token")
        }
    }

    private fun decodeNumber(p1: String, start: Pos): Token =
        if( pos.end )
            Token(p1, start, Token.Type.INT)
        else if( currentChar == '.' ) {
            // could be decimal
            advance()
            if( currentChar in digits ) {
                // decimal part
                val p2 = loadChars(digits)
                // with exponent?
                if( currentChar == 'e' || currentChar == 'E') {
                    advance()
                    var negative = false
                    if(currentChar == '+' )
                        advance()
                    else if(currentChar == '-') {
                        negative = true
                        advance()
                    }
                    var p3 = loadChars(digits)
                    if( negative ) p3 = "-$p3"
                    Token("$p1.${p2}e$p3", start, Token.Type.REAL)
                }
                else {
                    // no exponent
                    Token("$p1.$p2", start, Token.Type.REAL)
                }
            }
            else {
                // not decimal
                // something like 10.times, method call on integer number
                pos.back()
                Token(p1, start, Token.Type.INT)
            }
        }
        else {
            // could be integer, also hex:
            if (currentChar == 'x' && p1 == "0") {
                advance()
                Token(loadChars(hexDigits), start, Token.Type.HEX).also {
                    if( currentChar.isLetter() )
                        raise("invalid hex literal")
                }
            } else {
                Token(p1, start, Token.Type.INT)
            }
        }


    private val currentChar: Char get() = pos.currentChar

    private fun loadStringToken(): Token {
        var start = currentPos

        if (currentChar == '"') advance()
        else start = start.back()

        val sb = StringBuilder()
        while (currentChar != '"') {
            if( pos.end ) raise("unterminated string")
            when (currentChar) {
                '\\' -> {
                    advance() ?: raise("unterminated string")
                    when (currentChar) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        else -> sb.append('\\').append(currentChar)
                    }
                }
                else -> {
                    sb.append(currentChar)
                    advance()
                }
            }
        }
        advance()
        return Token(sb.toString(), start, Token.Type.STRING)
    }

    /**
     * Load characters from the set until it reaches EOF or invalid character found.
     * stop at EOF on character not in [validChars].
     * @return the string of valid characters, could be empty
     */
    private fun loadChars(validChars: Set<Char>): String {
        val result = StringBuilder()
        while (!pos.end) {
            val ch = pos.currentChar
            if (ch in validChars) {
                result.append(ch)
                advance()
            } else
                break
        }
        return result.toString()
    }

    /**
     * next non-whitespace char (newline are skipped too) or null if EOF
     */
    private fun skipws(): Char? {
        while (!pos.end) {
            val ch = pos.currentChar
            if (ch.isWhitespace())
                advance()
            else
                return ch
        }
        return null
    }

    private fun advance() = pos.advance()

    init {
//        advance()
    }
}