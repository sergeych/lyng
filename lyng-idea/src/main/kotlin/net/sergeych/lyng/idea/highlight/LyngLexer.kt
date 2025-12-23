/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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

/*
 * Minimal hand-written lexer for Lyng token highlighting
 */
package net.sergeych.lyng.idea.highlight

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

class LyngLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0
    private var myTokenStart: Int = 0
    private var myTokenEnd: Int = 0
    private var myTokenType: IElementType? = null

    private val keywords = setOf(
        "fun", "val", "var", "class", "type", "import", "as",
        "if", "else", "for", "while", "return", "true", "false", "null",
        "when", "in", "is", "break", "continue", "try", "catch", "finally",
        "get", "set"
    )

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.myTokenStart = startOffset
        this.myTokenEnd = startOffset
        this.myTokenType = null
        advance()
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = myTokenType

    override fun getTokenStart(): Int = myTokenStart

    override fun getTokenEnd(): Int = myTokenEnd

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        if (myTokenEnd >= endOffset) {
            myTokenType = null
            return
        }
        var i = if (myTokenEnd == 0) startOffset else myTokenEnd
        // Skip nothing; set start
        myTokenStart = i
        if (i >= endOffset) { myTokenType = null; return }

        val ch = buffer[i]

        // Whitespace
        if (ch.isWhitespace()) {
            i++
            while (i < endOffset && buffer[i].isWhitespace()) i++
            myTokenEnd = i
            myTokenType = LyngTokenTypes.WHITESPACE
            return
        }

        // Line comment //...
        if (ch == '/' && i + 1 < endOffset && buffer[i + 1] == '/') {
            i += 2
            while (i < endOffset && buffer[i] != '\n' && buffer[i] != '\r') i++
            myTokenEnd = i
            myTokenType = LyngTokenTypes.LINE_COMMENT
            return
        }

        // Block comment /* ... */
        if (ch == '/' && i + 1 < endOffset && buffer[i + 1] == '*') {
            i += 2
            while (i + 1 < endOffset && !(buffer[i] == '*' && buffer[i + 1] == '/')) i++
            if (i + 1 < endOffset) i += 2 // consume */
            myTokenEnd = i
            myTokenType = LyngTokenTypes.BLOCK_COMMENT
            return
        }

        // String "..." with simple escape handling
        if (ch == '"') {
            i++
            while (i < endOffset) {
                val c = buffer[i]
                if (c == '\\') { // escape
                    i += 2
                    continue
                }
                if (c == '"') { i++; break }
                i++
            }
            myTokenEnd = i
            myTokenType = LyngTokenTypes.STRING
            return
        }

        // Number
        if (ch.isDigit()) {
            i++
            var hasDot = false
            while (i < endOffset) {
                val c = buffer[i]
                if (c.isDigit()) { i++; continue }
                if (c == '.' && !hasDot) { hasDot = true; i++; continue }
                break
            }
            myTokenEnd = i
            myTokenType = LyngTokenTypes.NUMBER
            return
        }

        // Identifier / keyword
        if (ch.isIdentifierStart()) {
            i++
            while (i < endOffset && buffer[i].isIdentifierPart()) i++
            myTokenEnd = i
            val text = buffer.subSequence(myTokenStart, myTokenEnd).toString()
            myTokenType = if (text in keywords) LyngTokenTypes.KEYWORD else LyngTokenTypes.IDENTIFIER
            return
        }

        // Punctuation
        if (isPunct(ch)) {
            i++
            myTokenEnd = i
            myTokenType = LyngTokenTypes.PUNCT
            return
        }

        // Fallback bad char
        myTokenEnd = i + 1
        myTokenType = LyngTokenTypes.BAD_CHAR
    }

    private fun Char.isWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'
    private fun Char.isDigit(): Boolean = this in '0'..'9'
    private fun Char.isIdentifierStart(): Boolean = this == '_' || this.isLetter()
    private fun Char.isIdentifierPart(): Boolean = this.isIdentifierStart() || this.isDigit()
    private fun isPunct(c: Char): Boolean = c in setOf('(', ')', '{', '}', '[', ']', '.', ',', ';', ':', '+', '-', '*', '/', '%', '=', '<', '>', '!', '?', '&', '|', '^', '~')
}
