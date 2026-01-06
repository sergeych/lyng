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

package net.sergeych.lyng.idea.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.psi.tree.IElementType

class LyngSyntaxHighlighter : SyntaxHighlighter {
    override fun getHighlightingLexer(): Lexer = LyngLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        LyngTokenTypes.KEYWORD -> pack(LyngHighlighterColors.KEYWORD)
        LyngTokenTypes.STRING -> pack(LyngHighlighterColors.STRING)
        LyngTokenTypes.NUMBER -> pack(LyngHighlighterColors.NUMBER)
        LyngTokenTypes.LINE_COMMENT -> pack(LyngHighlighterColors.LINE_COMMENT)
        LyngTokenTypes.BLOCK_COMMENT -> pack(LyngHighlighterColors.BLOCK_COMMENT)
        LyngTokenTypes.PUNCT -> pack(LyngHighlighterColors.PUNCT)
        LyngTokenTypes.IDENTIFIER -> pack(LyngHighlighterColors.IDENTIFIER)
        LyngTokenTypes.LABEL -> pack(LyngHighlighterColors.LABEL)
        else -> emptyArray()
    }

    private fun pack(vararg keys: TextAttributesKey): Array<TextAttributesKey> = arrayOf(*keys)
}
