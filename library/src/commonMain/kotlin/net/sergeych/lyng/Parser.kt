package net.sergeych.lyng

val digitsSet = ('0'..'9').toSet()
val digits = { d: Char -> d in digitsSet }
val hexDigits = digitsSet + ('a'..'f') + ('A'..'F')
val idNextChars = { d: Char -> d.isLetter() || d == '_' || d.isDigit() }

@Suppress("unused")
val idFirstChars = { d: Char -> d.isLetter() || d == '_' }

fun parseLyng(source: Source): List<Token> {
    val p = Parser(fromPos = source.startPos)
    val tokens = mutableListOf<Token>()
    do {
        val t = p.nextToken()
        tokens += t
    } while (t.type != Token.Type.EOF)
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
        return when (val ch = pos.currentChar.also { pos.advance() }) {
            '(' -> Token("(", from, Token.Type.LPAREN)
            ')' -> Token(")", from, Token.Type.RPAREN)
            '{' -> Token("{", from, Token.Type.LBRACE)
            '}' -> Token("}", from, Token.Type.RBRACE)
            '[' -> Token("[", from, Token.Type.LBRACKET)
            ']' -> Token("]", from, Token.Type.RBRACKET)
            ',' -> Token(",", from, Token.Type.COMMA)
            ';' -> Token(";", from, Token.Type.SEMICOLON)
            '=' -> {
                if (pos.currentChar == '=') {
                    pos.advance()
                    if (currentChar == '=') {
                        pos.advance()
                        Token("===", from, Token.Type.REF_EQ)
                    } else
                        Token("==", from, Token.Type.EQ)
                } else
                    Token("=", from, Token.Type.ASSIGN)
            }

            '+' -> {
                when (currentChar) {
                    '+' -> {
                        pos.advance()
                        Token("+", from, Token.Type.PLUS2)
                    }

                    '=' -> {
                        pos.advance()
                        Token("+", from, Token.Type.PLUSASSIGN)
                    }

                    else ->
                        Token("+", from, Token.Type.PLUS)
                }
            }

            '-' -> {
                when (currentChar) {
                    '-' -> {
                        pos.advance()
                        Token("--", from, Token.Type.MINUS2)
                    }

                    '=' -> {
                        pos.advance()
                        Token("-", from, Token.Type.MINUSASSIGN)
                    }

                    '>' -> {
                        pos.advance()
                        Token("->", from, Token.Type.ARROW)
                    }

                    else -> Token("-", from, Token.Type.MINUS)
                }
            }

            '*' -> {
                if (currentChar == '=') {
                    pos.advance()
                    Token("*=", from, Token.Type.STARASSIGN)
                } else
                    Token("*", from, Token.Type.STAR)
            }

            '/' -> when (currentChar) {
                '/' -> {
                    pos.advance()
                    Token(loadToEnd().trim(), from, Token.Type.SINLGE_LINE_COMMENT)
                }

                '*' -> {
                    pos.advance()
                    Token(
                        loadTo("*/")?.trim()
                            ?: throw ScriptError(from, "Unterminated multiline comment"),
                        from,
                        Token.Type.MULTILINE_COMMENT
                    )
                }

                '=' -> {
                    pos.advance()
                    Token("/=", from, Token.Type.SLASHASSIGN)
                }

                else -> Token("/", from, Token.Type.SLASH)
            }

            '%' -> when (currentChar) {
                '=' -> {
                    pos.advance(); Token("%=", from, Token.Type.PERCENTASSIGN)
                }

                else -> Token("%", from, Token.Type.PERCENT)
            }

            '.' -> {
                // could be: dot, range .. or ..<, or ellipsis ...:
                if (currentChar == '.') {
                    pos.advance()
                    // .. already parsed:
                    if (currentChar == '.') {
                        pos.advance()
                        Token("...", from, Token.Type.ELLIPSIS)
                    } else if (currentChar == '<') {
                        pos.advance()
                        Token("..<", from, Token.Type.DOTDOTLT)
                    } else {
                        Token("..", from, Token.Type.DOTDOT)
                    }
                } else
                    Token(".", from, Token.Type.DOT)
            }

            '<' -> {
                if (currentChar == '=') {
                    pos.advance()
                    if( currentChar == '>' ) {
                        pos.advance()
                        Token("<=>", from, Token.Type.SHUTTLE)
                    }
                    else {
                        Token("<=", from, Token.Type.LTE)
                    }
                } else
                    Token("<", from, Token.Type.LT)
            }

            '>' -> {
                if (currentChar == '=') {
                    pos.advance()
                    Token(">=", from, Token.Type.GTE)
                } else
                    Token(">", from, Token.Type.GT)
            }

            '!' -> {
                if (currentChar == 'i') {
                    pos.advance()
                    when (currentChar) {
                        'n' -> {
                            pos.advance()
                            Token("!in", from, Token.Type.NOTIN)
                        }

                        's' -> {
                            pos.advance()
                            Token("!is", from, Token.Type.NOTIS)
                        }

                        else -> {
                            pos.back()
                            Token("!", from, Token.Type.NOT)
                        }
                    }
                } else
                    if (currentChar == '=') {
                        pos.advance()
                        if (currentChar == '=') {
                            pos.advance()
                            Token("!==", from, Token.Type.REF_NEQ)
                        } else
                            Token("!=", from, Token.Type.NEQ)
                    } else
                        Token("!", from, Token.Type.NOT)
            }

            '|' -> {
                if (currentChar == '|') {
                    pos.advance()
                    Token("||", from, Token.Type.OR)
                } else
                    Token("|", from, Token.Type.BITOR)
            }

            '&' -> {
                if (currentChar == '&') {
                    pos.advance()
                    Token("&&", from, Token.Type.AND)
                } else
                    Token("&", from, Token.Type.BITAND)
            }

            '@' -> {
                val label = loadChars(idNextChars)
                if (label.isNotEmpty()) Token(label, from, Token.Type.ATLABEL)
                else raise("unexpected @ character")
            }

            '\n' -> Token("\n", from, Token.Type.NEWLINE)

            ':' -> {
                if (currentChar == ':') {
                    pos.advance()
                    Token("::", from, Token.Type.COLONCOLON)
                } else
                    Token(":", from, Token.Type.COLON)
            }

            '"' -> loadStringToken()
            in digitsSet -> {
                pos.back()
                decodeNumber(loadChars(digits), from)
            }

            '\'' -> {
                val start = pos.toPos()
                var value = currentChar
                pos.advance()
                if (currentChar == '\\') {
                    value = currentChar
                    pos.advance()
                    value = when (value) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '\'', '\\' -> value
                        else -> throw ScriptError(currentPos, "unsupported escape character: $value")
                    }
                }
                if (currentChar != '\'') throw ScriptError(currentPos, "expected end of character literal: '")
                pos.advance()
                Token(value.toString(), start, Token.Type.CHAR)
            }

            else -> {
                // text infix operators:
                // Labels processing is complicated!
                // some@ statement: label 'some', ID 'statement'
                // statement@some: ID 'statement', LABEL 'some'!
                if (ch.isLetter() || ch == '_') {
                    val text = ch + loadChars(idNextChars)
                    if (currentChar == '@') {
                        pos.advance()
                        if (currentChar.isLetter()) {
                            // break@label or like
                            pos.back()
                            Token(text, from, Token.Type.ID)
                        } else
                            Token(text, from, Token.Type.LABEL)
                    } else
                        when (text) {
                            "in" -> Token("in", from, Token.Type.IN)
                            "is" -> Token("is", from, Token.Type.IS)
                            "protected" -> Token("protected", from, Token.Type.PROTECTED)
                            "private" -> Token("private", from, Token.Type.PRIVATE)
                            else -> Token(text, from, Token.Type.ID)
                        }
                } else
                    raise("can't parse token")
            }
        }
    }

    private fun decodeNumber(p1: String, start: Pos): Token =
        if (pos.end)
            Token(p1, start, Token.Type.INT)
        else if (currentChar == '.') {
            // could be decimal
            pos.advance()
            if (currentChar in digitsSet) {
                // decimal part
                val p2 = loadChars(digits)
                // with exponent?
                if (currentChar == 'e' || currentChar == 'E') {
                    pos.advance()
                    var negative = false
                    if (currentChar == '+')
                        pos.advance()
                    else if (currentChar == '-') {
                        negative = true
                        pos.advance()
                    }
                    var p3 = loadChars(digits)
                    if (negative) p3 = "-$p3"
                    Token("$p1.${p2}e$p3", start, Token.Type.REAL)
                } else {
                    // no exponent
                    Token("$p1.$p2", start, Token.Type.REAL)
                }
            } else {
                // not decimal
                // something like 10.times, method call on integer number
                pos.back()
                Token(p1, start, Token.Type.INT)
            }
        } else {
            // could be integer, also hex:
            if (currentChar == 'x' && p1 == "0") {
                pos.advance()
                Token(loadChars({ it in hexDigits }), start, Token.Type.HEX).also {
                    if (currentChar.isLetter())
                        raise("invalid hex literal")
                }
            } else {
                Token(p1, start, Token.Type.INT)
            }
        }


    private val currentChar: Char get() = pos.currentChar

    private fun loadStringToken(): Token {
        var start = currentPos

        if (currentChar == '"') pos.advance()
        else start = start.back()

        val sb = StringBuilder()
        while (currentChar != '"') {
            if (pos.end) throw ScriptError(start, "unterminated string started there")
            when (currentChar) {
                '\\' -> {
                    pos.advance() ?: raise("unterminated string")
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
                    pos.advance()
                }
            }
        }
        pos.advance()
        return Token(sb.toString(), start, Token.Type.STRING)
    }

    /**
     * Load characters from the set until it reaches EOF or invalid character found.
     * stop at EOF on character filtered by [isValidChar].
     *
     * Note this function loads only on one string. Multiline texts are not supported by
     * this method.
     *
     * @return the string of valid characters, could be empty
     */
    private fun loadChars(isValidChar: (Char) -> Boolean): String {
        val startLine = pos.line
        val result = StringBuilder()
        while (!pos.end && pos.line == startLine) {
            val ch = pos.currentChar
            if (isValidChar(ch)) {
                result.append(ch)
                pos.advance()
            } else
                break
        }
        return result.toString()
    }

    @Suppress("unused")
    private fun loadUntil(endChars: Set<Char>): String {
        return if (pos.end) ""
        else {
            val result = StringBuilder()
            while (!pos.end) {
                val ch = pos.currentChar
                if (ch in endChars) break
                result.append(ch)
                pos.advance()
            }
            result.toString()
        }
    }

    private fun loadToEnd(): String {
        val result = StringBuilder()
        val l = pos.line
        do {
            result.append(pos.currentChar)
            pos.advance()
        } while (pos.line == l)
        return result.toString()
    }

    private fun loadTo(str: String): String? {
        val result = StringBuilder()
        while (!pos.readFragment(str)) {
            if (pos.end) return null
            result.append(pos.currentChar); pos.advance()
        }
        return result.toString()
    }

    /**
     * next non-whitespace char (newline are skipped too) or null if EOF
     */
    private fun skipws(): Char? {
        while (!pos.end) {
            val ch = pos.currentChar
            if (ch == '\n') break
            if (ch.isWhitespace())
                pos.advance()
            else
                return ch
        }
        return null
    }

}