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

package net.sergeych.lyng.idea.util

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import net.sergeych.lyng.format.LyngFormatConfig
import net.sergeych.lyng.format.LyngFormatter

object FormattingUtils {
    fun computeDesiredIndent(project: Project, doc: Document, line: Int): String {
        val options = CodeStyle.getIndentOptions(project, doc)
        val start = 0
        val end = doc.getLineEndOffset(line)
        val snippet = doc.getText(TextRange(start, end))
        val lineText = if (line < doc.lineCount) {
            val ls = doc.getLineStartOffset(line)
            val le = doc.getLineEndOffset(line)
            doc.getText(TextRange(ls, le))
        } else ""
        val isBlankLine = lineText.trim().isEmpty()
        val snippetForCalc = if (isBlankLine) snippet + "x" else snippet
        val cfg = LyngFormatConfig(
            indentSize = options.INDENT_SIZE.coerceAtLeast(1),
            useTabs = options.USE_TAB_CHARACTER,
            continuationIndentSize = options.CONTINUATION_INDENT_SIZE.coerceAtLeast(options.INDENT_SIZE.coerceAtLeast(1)),
        )
        val formatted = LyngFormatter.reindent(snippetForCalc, cfg)
        val lastNl = formatted.lastIndexOf('\n')
        val lastLine = if (lastNl >= 0) formatted.substring(lastNl + 1) else formatted
        val wsLen = lastLine.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) lastLine.length else it }
        return lastLine.substring(0, wsLen)
    }

    fun findFirstNonWs(doc: Document, start: Int, end: Int): Int {
        var i = start
        val text = doc.charsSequence
        while (i < end) {
            val ch = text[i]
            if (ch != ' ' && ch != '\t') break
            i++
        }
        return i
    }
}
