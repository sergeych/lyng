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
package net.sergeych.lyng.idea.format

import com.intellij.application.options.CodeStyle
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.impl.source.codeStyle.PreFormatProcessor
import net.sergeych.lyng.format.LyngFormatConfig
import net.sergeych.lyng.format.LyngFormatter
import net.sergeych.lyng.idea.LyngLanguage

/**
 * Idempotent indentation fixer executed by Reformat Code before formatting.
 * It walks all lines in the affected range and applies exact indentation using
 * CodeStyleManager.adjustLineIndent(), which delegates to our LineIndentProvider.
 */
class LyngPreFormatProcessor : PreFormatProcessor {
    override fun process(element: ASTNode, range: TextRange): TextRange {
        val file = element.psi?.containingFile ?: return range
        if (file.language != LyngLanguage) return range
        val project: Project = file.project
        val doc = file.viewProvider.document ?: return range
        val psiDoc = com.intellij.psi.PsiDocumentManager.getInstance(project)
        val options = CodeStyle.getIndentOptions(project, doc)

        val settings = net.sergeych.lyng.idea.settings.LyngFormatterSettings.getInstance(project)
        // When both spacing and wrapping are OFF, still fix indentation for the whole file to
        // guarantee visible changes on Reformat Code.
        val runFullFileIndent = !settings.enableSpacing && !settings.enableWrapping
        // Maintain a working range and a modification flag to avoid stale offsets after replacements
        var modified = false
        fun fullRange(): TextRange = TextRange(0, doc.textLength)
        var workingRange: TextRange = range.intersection(fullRange()) ?: fullRange()

        val startLine = if (runFullFileIndent) 0 else doc.getLineNumber(workingRange.startOffset)
        val endLine = if (runFullFileIndent) (doc.lineCount - 1).coerceAtLeast(0)
        else doc.getLineNumber(workingRange.endOffset.coerceAtMost(doc.textLength))

        fun codePart(s: String): String {
            val idx = s.indexOf("//")
            return if (idx >= 0) s.substring(0, idx) else s
        }

        // Pre-scan to compute balances up to startLine
        var blockLevel = 0
        var parenBalance = 0
        var bracketBalance = 0
        for (ln in 0 until startLine) {
            val text = doc.getText(TextRange(doc.getLineStartOffset(ln), doc.getLineEndOffset(ln)))
            for (ch in codePart(text)) when (ch) {
                '{' -> blockLevel++
                '}' -> if (blockLevel > 0) blockLevel--
                '(' -> parenBalance++
                ')' -> if (parenBalance > 0) parenBalance--
                '[' -> bracketBalance++
                ']' -> if (bracketBalance > 0) bracketBalance--
            }
        }

        // Re-indent each line deterministically (idempotent). We avoid any content
        // rewriting here to prevent long-running passes or re-entrant formatting.
        for (line in startLine..endLine) {
            val lineStart = doc.getLineStartOffset(line)
            // adjustLineIndent delegates to our LineIndentProvider which computes
            // indentation from scratch; this is safe and idempotent
            if (file.context == null) {
                try {
                    CodeStyleManager.getInstance(project).adjustLineIndent(file, lineStart)
                } catch (e: Exception) {
                    // Log as debug because this can be called many times during reformat
                    // and we don't want to spam warnings if it's a known platform issue with injections
                }
            }

            // After indentation, update block/paren/bracket balances using the current line text
            val lineEnd = doc.getLineEndOffset(line)
            val text = doc.getText(TextRange(lineStart, lineEnd))
            val code = codePart(text)
            for (ch in code) when (ch) {
                '{' -> blockLevel++
                '}' -> if (blockLevel > 0) blockLevel--
                '(' -> parenBalance++
                ')' -> if (parenBalance > 0) parenBalance--
                '[' -> bracketBalance++
                ']' -> if (bracketBalance > 0) bracketBalance--
            }
        }
        // If both spacing and wrapping are OFF, explicitly reindent the text using core formatter to
        // guarantee indentation is fixed even when the platform doesn't rewrite whitespace by itself.
        if (!settings.enableSpacing && !settings.enableWrapping) {
            val cfg = LyngFormatConfig(
                indentSize = options.INDENT_SIZE.coerceAtLeast(1),
                useTabs = options.USE_TAB_CHARACTER,
                continuationIndentSize = options.CONTINUATION_INDENT_SIZE.coerceAtLeast(options.INDENT_SIZE.coerceAtLeast(1)),
            )
            val full = fullRange()
            val r = if (runFullFileIndent) full else workingRange.intersection(full) ?: full
            val text = doc.getText(r)
            val formatted = LyngFormatter.reindent(text, cfg)
            if (formatted != text) {
                doc.replaceString(r.startOffset, r.endOffset, formatted)
                modified = true
                psiDoc.commitDocument(doc)
                workingRange = fullRange()
            }
        }

        // Optionally apply spacing using the core formatter if enabled in settings (wrapping stays off)
        if (settings.enableSpacing) {
            val cfg = LyngFormatConfig(
                indentSize = options.INDENT_SIZE.coerceAtLeast(1),
                useTabs = options.USE_TAB_CHARACTER,
                continuationIndentSize = options.CONTINUATION_INDENT_SIZE.coerceAtLeast(options.INDENT_SIZE.coerceAtLeast(1)),
                applySpacing = true,
                applyWrapping = false,
            )
            val safe = workingRange.intersection(fullRange()) ?: fullRange()
            val text = doc.getText(safe)
            val formatted = LyngFormatter.format(text, cfg)
            if (formatted != text) {
                doc.replaceString(safe.startOffset, safe.endOffset, formatted)
                modified = true
                psiDoc.commitDocument(doc)
                workingRange = fullRange()
            }
        }
        // Optionally apply wrapping (after spacing) when enabled
        if (settings.enableWrapping) {
            val cfg = LyngFormatConfig(
                indentSize = options.INDENT_SIZE.coerceAtLeast(1),
                useTabs = options.USE_TAB_CHARACTER,
                continuationIndentSize = options.CONTINUATION_INDENT_SIZE.coerceAtLeast(options.INDENT_SIZE.coerceAtLeast(1)),
                applySpacing = settings.enableSpacing,
                applyWrapping = true,
            )
            val safe2 = workingRange.intersection(fullRange()) ?: fullRange()
            val text2 = doc.getText(safe2)
            val wrapped = LyngFormatter.format(text2, cfg)
            if (wrapped != text2) {
                doc.replaceString(safe2.startOffset, safe2.endOffset, wrapped)
                modified = true
                psiDoc.commitDocument(doc)
                workingRange = fullRange()
            }
        }
        // Return a safe range for the formatter to continue with, preventing stale offsets
        return if (modified) fullRange() else (range.intersection(fullRange()) ?: fullRange())
    }
}
