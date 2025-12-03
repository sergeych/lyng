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
package net.sergeych.lyng.idea.grazie

import com.intellij.grazie.grammar.strategy.GrammarCheckingStrategy
import com.intellij.grazie.grammar.strategy.GrammarCheckingStrategy.TextDomain
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import net.sergeych.lyng.idea.highlight.LyngTokenTypes
import net.sergeych.lyng.idea.settings.LyngFormatterSettings
import net.sergeych.lyng.idea.spell.LyngSpellIndex

/**
 * Grazie/Natural Languages strategy for Lyng.
 *
 * - Comments: checked as natural language (TextDomain.COMMENTS)
 * - String literals: optionally checked (setting), skipping printf-like specifiers via stealth ranges (TextDomain.LITERALS)
 * - Identifiers (non-keywords): checked under TextDomain.CODE so "Process code" controls apply
 * - Keywords: skipped
 */
class LyngGrazieStrategy : GrammarCheckingStrategy {

    private val log = Logger.getInstance(LyngGrazieStrategy::class.java)
    @Volatile private var loggedOnce = false
    @Volatile private var loggedFirstMatch = false
    private val seenTypes: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private fun legacySpellcheckerInstalled(): Boolean =
        PluginManagerCore.isPluginInstalled(PluginId.getId("com.intellij.spellchecker"))

    // Regex for printf-style specifiers: %[flags][width][.precision][length]type
    private val spec = Regex("%(?:[-+ #0]*(?:\\d+)?(?:\\.\\d+)?[a-zA-Z%])")

    override fun isMyContextRoot(element: PsiElement): Boolean {
        val type = element.node?.elementType
        val settings = LyngFormatterSettings.getInstance(element.project)
        val legacyPresent = legacySpellcheckerInstalled()
        if (type != null && seenTypes.size < 10) {
            val name = type.toString()
            if (seenTypes.add(name)) {
                log.info("LyngGrazieStrategy: saw PSI type=$name")
            }
        }
        if (!loggedOnce) {
            loggedOnce = true
            log.info("LyngGrazieStrategy activated: legacyPresent=$legacyPresent, preferGrazieForCommentsAndLiterals=${settings.preferGrazieForCommentsAndLiterals}, spellCheckStringLiterals=${settings.spellCheckStringLiterals}, grazieChecksIdentifiers=${settings.grazieChecksIdentifiers}")
        }

        val file = element.containingFile ?: return false
        val index = LyngSpellIndex.getUpToDate(file) ?: return false // Suspend until ready
        // To ensure Grazie asks TextExtractor for all leafs, accept any Lyng element once index is ready.
        // The extractor will decide per-range/domain what to actually provide.
        if (!loggedFirstMatch) {
            loggedFirstMatch = true
            log.info("LyngGrazieStrategy: enabling Grazie on all Lyng elements (index ready)")
        }
        return true
    }

    override fun getContextRootTextDomain(root: PsiElement): TextDomain {
        val type = root.node?.elementType
        val settings = LyngFormatterSettings.getInstance(root.project)
        val file = root.containingFile
        val index = if (file != null) LyngSpellIndex.getUpToDate(file) else null
        val r = root.textRange

        fun overlaps(list: List<TextRange>): Boolean = r != null && list.any { it.intersects(r) }

        return when (type) {
            LyngTokenTypes.LINE_COMMENT, LyngTokenTypes.BLOCK_COMMENT -> TextDomain.COMMENTS
            LyngTokenTypes.STRING -> if (settings.grazieTreatLiteralsAsComments) TextDomain.COMMENTS else TextDomain.LITERALS
            LyngTokenTypes.IDENTIFIER -> {
                // For Grazie-only reliability in 243, route identifiers via COMMENTS when configured
                if (settings.grazieTreatIdentifiersAsComments && index != null && r != null && overlaps(index.identifiers))
                    TextDomain.COMMENTS
                else TextDomain.PLAIN_TEXT
            }
            else -> TextDomain.PLAIN_TEXT
        }
    }

    // Note: do not override getLanguageSupport to keep compatibility with 243 API

    override fun getStealthyRanges(root: PsiElement, text: CharSequence): java.util.LinkedHashSet<IntRange> {
        val result = LinkedHashSet<IntRange>()
        val type = root.node?.elementType
        if (type == LyngTokenTypes.STRING) {
            if (!shouldCheckLiterals(root)) {
                // Hide the entire string when literals checking is disabled by settings
                result += (0 until text.length)
                return result
            }
            // Hide printf-like specifiers in strings
            val (start, end) = stripQuotesBounds(text)
            if (end > start) {
                val content = text.subSequence(start, end)
                for (m in spec.findAll(content)) {
                    val ms = start + m.range.first
                    val me = start + m.range.last
                    result += (ms..me)
                }
                if (result.isNotEmpty()) {
                    log.debug("LyngGrazieStrategy: hidden ${result.size} printf specifier ranges in string literal")
                }
            }
        }
        return result
    }

    override fun isEnabledByDefault(): Boolean = true

    private fun shouldCheckLiterals(root: PsiElement): Boolean =
        LyngFormatterSettings.getInstance(root.project).spellCheckStringLiterals

    private fun stripQuotesBounds(text: CharSequence): Pair<Int, Int> {
        if (text.length < 2) return 0 to text.length
        val first = text.first()
        val last = text.last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\''))
            1 to (text.length - 1) else (0 to text.length)
    }
}
