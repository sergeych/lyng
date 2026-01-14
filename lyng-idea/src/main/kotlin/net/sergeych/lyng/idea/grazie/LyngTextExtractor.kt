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
package net.sergeych.lyng.idea.grazie

import com.intellij.grazie.text.TextContent
import com.intellij.grazie.text.TextContent.TextDomain
import com.intellij.grazie.text.TextExtractor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import net.sergeych.lyng.idea.highlight.LyngTokenTypes
import net.sergeych.lyng.idea.settings.LyngFormatterSettings
import net.sergeych.lyng.idea.spell.LyngSpellIndex

/**
 * Provides Grazie with extractable text for Lyng PSI elements.
 * We return text for identifiers, comments, and (optionally) string literals.
 * printf-like specifiers are filtered by the Grammar strategy via stealth ranges.
 */
class LyngTextExtractor : TextExtractor() {
    private val log = Logger.getInstance(LyngTextExtractor::class.java)
    @Volatile private var loggedOnce = false
    private val seen: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    override fun buildTextContent(element: PsiElement, allowedDomains: Set<TextDomain>): TextContent? {
        val type = element.node?.elementType ?: return null
        if (!loggedOnce) {
            loggedOnce = true
            log.info("LyngTextExtractor active; allowedDomains=${allowedDomains.joinToString()}")
        }
        val settings = LyngFormatterSettings.getInstance(element.project)
        val file = element.containingFile
        val index = if (file != null) LyngSpellIndex.getUpToDate(file) else null
        val r = element.textRange

        // Decide target domain by intersection with our MiniAst-driven index; prefer comments > strings > identifiers
        var domain: TextDomain? = null
        if (index != null && r != null) {
            if (index.comments.any { it.intersects(r) }) domain = TextDomain.COMMENTS
            else if (index.strings.any { it.intersects(r) } && settings.spellCheckStringLiterals) domain = TextDomain.LITERALS
            else if (index.identifiers.any { it.contains(r) }) domain = if (settings.grazieTreatIdentifiersAsComments) TextDomain.COMMENTS else TextDomain.DOCUMENTATION
        } else {
            // Fallback to token type if index is not ready (rare timing), mostly for comments
            domain = when (type) {
                LyngTokenTypes.LINE_COMMENT, LyngTokenTypes.BLOCK_COMMENT -> TextDomain.COMMENTS
                else -> null
            }
        }
        if (domain == null) return null

        // If literals aren't requested but fallback is enabled, route strings as COMMENTS
        if (domain == TextDomain.LITERALS && !allowedDomains.contains(TextDomain.LITERALS) && settings.grazieTreatLiteralsAsComments) {
            domain = TextDomain.COMMENTS
        }
        if (!allowedDomains.contains(domain)) {
            if (seen.add("deny-${domain.name}")) {
                log.info("LyngTextExtractor: domain ${domain.name} not in allowedDomains; skipping")
            }
            return null
        }
        return try {
            // Try common factory names across versions
            val methods = TextContent::class.java.methods.filter { it.name == "psiFragment" }
            val built: TextContent? = when {
                // Try psiFragment(PsiElement, TextDomain)
                methods.any { it.parameterCount == 2 && it.parameterTypes[0].name.contains("PsiElement") } -> {
                    val m = methods.first { it.parameterCount == 2 && it.parameterTypes[0].name.contains("PsiElement") }
                    @Suppress("UNCHECKED_CAST")
                    (m.invoke(null, element, domain) as? TextContent)?.also {
                        if (seen.add("ok-${domain.name}")) log.info("LyngTextExtractor: provided ${domain.name} for ${type} via psiFragment(element, domain)")
                    }
                }
                // Try psiFragment(TextDomain, PsiElement)
                methods.any { it.parameterCount == 2 && it.parameterTypes[0].name.endsWith("TextDomain") } -> {
                    val m = methods.first { it.parameterCount == 2 && it.parameterTypes[0].name.endsWith("TextDomain") }
                    @Suppress("UNCHECKED_CAST")
                    (m.invoke(null, domain, element) as? TextContent)?.also {
                        if (seen.add("ok-${domain.name}")) log.info("LyngTextExtractor: provided ${domain.name} for ${type} via psiFragment(domain, element)")
                    }
                }
                else -> null
            }
            built
        } catch (e: Throwable) {
            log.info("LyngTextExtractor: failed to build TextContent: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
