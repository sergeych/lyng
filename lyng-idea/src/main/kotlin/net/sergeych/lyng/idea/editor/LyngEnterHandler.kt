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
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import net.sergeych.lyng.format.LyngFormatConfig
import net.sergeych.lyng.format.LyngFormatter
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.settings.LyngFormatterSettings

class LyngEnterHandler : EnterHandlerDelegate {
    private val log = Logger.getInstance(LyngEnterHandler::class.java)
    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?
    ): Result {
        if (file.language != LyngLanguage) return Result.Continue
        if (log.isDebugEnabled) log.debug("[LyngEnter] preprocess in Lyng file at caretOffset=${caretOffset.get()}")
        // Let the platform insert the newline; we will fix indentation in postProcessEnter.
        return Result.Continue
    }

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): Result {
        if (file.language != LyngLanguage) return Result.Continue
        val project = file.project
        val doc = editor.document
        val psiManager = PsiDocumentManager.getInstance(project)
        psiManager.commitDocument(doc)

        // Handle all carets independently to keep multi-caret scenarios sane
        for (caret in editor.caretModel.allCarets) {
            val line = doc.safeLineNumber(caret.offset)
            if (line < 0) continue
            if (log.isDebugEnabled) log.debug("[LyngEnter] postProcess at line=$line offset=${caret.offset}")
            // Adjust previous '}' line if applicable, then indent the current line using our rules
            adjustBraceAndCurrentIndent(project, file, doc, line)

            // After indenting, ensure caret sits after the computed indent of this new line
            moveCaretToIndentIfOnLeadingWs(editor, doc, file, line, caret)
        }
        return Result.Continue
    }

    private fun adjustBraceAndCurrentIndent(project: Project, file: PsiFile, doc: Document, currentLine: Int) {
        val prevLine = currentLine - 1
        if (prevLine >= 0) {
            val prevText = doc.getLineText(prevLine)
            val trimmed = prevText.trimStart()
            // consider only code part before // comment
            val code = trimmed.substringBefore("//").trim()
            if (code == "}") {
                // Optionally reindent the enclosed block without manually touching the '}' line.
                val settings = LyngFormatterSettings.getInstance(project)
                if (settings.reindentClosedBlockOnEnter) {
                    reindentClosedBlockAroundBrace(project, file, doc, prevLine)
                }
            }
        }
        // Adjust indent for the current (new) line
        val currentStart = doc.getLineStartOffsetSafe(currentLine)
        val csm = CodeStyleManager.getInstance(project)
        csm.adjustLineIndent(file, currentStart)

        // Fallback: if the platform didn't physically insert indentation, compute it from our formatter and apply
        val lineStart = doc.getLineStartOffset(currentLine)
        val lineEnd = doc.getLineEndOffset(currentLine)
        val desiredIndent = computeDesiredIndent(project, doc, currentLine)
        val firstNonWs = findFirstNonWs(doc, lineStart, lineEnd)
        val currentIndentLen = firstNonWs - lineStart
        if (desiredIndent.isNotEmpty() || currentIndentLen != 0) {
            // Replace existing leading whitespace to match desired indent exactly
            val replaceFrom = lineStart
            val replaceTo = lineStart + currentIndentLen
            if (doc.getText(TextRange(replaceFrom, replaceTo)) != desiredIndent) {
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                    doc.replaceString(replaceFrom, replaceTo, desiredIndent)
                }
                PsiDocumentManager.getInstance(project).commitDocument(doc)
                if (log.isDebugEnabled) {
                    val dbg = desiredIndent.replace("\t", "\\t")
                    log.debug("[LyngEnter] rewrote current line leading WS to '$dbg' at line=$currentLine")
                }
            }
        }
    }

    private fun reindentClosedBlockAroundBrace(project: Project, file: PsiFile, doc: Document, braceLine: Int) {
        // Find the absolute index of the '}' at or before end of braceLine
        val braceLineStart = doc.getLineStartOffset(braceLine)
        val braceLineEnd = doc.getLineEndOffset(braceLine)
        val rawBraceLine = doc.getText(TextRange(braceLineStart, braceLineEnd))
        val codeBraceLine = rawBraceLine.substringBefore("//")
        val closeRel = codeBraceLine.lastIndexOf('}')
        if (closeRel < 0) return
        val closeAbs = braceLineStart + closeRel

        // Compute the enclosing block range in raw text (document char sequence)
        val blockRange = net.sergeych.lyng.format.BraceUtils.findEnclosingBlockRange(doc.charsSequence, closeAbs, includeTrailingNewline = true)
            ?: return

        val options = CodeStyle.getIndentOptions(project, doc)
        val cfg = LyngFormatConfig(
            indentSize = options.INDENT_SIZE.coerceAtLeast(1),
            useTabs = options.USE_TAB_CHARACTER,
            continuationIndentSize = options.CONTINUATION_INDENT_SIZE.coerceAtLeast(options.INDENT_SIZE.coerceAtLeast(1)),
        )

        // Run partial reindent over the slice and replace only if changed
        val whole = doc.text
        val updated = LyngFormatter.reindentRange(whole, blockRange, cfg, preserveBaseIndent = true)
        if (updated != whole) {
            WriteCommandAction.runWriteCommandAction(project) {
                doc.replaceString(0, doc.textLength, updated)
            }
            PsiDocumentManager.getInstance(project).commitDocument(doc)
            if (log.isDebugEnabled) log.debug("[LyngEnter] reindented closed block range=${'$'}blockRange")
        }
    }

    private fun moveCaretToIndentIfOnLeadingWs(editor: Editor, doc: Document, file: PsiFile, line: Int, caret: Caret) {
        if (line < 0 || line >= doc.lineCount) return
        val lineStart = doc.getLineStartOffset(line)
        val lineEnd = doc.getLineEndOffset(line)
        val desiredIndent = computeDesiredIndent(file.project, doc, line)
        val firstNonWs = (lineStart + desiredIndent.length).coerceAtMost(doc.textLength)
        val caretOffset = caret.offset
        // If caret is at beginning of the line or still within the leading whitespace, move it after indent
        val target = firstNonWs.coerceIn(lineStart, lineEnd)
        caret.moveToOffset(target)
    }

    private fun computeDesiredIndent(project: Project, doc: Document, line: Int): String {
        val options = CodeStyle.getIndentOptions(project, doc)
        val start = 0
        val end = doc.getLineEndOffset(line)
        val snippet = doc.getText(TextRange(start, end))
        val isBlankLine = doc.getLineText(line).trim().isEmpty()
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

    private fun findFirstNonWs(doc: Document, start: Int, end: Int): Int {
        var i = start
        val text = doc.charsSequence
        while (i < end) {
            val ch = text[i]
            if (ch != ' ' && ch != '\t') break
            i++
        }
        return i
    }

    private fun Document.safeLineNumber(offset: Int): Int =
        getLineNumber(offset.coerceIn(0, textLength))

    private fun Document.getLineText(line: Int): String {
        if (line < 0 || line >= lineCount) return ""
        val start = getLineStartOffset(line)
        val end = getLineEndOffset(line)
        return getText(TextRange(start, end))
    }

    private fun Document.getLineStartOffsetSafe(line: Int): Int =
        if (line < 0) 0 else getLineStartOffset(line.coerceAtMost(lineCount - 1).coerceAtLeast(0))
}
