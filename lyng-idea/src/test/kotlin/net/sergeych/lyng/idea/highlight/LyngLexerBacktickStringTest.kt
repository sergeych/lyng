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

package net.sergeych.lyng.idea.highlight

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LyngLexerBacktickStringTest {

    @Test
    fun backtickStringGetsStringTokenAndColor() {
        val lexer = LyngLexer()
        val source = """val json = `{"name":"lyng","doc":"use \`quotes\`"}`"""
        lexer.start(source, 0, source.length, 0)

        val tokens = mutableListOf<Pair<String, String>>()
        while (lexer.tokenType != null) {
            val tokenText = source.substring(lexer.tokenStart, lexer.tokenEnd)
            tokens += lexer.tokenType.toString() to tokenText
            lexer.advance()
        }

        assertEquals(
            listOf(
                "KEYWORD" to "val",
                "WHITESPACE" to " ",
                "IDENTIFIER" to "json",
                "WHITESPACE" to " ",
                "PUNCT" to "=",
                "WHITESPACE" to " ",
                "STRING" to "`{\"name\":\"lyng\",\"doc\":\"use \\`quotes\\`\"}`"
            ),
            tokens
        )

        val highlighter = LyngSyntaxHighlighter()
        assertArrayEquals(
            arrayOf(LyngHighlighterColors.STRING),
            highlighter.getTokenHighlights(LyngTokenTypes.STRING)
        )
    }
}
