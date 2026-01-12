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
package net.sergeych.lyng.idea.spell

// Avoid Tokenizers helper to keep compatibility; implement our own tokenizers
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.spellchecker.inspections.PlainTextSplitter
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.TokenConsumer
import com.intellij.spellchecker.tokenizer.Tokenizer
import net.sergeych.lyng.idea.settings.LyngFormatterSettings

/**
 * Spellchecking strategy for Lyng:
 * - Identifiers: checked as identifiers
 * - Comments: checked as plain text
 * - Keywords: skipped
 * - String literals: optional (controlled by settings), and we exclude printf-style format specifiers like
 *   %s, %d, %-12s, %0.2f, etc.
 */
class LyngSpellcheckingStrategy : SpellcheckingStrategy() {

    override fun getTokenizer(element: PsiElement): Tokenizer<*> {
        if (element is com.intellij.psi.PsiFile) return EMPTY_TOKENIZER

        val settings = LyngFormatterSettings.getInstance(element.project)
        val et = element.node?.elementType

        if (et == net.sergeych.lyng.idea.highlight.LyngTokenTypes.IDENTIFIER || et == net.sergeych.lyng.idea.highlight.LyngTokenTypes.LABEL) {
            return IDENTIFIER_TOKENIZER
        }
        if (et == net.sergeych.lyng.idea.highlight.LyngTokenTypes.LINE_COMMENT || et == net.sergeych.lyng.idea.highlight.LyngTokenTypes.BLOCK_COMMENT) {
            return COMMENT_TEXT_TOKENIZER
        }
        if (et == net.sergeych.lyng.idea.highlight.LyngTokenTypes.STRING && settings.spellCheckStringLiterals) {
            return STRING_WITH_PRINTF_EXCLUDES
        }

        return EMPTY_TOKENIZER
    }

    private object EMPTY_TOKENIZER : Tokenizer<PsiElement>() {
        override fun tokenize(element: PsiElement, consumer: TokenConsumer) {}
    }

    private object IDENTIFIER_TOKENIZER : Tokenizer<PsiElement>() {
        private val splitter = com.intellij.spellchecker.inspections.IdentifierSplitter.getInstance()
        override fun tokenize(element: PsiElement, consumer: TokenConsumer) {
            val text = element.text
            if (text.isNullOrEmpty()) return
            consumer.consumeToken(element, text, false, 0, TextRange(0, text.length), splitter)
        }
    }

    private object COMMENT_TEXT_TOKENIZER : Tokenizer<PsiElement>() {
        private val splitter = PlainTextSplitter.getInstance()
        override fun tokenize(element: PsiElement, consumer: TokenConsumer) {
            val text = element.text
            if (text.isNullOrEmpty()) return
            consumer.consumeToken(element, text, false, 0, TextRange(0, text.length), splitter)
        }
    }

    private object STRING_WITH_PRINTF_EXCLUDES : Tokenizer<PsiElement>() {
        private val splitter = PlainTextSplitter.getInstance()

        // Regex for printf-style specifiers: %[flags][width][.precision][length]type
        // This is intentionally permissive to skip common cases like %s, %d, %-12s, %08x, %.2f, %%
        private val SPEC = Regex("%(?:[-+ #0]*(?:\\d+)?(?:\\.\\d+)?[a-zA-Z%])")

        override fun tokenize(element: PsiElement, consumer: TokenConsumer) {
            // Check project settings whether literals should be spell-checked
            val settings = LyngFormatterSettings.getInstance(element.project)
            if (!settings.spellCheckStringLiterals) return

            val text = element.text
            if (text.isEmpty()) return

            // Try to strip surrounding quotes (simple lexer token for Lyng strings)
            var startOffsetInElement = 0
            var endOffsetInElement = text.length
            if (text.length >= 2 && (text.first() == '"' && text.last() == '"' || text.first() == '\'' && text.last() == '\'')) {
                startOffsetInElement = 1
                endOffsetInElement = text.length - 1
            }
            if (endOffsetInElement <= startOffsetInElement) return

            val content = text.substring(startOffsetInElement, endOffsetInElement)

            var last = 0
            for (m in SPEC.findAll(content)) {
                val ms = m.range.first
                val me = m.range.last + 1
                if (ms > last) {
                    val range = TextRange(startOffsetInElement + last, startOffsetInElement + ms)
                    consumer.consumeToken(element, text, false, 0, range, splitter)
                }
                last = me
            }
            if (last < content.length) {
                val range = TextRange(startOffsetInElement + last, startOffsetInElement + content.length)
                consumer.consumeToken(element, text, false, 0, range, splitter)
            }
        }
    }
}
