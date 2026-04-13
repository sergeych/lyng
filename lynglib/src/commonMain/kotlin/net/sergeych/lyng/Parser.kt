/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.sergeych.lyng

val digitsSet = ('0'..'9').toSet()
val digits = { d: Char -> d in digitsSet }
val hexDigits = digitsSet + ('a'..'f') + ('A'..'F')
val idNextChars = { d: Char -> d.isLetter() || d == '_' || d.isDigit() || d == '$' || d == '~' }

@Suppress("unused")
val idFirstChars = { d: Char -> d.isLetter() || d == '_' || d == '$' }

fun parseLyng(source: Source): List<Token> {
    val p = Parser(
        fromPos = source.startPos,
        interpolationEnabled = detectInterpolationEnabled(source.text)
    )
    val tokens = mutableListOf<Token>()
    do {
        val t = p.nextToken()
        tokens += t
    } while (t.type != Token.Type.EOF)
    return tokens
}

private fun parseLyng(source: Source, interpolationEnabled: Boolean): List<Token> {
    val p = Parser(
        fromPos = source.startPos,
        interpolationEnabled = interpolationEnabled
    )
    val tokens = mutableListOf<Token>()
    do {
        val t = p.nextToken()
        tokens += t
    } while (t.type != Token.Type.EOF)
    return tokens
}

private fun detectInterpolationEnabled(text: String): Boolean {
    // Per-file feature switch in leading comments:
    //   // feature: interpolation: off
    //   // feature: interpolation: on
    var enabled = true
    val lines = text.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            i++
            continue
        }
        if (i == 0 && trimmed.startsWith("#!")) {
            i++
            continue
        }
        if (trimmed.startsWith("//")) {
            val m = Regex("^//\\s*feature\\s*:\\s*interpolation\\s*:\\s*(on|off)\\s*$", RegexOption.IGNORE_CASE)
                .matchEntire(trimmed)
            if (m != null) {
                enabled = m.groupValues[1].equals("on", ignoreCase = true)
            }
            i++
            continue
        }
        if (trimmed.startsWith("/*")) {
            // Skip leading block comment(s); directives are intentionally line-comment based.
            var j = i
            var closed = false
            while (j < lines.size) {
                if (lines[j].contains("*/")) {
                    closed = true
                    break
                }
                j++
            }
            if (!closed) break
            i = j + 1
            continue
        }
        break
    }
    return enabled
}

private class Parser(fromPos: Pos, private val interpolationEnabled: Boolean = true) {

    private val pos = MutablePos(fromPos)
    private val bufferedTokens = ArrayDeque<Token>()

    /**
     * Immutable copy of current position
     */
    private val currentPos: Pos get() = pos.toPos()

    private fun raise(msg: String): Nothing = throw ScriptError(currentPos, msg)

    fun nextToken(): Token {
        if (bufferedTokens.isNotEmpty()) return bufferedTokens.removeFirst()
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
                    when (currentChar) {
                        '=' -> {
                            pos.advance()
                            Token("===", from, Token.Type.REF_EQ)
                        }

                        else -> Token("==", from, Token.Type.EQ)
                    }
                } else if (currentChar == '>') {
                    pos.advance()
                    Token("=>", from, Token.Type.EQARROW)
                } else if (currentChar == '~') {
                    pos.advance()
                    Token("=~", from, Token.Type.MATCH)
                } else
                    Token("=", from, Token.Type.ASSIGN)
            }

            '+' -> {
                when (currentChar) {
                    '+' -> {
                        pos.advance()
                        Token("++", from, Token.Type.PLUS2)
                    }

                    '=' -> {
                        pos.advance()
                        Token("+=", from, Token.Type.PLUSASSIGN)
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
                        Token("-=", from, Token.Type.MINUSASSIGN)
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
                    val body = loadToEndOfLine()
                    // Include the leading '//' and do not trim; keep exact lexeme (excluding preceding codepoint)
                    Token("//" + body, from, Token.Type.SINGLE_LINE_COMMENT)
                }

                '*' -> {
                    pos.advance()
                    val content = loadTo("*/")
                        ?: throw ScriptError(from, "Unterminated multiline comment")
                    // loadTo consumes the closing fragment, so we are already after */
                    Token("/*" + content + "*/", from, Token.Type.MULTILINE_COMMENT)
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
                    when (currentChar) {
                        '.' -> {
                            pos.advance()
                            Token("...", from, Token.Type.ELLIPSIS)
                        }

                        '<' -> {
                            pos.advance()
                            Token("..<", from, Token.Type.DOTDOTLT)
                        }

                        else -> {
                            Token("..", from, Token.Type.DOTDOT)
                        }
                    }
                } else
                    Token(".", from, Token.Type.DOT)
            }

            '<' -> {
                if (currentChar == '=') {
                    pos.advance()
                    if (currentChar == '>') {
                        pos.advance()
                        Token("<=>", from, Token.Type.SHUTTLE)
                    } else {
                        Token("<=", from, Token.Type.LTE)
                    }
                } else if (currentChar == '<') {
                    // Shift left <<
                    pos.advance()
                    Token("<<", from, Token.Type.SHL)
                } else
                    Token("<", from, Token.Type.LT)
            }

            '>' -> {
                if (currentChar == '=') {
                    pos.advance()
                    Token(">=", from, Token.Type.GTE)
                } else if (currentChar == '>') {
                    // Shift right >>
                    pos.advance()
                    Token(">>", from, Token.Type.SHR)
                } else
                    Token(">", from, Token.Type.GT)
            }

            '!' -> {
                if (currentChar == 'i') {
                    // Potentially !in / !is, but only if a word boundary follows
                    pos.advance()
                    when (currentChar) {
                        'n' -> {
                            pos.advance()
                            // if next char continues an identifier, it's actually '!'+identifier starting with "in..."
                            if (idNextChars(currentChar)) {
                                // backtrack to right after '!'
                                pos.back()
                                pos.back()
                                Token("!", from, Token.Type.NOT)
                            } else
                                Token("!in", from, Token.Type.NOTIN)
                        }

                        's' -> {
                            pos.advance()
                            // if next char continues an identifier, it's actually '!'+identifier starting with "is..."
                            if (idNextChars(currentChar)) {
                                // backtrack to right after '!'
                                pos.back()
                                pos.back()
                                Token("!", from, Token.Type.NOT)
                            } else
                                Token("!is", from, Token.Type.NOTIS)
                        }

                        else -> {
                            // it was just '!i' followed by something else; revert one step and return '!'
                            pos.back()
                            Token("!", from, Token.Type.NOT)
                        }
                    }
                } else if (currentChar == '=') {
                        pos.advance()
                        if (currentChar == '=') {
                            pos.advance()
                            Token("!==", from, Token.Type.REF_NEQ)
                        } else
                            Token("!=", from, Token.Type.NEQ)
                    } else if (currentChar == '~') {
                        pos.advance()
                        Token("!~", from, Token.Type.NOTMATCH)
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

            '^' -> Token("^", from, Token.Type.BITXOR)

            '~' -> Token("~", from, Token.Type.BITNOT)

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

            '"', '`' -> loadStringTokens(from, ch)

            in digitsSet -> {
                pos.back()
                decodeNumber(loadChars { it in digitsSet || it == '_' }, from)
            }

            '\'' -> {
                val start = pos.toPos()
                var value = currentChar
                if (currentChar == '\\') {
                    pos.advance()
                    if (pos.end) throw ScriptError(start, "unterminated character literal")
                    value = when (currentChar) {
                        'n' -> {
                            pos.advance()
                            '\n'
                        }

                        'r' -> {
                            pos.advance()
                            '\r'
                        }

                        't' -> {
                            pos.advance()
                            '\t'
                        }

                        '\'' -> {
                            pos.advance()
                            '\''
                        }

                        '\\' -> {
                            pos.advance()
                            '\\'
                        }

                        'u' -> loadUnicodeEscape(start)

                        else -> throw ScriptError(currentPos, "unsupported escape character: $currentChar")
                    }
                } else {
                    pos.advance()
                }
                if (currentChar != '\'') throw ScriptError(currentPos, "expected end of character literal: '")
                pos.advance()
                Token(value.toString(), start, Token.Type.CHAR)
            }

            '?' -> {
                when (currentChar) {
                    '=' -> { pos.advance(); Token("?=", from, Token.Type.IFNULLASSIGN) }
                    ':' -> { pos.advance(); Token("?:", from, Token.Type.ELVIS) }
                    '?' -> { pos.advance(); Token("??", from, Token.Type.ELVIS) }
                    '.' -> { pos.advance(); Token("?.", from, Token.Type.NULL_COALESCE) }
                    '[' -> { pos.advance(); Token("?[", from, Token.Type.NULL_COALESCE_INDEX) }
                    '(' -> { pos.advance(); Token("?(", from, Token.Type.NULL_COALESCE_INVOKE) }
                    '{' -> { pos.advance(); Token("?{", from, Token.Type.NULL_COALESCE_BLOCKINVOKE) }
                    else -> {
                        Token("?", from, Token.Type.QUESTION)
                    }
                }
            }

            else -> {
                // text infix operators:
                // Labels processing is complicated!
                // some@ statement: label 'some', ID 'statement'
                // statement@some: ID 'statement', LABEL 'some'!
                if (idNextChars(ch)) {
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
                            "by" -> Token("by", from, Token.Type.BY)
                            "step" -> Token("step", from, Token.Type.STEP)
                            "downTo" -> Token("downTo", from, Token.Type.DOWNTO)
                            "downUntil" -> Token("downUntil", from, Token.Type.DOWNUNTIL)
                            "object" -> Token("object", from, Token.Type.OBJECT)
                            "as" -> {
                                // support both `as` and tight `as?` without spaces
                                if (currentChar == '?') { pos.advance(); Token("as?", from, Token.Type.ASNULL) }
                                else Token("as", from, Token.Type.AS)
                            }
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
        else if (currentChar == 'e' || currentChar == 'E') {
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
            Token("${p1}e$p3", start, Token.Type.REAL)
        } else if (currentChar == '.') {
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
                val hex = loadChars { it in hexDigits }
                Token(hex, start, Token.Type.HEX).also {
                    if (currentChar.isLetter())
                        raise("invalid hex literal")
                }
            } else {
                Token(p1, start, Token.Type.INT)
            }
        }


    private val currentChar: Char get() = pos.currentChar

    private fun fixMultilineStringLiteral(source: String): String {
        val sizes = mutableListOf<Int>()
        val lines = source.lines().toMutableList()
        if (lines.size == 0) return ""
        if (lines[0].isBlank()) lines.removeFirst()
        if (lines.isEmpty()) return ""
        if (lines.last().isBlank()) lines.removeLast()

        val normalized = lines.map { l ->
            if (l.isBlank()) {
                sizes.add(-1)
                ""
            } else {
                val margin = leftMargin(l)
                sizes += margin
                " ".repeat(margin) + l.trim()
            }
        }
        val commonMargin = sizes.filter { it >= 0 }.min()
        val fixed = if (commonMargin < 1) lines else normalized.map {
            if (it.isBlank()) "" else it.drop(commonMargin)
        }
        return fixed.joinToString("\n")
    }

    private fun loadStringToken(delimiter: Char): Token {
        val start = currentPos
        val sb = StringBuilder()
        var newlineDetected = false
        while (currentChar != delimiter) {
            if (pos.end) throw ScriptError(start, "unterminated string started there")
            when (currentChar) {
                '\\' -> {
                    pos.advance() ?: raise("unterminated string")
                    when (currentChar) {
                        'n' -> {
                            sb.append('\n'); pos.advance()
                        }

                        'r' -> {
                            sb.append('\r'); pos.advance()
                        }

                        't' -> {
                            sb.append('\t'); pos.advance()
                        }

                        delimiter -> {
                            sb.append(delimiter); pos.advance()
                        }

                        '\\' -> {
                            sb.append('\\'); pos.advance()
                        }

                        'u' -> {
                            sb.append(loadUnicodeEscape(start))
                        }

                        else -> {
                            sb.append('\\').append(currentChar)
                            pos.advance()
                        }
                    }
                }

                '\n', '\r' -> {
                    newlineDetected = true
                    sb.append(currentChar)
                    pos.advance()
                }

                else -> {
                    sb.append(currentChar)
                    pos.advance()
                }
            }
        }
        pos.advance()

        val result = sb.toString().let { if (newlineDetected) fixMultilineStringLiteral(it) else it }

        return Token(result, start, Token.Type.STRING)
    }

    private sealed interface StringChunk {
        data class Literal(val text: String, val pos: Pos) : StringChunk
        data class Expr(val tokens: List<Token>, val pos: Pos) : StringChunk
    }

    private fun loadStringTokens(startQuotePos: Pos, delimiter: Char): Token {
        if (!interpolationEnabled) return loadStringToken(delimiter)
        val tokenPos = currentPos

        val chunks = mutableListOf<StringChunk>()
        val literal = StringBuilder()
        var newlineDetected = false
        var hasInterpolation = false

        fun flushLiteralChunk() {
            if (literal.isNotEmpty()) {
                chunks += StringChunk.Literal(literal.toString(), tokenPos)
                literal.clear()
            }
        }

        while (currentChar != delimiter) {
            if (pos.end) throw ScriptError(startQuotePos, "unterminated string started there")
            when (currentChar) {
                '\\' -> {
                    pos.advance() ?: raise("unterminated string")
                    when (currentChar) {
                        'n' -> {
                            literal.append('\n'); pos.advance()
                        }

                        'r' -> {
                            literal.append('\r'); pos.advance()
                        }

                        't' -> {
                            literal.append('\t'); pos.advance()
                        }

                        delimiter -> {
                            literal.append(delimiter); pos.advance()
                        }

                        '\\' -> {
                            literal.append('\\'); pos.advance()
                        }

                        'u' -> {
                            literal.append(loadUnicodeEscape(tokenPos))
                        }

                        '$' -> {
                            // Backslash-escaped dollar is always literal.
                            literal.append('$')
                            pos.advance()
                        }

                        else -> {
                            literal.append('\\').append(currentChar)
                            pos.advance()
                        }
                    }
                }

                '$' -> {
                    pos.advance()
                    when {
                        currentChar == '$' -> {
                            // $$ -> literal '$'
                            literal.append('$')
                            pos.advance()
                        }

                        currentChar == '{' -> {
                            hasInterpolation = true
                            flushLiteralChunk()
                            val exprStart = pos.toPos()
                            pos.advance() // consume '{'
                            val exprText = readInterpolationExprText(startQuotePos)
                            val exprTokens = parseEmbeddedExpressionTokens(exprText, exprStart)
                            chunks += StringChunk.Expr(exprTokens, exprStart)
                        }

                        idFirstChars(currentChar) -> {
                            hasInterpolation = true
                            flushLiteralChunk()
                            val idPos = pos.toPos()
                            val id = loadChars(idNextChars)
                            val idToken = Token(id, idPos, Token.Type.ID)
                            chunks += StringChunk.Expr(listOf(idToken), idPos)
                        }

                        else -> {
                            // Bare '$' before non-interpolation text stays literal.
                            literal.append('$')
                        }
                    }
                }

                '\n', '\r' -> {
                    newlineDetected = true
                    literal.append(currentChar)
                    pos.advance()
                }

                else -> {
                    literal.append(currentChar)
                    pos.advance()
                }
            }
        }
        pos.advance() // closing quote

        if (!hasInterpolation) {
            val result = literal.toString().let { if (newlineDetected) fixMultilineStringLiteral(it) else it }
            return Token(result, tokenPos, Token.Type.STRING)
        }

        flushLiteralChunk()
        if (chunks.isEmpty()) {
            return Token("", tokenPos, Token.Type.STRING)
        }
        val expanded = mutableListOf<Token>()
        expanded += Token("(", tokenPos, Token.Type.LPAREN)
        expanded += Token("", tokenPos, Token.Type.STRING)
        var emittedPieces = 1
        for (chunk in chunks) {
            val pieceTokens = when (chunk) {
                is StringChunk.Literal -> {
                    if (chunk.text.isEmpty()) emptyList()
                    else listOf(Token(chunk.text, chunk.pos, Token.Type.STRING))
                }
                is StringChunk.Expr -> {
                    if (chunk.tokens.isEmpty()) throw ScriptError(chunk.pos, "empty interpolation expression")
                    if (chunk.tokens.size == 1) {
                        chunk.tokens
                    } else {
                        listOf(Token("(", chunk.pos, Token.Type.LPAREN)) +
                            chunk.tokens +
                            listOf(Token(")", chunk.pos, Token.Type.RPAREN))
                    }
                }
            }
            if (pieceTokens.isEmpty()) continue
            if (emittedPieces > 0) expanded += Token("+", tokenPos, Token.Type.PLUS)
            expanded += pieceTokens
            emittedPieces++
        }
        expanded += Token(")", tokenPos, Token.Type.RPAREN)

        val first = expanded.first()
        for (i in 1 until expanded.size) bufferedTokens.addLast(expanded[i])
        return first
    }

    private fun parseEmbeddedExpressionTokens(text: String, exprPos: Pos): List<Token> {
        val tokens = try {
            parseLyng(Source(exprPos.source.fileName, text), interpolationEnabled)
        } catch (e: ScriptError) {
            throw ScriptError(remapEmbeddedPos(exprPos, e.pos), e.errorMessage, e)
        }
        if (tokens.isEmpty()) return emptyList()
        val withoutEof = if (tokens.last().type == Token.Type.EOF) tokens.dropLast(1) else tokens
        return withoutEof.map { it.copy(pos = remapEmbeddedPos(exprPos, it.pos)) }
    }

    private fun remapEmbeddedPos(exprPos: Pos, embeddedPos: Pos): Pos {
        if (embeddedPos.line < 0 || embeddedPos.column < 0) return exprPos
        val line = exprPos.line + embeddedPos.line
        val column = if (embeddedPos.line == 0) exprPos.column + embeddedPos.column else embeddedPos.column
        return Pos(exprPos.source, line, column)
    }

    private fun readInterpolationExprText(start: Pos): String {
        val out = StringBuilder()
        var depth = 1
        while (!pos.end) {
            val ch = currentChar
            if (ch == '"' || ch == '`') {
                appendQuoted(out, ch)
                continue
            }
            if (ch == '\'') {
                appendQuoted(out, '\'')
                continue
            }
            if (ch == '/' && peekChar() == '/') {
                out.append('/').append('/')
                pos.advance()
                pos.advance()
                while (!pos.end && currentChar != '\n') {
                    out.append(currentChar)
                    pos.advance()
                }
                continue
            }
            if (ch == '/' && peekChar() == '*') {
                out.append('/').append('*')
                pos.advance()
                pos.advance()
                var closed = false
                while (!pos.end) {
                    val c = currentChar
                    if (c == '*' && peekChar() == '/') {
                        out.append('*').append('/')
                        pos.advance()
                        pos.advance()
                        closed = true
                        break
                    }
                    out.append(c)
                    pos.advance()
                }
                if (!closed) throw ScriptError(start, "unterminated block comment in interpolation")
                continue
            }
            if (ch == '{') {
                depth++
                out.append(ch)
                pos.advance()
                continue
            }
            if (ch == '}') {
                depth--
                if (depth == 0) {
                    pos.advance() // consume closing '}'
                    return out.toString()
                }
                out.append(ch)
                pos.advance()
                continue
            }
            out.append(ch)
            pos.advance()
        }
        throw ScriptError(start, "unterminated interpolation expression")
    }

    private fun appendQuoted(out: StringBuilder, quote: Char) {
        out.append(quote)
        pos.advance()
        while (!pos.end) {
            val c = currentChar
            out.append(c)
            pos.advance()
            if (c == '\\') {
                if (!pos.end) {
                    out.append(currentChar)
                    pos.advance()
                }
                continue
            }
            if (c == quote) return
        }
    }

    private fun peekChar(): Char {
        if (pos.end) return 0.toChar()
        val mark = pos.toPos()
        pos.advance()
        val result = currentChar
        pos.resetTo(mark)
        return result
    }

    private fun loadUnicodeEscape(start: Pos): Char {
        // Called when currentChar points to 'u' right after a backslash.
        if (currentChar != 'u') throw ScriptError(currentPos, "expected unicode escape marker: u")
        pos.advance() ?: throw ScriptError(start, "unterminated unicode escape")

        var code = 0
        repeat(4) {
            val ch = currentChar
            if (ch !in hexDigits) {
                throw ScriptError(currentPos, "invalid unicode escape sequence, expected 4 hex digits")
            }
            code = (code shl 4) + ch.digitToInt(16)
            pos.advance()
        }
        return code.toChar()
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

    private fun loadToEndOfLine(): String {
        val result = StringBuilder()
        // Read characters up to but not including the line break
        while (!pos.end && pos.currentChar != '\n') {
            result.append(pos.currentChar)
            pos.advance()
        }
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

    init {
        // skip shebang
        if (pos.readFragment("#!"))
            loadToEndOfLine()
    }

}
