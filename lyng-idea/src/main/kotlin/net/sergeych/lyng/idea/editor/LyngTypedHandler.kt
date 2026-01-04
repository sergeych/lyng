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
package net.sergeych.lyng.idea.editor

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import net.sergeych.lyng.format.BraceUtils
import net.sergeych.lyng.format.LyngFormatConfig
import net.sergeych.lyng.format.LyngFormatter
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.settings.LyngFormatterSettings
import net.sergeych.lyng.idea.util.FormattingUtils.computeDesiredIndent
import net.sergeych.lyng.idea.util.FormattingUtils.findFirstNonWs

class LyngTypedHandler : TypedHandlerDelegate() {
    private val log = Logger.getInstance(LyngTypedHandler::class.java)

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.language != LyngLanguage) return Result.CONTINUE
        
        if (c == '}') {
            val doc = editor.document
            PsiDocumentManager.getInstance(project).commitDocument(doc)

            val offset = editor.caretModel.offset
            val line = doc.getLineNumber((offset - 1).coerceAtLeast(0))
            if (line < 0) return Result.CONTINUE

            val rawLine = doc.getLineText(line)
            val code = rawLine.substringBefore("//").trim()
            if (code == "}") {
                val settings = LyngFormatterSettings.getInstance(project)
                if (settings.reindentClosedBlockOnEnter) {
                    reindentClosedBlockAroundBrace(project, file, doc, line)
                }
                // After block reindent, adjust line indent to what platform thinks (no-op in many cases)
                val lineStart = doc.getLineStartOffset(line)
                if (file.context == null) {
                    try {
                        CodeStyleManager.getInstance(project).adjustLineIndent(file, lineStart)
                    } catch (e: Exception) {
                        log.warn("Failed to adjust line indent for current line: ${e.message}")
                    }
                }
            }
        } else if (c == '/') {
            val doc = editor.document
            val offset = editor.caretModel.offset
            if (offset >= 2 && doc.getText(TextRange(offset - 2, offset)) == "*/") {
                PsiDocumentManager.getInstance(project).commitDocument(doc)
                val line = doc.getLineNumber(offset - 1)
                val lineStart = doc.getLineStartOffset(line)
                if (file.context == null) {
                    try {
                        CodeStyleManager.getInstance(project).adjustLineIndent(file, lineStart)
                    } catch (e: Exception) {
                        log.warn("Failed to adjust line indent for comment: ${e.message}")
                    }
                }

                // Manual application fallback
                val desired = computeDesiredIndent(project, doc, line)
                val lineEnd = doc.getLineEndOffset(line)
                val firstNonWs = findFirstNonWs(doc, lineStart, lineEnd)
                val currentIndentLen = firstNonWs - lineStart
                if (doc.getText(TextRange(lineStart, lineStart + currentIndentLen)) != desired) {
                    WriteCommandAction.runWriteCommandAction(project) {
                        doc.replaceString(lineStart, lineStart + currentIndentLen, desired)
                    }
                }
            }
        }
        return Result.CONTINUE
    }

    private fun reindentClosedBlockAroundBrace(project: Project, file: PsiFile, doc: Document, braceLine: Int) {
        val braceLineStart = doc.getLineStartOffset(braceLine)
        val braceLineEnd = doc.getLineEndOffset(braceLine)
        val rawBraceLine = doc.getText(TextRange(braceLineStart, braceLineEnd))
        val codeBraceLine = rawBraceLine.substringBefore("//")
        val closeRel = codeBraceLine.lastIndexOf('}')
        if (closeRel < 0) return
        val closeAbs = braceLineStart + closeRel

        val blockRange = BraceUtils.findEnclosingBlockRange(
            doc.charsSequence,
            closeAbs,
            includeTrailingNewline = true
        ) ?: return

        val options = CodeStyle.getIndentOptions(project, doc)
        val cfg = LyngFormatConfig(
            indentSize = options.INDENT_SIZE.coerceAtLeast(1),
            useTabs = options.USE_TAB_CHARACTER,
            continuationIndentSize = options.CONTINUATION_INDENT_SIZE.coerceAtLeast(options.INDENT_SIZE.coerceAtLeast(1)),
        )

        val whole = doc.text
        val updated = LyngFormatter.reindentRange(whole, blockRange, cfg, preserveBaseIndent = true)
        if (updated != whole) {
            WriteCommandAction.runWriteCommandAction(project) {
                doc.replaceString(0, doc.textLength, updated)
            }
            PsiDocumentManager.getInstance(project).commitDocument(doc)
            if (log.isDebugEnabled) log.debug("[LyngTyped] reindented closed block range=$blockRange")
        }
    }

    private fun Document.getLineText(line: Int): String {
        if (line < 0 || line >= lineCount) return ""
        val start = getLineStartOffset(line)
        val end = getLineEndOffset(line)
        return getText(TextRange(start, end))
    }
}
