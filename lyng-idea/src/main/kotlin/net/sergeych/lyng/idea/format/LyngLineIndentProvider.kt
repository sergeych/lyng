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
import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.util.FormattingUtils

/**
 * Lightweight indentation provider for Lyng.
 *
 * Rules (heuristic, text-based):
 * - New lines after an opening brace/paren increase indent level.
 * - Lines starting with a closing brace/paren decrease indent level by one.
 * - Keeps previous non-empty line's indent as baseline otherwise.
 */
class LyngLineIndentProvider : LineIndentProvider {
    override fun getLineIndent(project: com.intellij.openapi.project.Project, editor: Editor, language: Language?, offset: Int): String? {
        if (language != null && language != LyngLanguage) return null
        val doc = editor.document
        PsiDocumentManager.getInstance(project).commitDocument(doc)

        val options = CodeStyle.getIndentOptions(project, doc)

        val line = doc.getLineNumberSafe(offset)
        return FormattingUtils.computeDesiredIndent(project, doc, line)
    }

    override fun isSuitableFor(language: Language?): Boolean = language == null || language == LyngLanguage

    private fun Document.getLineNumberSafe(offset: Int): Int =
        getLineNumber(offset.coerceIn(0, textLength))

    private fun Document.getLineText(line: Int): String {
        if (line < 0 || line >= lineCount) return ""
        val start = getLineStartOffset(line)
        val end = getLineEndOffset(line)
        return getText(TextRange(start, end))
    }

    private fun indentUnit(options: IndentOptions): String =
        if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE.coerceAtLeast(1))

    private fun indentOfLine(doc: Document, line: Int): String {
        val s = doc.getLineText(line)
        val i = s.indexOfFirst { !it.isWhitespace() }
        return if (i <= 0) s.takeWhile { it == ' ' || it == '\t' } else s.substring(0, i)
    }

    private fun countIndentUnits(indent: String, options: IndentOptions): Int {
        if (indent.isEmpty()) return 0
        if (options.USE_TAB_CHARACTER) return indent.count { it == '\t' }
        val size = options.INDENT_SIZE.coerceAtLeast(1)
        var spaces = 0
        for (ch in indent) spaces += if (ch == '\t') size else 1
        return spaces / size
    }

}
