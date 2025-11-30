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
package net.sergeych.lyng.idea.editor

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import net.sergeych.lyng.format.LyngFormatConfig
import net.sergeych.lyng.format.LyngFormatter
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.settings.LyngFormatterSettings

/**
 * Helper for preparing reindented pasted text. EP wiring is deferred until API
 * signature is finalized for the target IDE build.
 */
object LyngPastePreProcessor {
    fun reindentForPaste(
        project: Project,
        editor: Editor,
        file: PsiFile,
        text: String
    ): String {
        if (file.language != LyngLanguage) return text
        val settings = LyngFormatterSettings.getInstance(project)
        if (!settings.reindentPastedBlocks) return text

        val doc = editor.document
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val options = CodeStyle.getIndentOptions(project, doc)
        val cfg = LyngFormatConfig(
            indentSize = options.INDENT_SIZE.coerceAtLeast(1),
            useTabs = options.USE_TAB_CHARACTER,
            continuationIndentSize = options.CONTINUATION_INDENT_SIZE.coerceAtLeast(options.INDENT_SIZE.coerceAtLeast(1)),
        )

        // Only apply smart paste when caret is in leading whitespace position of its line
        val caret = editor.caretModel.currentCaret
        val line = doc.getLineNumber(caret.offset.coerceIn(0, doc.textLength))
        if (line < 0 || line >= doc.lineCount) return text
        val lineStart = doc.getLineStartOffset(line)
        val firstNonWs = firstNonWhitespace(doc, lineStart, doc.getLineEndOffset(line))
        if (caret.offset > firstNonWs) return text

        val baseIndent = doc.charsSequence.subSequence(lineStart, caret.offset).toString()
        val reindented = LyngFormatter.reindent(text, cfg)
        // Prefix each non-empty line with base indent to preserve surrounding indentation
        val lines = reindented.split('\n')
        val sb = StringBuilder(reindented.length + lines.size * baseIndent.length)
        for ((idx, ln) in lines.withIndex()) {
            if (ln.isNotEmpty()) sb.append(baseIndent).append(ln) else sb.append(ln)
            if (idx < lines.lastIndex) sb.append('\n')
        }
        return sb.toString()
    }

    private fun firstNonWhitespace(doc: com.intellij.openapi.editor.Document, from: Int, to: Int): Int {
        val seq = doc.charsSequence
        var i = from
        while (i < to) {
            val ch = seq[i]
            if (ch != ' ' && ch != '\t') break
            i++
        }
        return i
    }
}
