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

package net.sergeych.lyng

class CompilerContext(val tokens: List<Token>) {
    val labels = mutableSetOf<String>()

    var breakFound = false

    var loopLevel = 0

    inline fun <T> parseLoop(f: () -> T): Pair<Boolean, T> {
        if (++loopLevel == 0) breakFound = false
        val result = f()
        return Pair(breakFound, result).also {
            --loopLevel
        }
    }

    var currentIndex = 0

    fun hasNext() = currentIndex < tokens.size
    fun hasPrevious() = currentIndex > 0
    fun next() =
        if (currentIndex < tokens.size) tokens[currentIndex++]
        else Token("", tokens.last().pos, Token.Type.EOF)

    fun previous() = if (!hasPrevious()) throw IllegalStateException("No previous token") else tokens[--currentIndex]

    fun savePos() = currentIndex
    fun restorePos(pos: Int) {
        currentIndex = pos
    }

    fun ensureLabelIsValid(pos: Pos, label: String) {
        if (label !in labels)
            throw ScriptError(pos, "Undefined label '$label'")
    }

    @Suppress("unused")
    fun requireId() = requireToken(Token.Type.ID, "identifier is required")

    fun requireToken(type: Token.Type, message: String = "required ${type.name}"): Token =
        next().also {
            if (type != it.type) throw ScriptError(it.pos, message)
        }

    @Suppress("unused")
    fun syntaxError(at: Pos, message: String = "Syntax error"): Nothing {
        throw ScriptError(at, message)
    }

    fun syntaxError(message: String = "Syntax error"): Nothing {
        throw ScriptError(currentPos(), message)
    }

    fun currentPos(): Pos {
        if (tokens.isEmpty()) return Pos.builtIn
        val idx = when {
            currentIndex < 0 -> 0
            currentIndex >= tokens.size -> tokens.size - 1
            else -> currentIndex
        }
        return tokens[idx].pos
    }

    /**
     * If the next token is identifier `name`, skip it and return `true`.
     * else leave where it is and return `false`
     */
    fun skipId(name: String): Boolean {
        current().let { t ->
            if (t.type == Token.Type.ID && t.value == name) {
                next()
                return true
            }
        }
        return false
    }

    /**
     * Skips next token if its type is `tokenType`, returns `true` if so.
     * @param errorMessage message to throw if next token is not `tokenType`
     * @param isOptional if `true` and token is not of `tokenType`, just return `false` and does not skip it
     * @return `true` if the token was skipped
     * @throws ScriptError if [isOptional] is `false` and next token is not of [tokenType]
     */
    fun skipTokenOfType(
        tokenType: Token.Type,
        errorMessage: String = "expected ${tokenType.name}",
        isOptional: Boolean = false
    ): Boolean {
        val t = next()
        return if (t.type != tokenType) {
            if (!isOptional) {
                println("unexpected: $t (needed $tokenType)")
                throw ScriptError(t.pos, errorMessage)
            } else {
                previous()
                false
            }
        } else true
    }

    /**
     * If next token is one of these types, skip it.
     * @return true if token was found and skipped
     */
    fun skipNextIf(vararg types: Token.Type): Boolean {
        val t = next()
        return if (t.type in types)
            true
        else {
            previous()
            false
        }
    }

    @Suppress("unused")
    fun skipTokens(vararg tokenTypes: Token.Type) {
        while (next().type in tokenTypes) { /**/
        }
        previous()
    }

    fun nextNonWhitespace(): Token {
        while (true) {
            val t = next()
            if (t.type !in wstokens) return t
        }
    }

    /**
     * Find next non-whitespace token and return it. The token is not extracted,
     * is will be returned on [next] call.
     * @return next non-whitespace token without extracting it from tokens list
     */
    fun peekNextNonWhitespace(): Token {
        while (true) {
            val t = next()
            if (t.type !in wstokens) {
                previous()
                return t
            }
        }
    }


    inline fun ifNextIs(typeId: Token.Type, f: (Token) -> Unit): Boolean {
        val t = next()
        return if (t.type == typeId) {
            f(t)
            true
        } else {
            previous()
            false
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun addBreak() {
        breakFound = true
    }

    /**
     * Return value of the next token if it is an identifier, null otherwise.
     * Does not change position.
     */
    @Suppress("unused")
    fun nextIdValue(): String? {
        return if (hasNext()) {
            val nt = tokens[currentIndex]
            if (nt.type == Token.Type.ID)
                nt.value
            else null
        } else null
    }

    @Suppress("unused")
    fun current(): Token = tokens[currentIndex]

    /**
     * If the token at current position plus offset (could be negative) exists, returns it, otherwise returns null.
     */
    @Suppress("unused")
    fun atOffset(offset: Int): Token? =
        if (currentIndex + offset in tokens.indices) tokens[currentIndex + offset] else null

    fun matchQualifiers(keyword: String, vararg qualifiers: String): Boolean {
        val pos = savePos()
        var count = 0
        while (count < qualifiers.size) {
            val t = next()
            when (t.type) {
                Token.Type.ID -> {
                    if (t.value in qualifiers) count++
                    else {
                        restorePos(pos); return false
                    }
                }

                Token.Type.MULTILINE_COMMENT, Token.Type.SINLGE_LINE_COMMENT, Token.Type.NEWLINE -> {}
                else -> {
                    restorePos(pos); return false
                }
            }
        }
        val t = next()
        if (t.type == Token.Type.ID && t.value == keyword) {
            return true
        } else {
            restorePos(pos)
            return false
        }
    }

    /**
     * Skip newlines and comments. Returns (and reads) first non-whitespace token.
     * Note that [Token.Type.EOF] is not considered a whitespace token.
     */
    fun skipWsTokens(): Token {
        while (current().type in wstokens) {
            next()
        }
        return current()
    }

    companion object {
        val wstokens = setOf(Token.Type.NEWLINE, Token.Type.MULTILINE_COMMENT, Token.Type.SINLGE_LINE_COMMENT)
    }
}