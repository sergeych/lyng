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
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.psi.PsiDocumentManager
import net.sergeych.lyng.format.LyngFormatConfig
import net.sergeych.lyng.format.LyngFormatter
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.settings.LyngFormatterSettings
import java.awt.datatransfer.DataFlavor

/**
 * Smart Paste using an editor action handler (avoids RawText API variance).
 * Reindents pasted blocks when caret is in leading whitespace and the setting is enabled.
 */
class LyngPasteHandler : EditorWriteActionHandler(true) {
    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(LyngPasteHandler::class.java)
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
        val project = editor.project
        if (project == null) return

        val psiDocMgr = PsiDocumentManager.getInstance(project)
        val file = psiDocMgr.getPsiFile(editor.document)
        if (file == null || file.language != LyngLanguage) {
            pasteAsIs(editor)
            return
        }

        val settings = LyngFormatterSettings.getInstance(project)
        if (!settings.reindentPastedBlocks) {
            pasteAsIs(editor)
            return
        }

        val text = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        if (text == null) {
            pasteAsIs(editor)
            return
        }

        val caretModel = editor.caretModel
        val effectiveCaret = caret ?: caretModel.currentCaret
        val doc = editor.document
        PsiDocumentManager.getInstance(project).commitDocument(doc)

        // Paste the text as-is first, then compute the inserted range and reindent that slice
        val options = CodeStyle.getIndentOptions(project, doc)
        val cfg = LyngFormatConfig(
            indentSize = options.INDENT_SIZE.coerceAtLeast(1),
            useTabs = options.USE_TAB_CHARACTER,
            continuationIndentSize = options.CONTINUATION_INDENT_SIZE.coerceAtLeast(options.INDENT_SIZE.coerceAtLeast(1)),
        )

        // Replace selection (if any) or insert at caret with original clipboard text
        val selModel = editor.selectionModel
        val replaceStart = if (selModel.hasSelection()) selModel.selectionStart else effectiveCaret.offset
        val replaceEnd = if (selModel.hasSelection()) selModel.selectionEnd else effectiveCaret.offset

        WriteCommandAction.runWriteCommandAction(project) {
            log.info("[LyngPaste] handler invoked for Lyng file; setting ON=${settings.reindentPastedBlocks}")
            // Step 1: paste as-is
            val beforeLen = doc.textLength
            doc.replaceString(replaceStart, replaceEnd, text)
            psiDocMgr.commitDocument(doc)

            // Step 2: compute the freshly inserted range robustly (account for line-separator normalization)
            val insertedStart = replaceStart
            val delta = doc.textLength - beforeLen + (replaceEnd - replaceStart)
            val insertedEndExclusive = (insertedStart + delta).coerceIn(insertedStart, doc.textLength)

            // Expand to full lines to let the formatter compute proper base/closing alignment
            val lineStart = run {
                var i = (insertedStart - 1).coerceAtLeast(0)
                while (i >= 0 && doc.charsSequence[i] != '\n') i--
                i + 1
            }
            var lineEndInclusive = run {
                var i = insertedEndExclusive
                val seq = doc.charsSequence
                while (i < seq.length && seq[i] != '\n') i++
                // include trailing newline if present
                if (i < seq.length && seq[i] == '\n') i + 1 else i
            }

            // If the next non-whitespace char right after the insertion is a closing brace '}',
            // include that brace line into the formatting slice for better block alignment.
            run {
                val seq = doc.charsSequence
                var j = insertedEndExclusive
                while (j < seq.length && (seq[j] == ' ' || seq[j] == '\t' || seq[j] == '\n' || seq[j] == '\r')) j++
                if (j < seq.length && seq[j] == '}') {
                    var k = j
                    while (k < seq.length && seq[k] != '\n') k++
                    lineEndInclusive = if (k < seq.length && seq[k] == '\n') k + 1 else k
                }
            }

            val fullTextBefore = doc.text
            val expandedRange = (lineStart until lineEndInclusive)
            log.info("[LyngPaste] inserted=[$insertedStart,$insertedEndExclusive) expanded=[$lineStart,$lineEndInclusive)")
            val updatedFull = LyngFormatter.reindentRange(
                fullTextBefore,
                expandedRange,
                cfg,
                preserveBaseIndent = true,
                baseIndentFrom = insertedStart
            )

            if (updatedFull != fullTextBefore) {
                val delta = updatedFull.length - fullTextBefore.length
                doc.replaceString(0, doc.textLength, updatedFull)
                psiDocMgr.commitDocument(doc)
                caretModel.moveToOffset((insertedEndExclusive + delta).coerceIn(0, doc.textLength))
                log.info("[LyngPaste] applied reindent to expanded range")
            } else {
                // No changes after reindent — just move caret to end of the inserted text
                caretModel.moveToOffset(insertedEndExclusive)
                log.info("[LyngPaste] no changes after reindent")
            }
            selModel.removeSelection()
        }
    }

    private fun pasteAsIs(editor: Editor) {
        val text = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor) ?: return
        pasteText(editor, text)
    }

    private fun pasteText(editor: Editor, text: String) {
        val project = editor.project ?: return
        val doc = editor.document
        val caretModel = editor.caretModel
        val selModel = editor.selectionModel
        WriteCommandAction.runWriteCommandAction(project) {
            val replaceStart = if (selModel.hasSelection()) selModel.selectionStart else caretModel.offset
            val replaceEnd = if (selModel.hasSelection()) selModel.selectionEnd else caretModel.offset
            doc.replaceString(replaceStart, replaceEnd, text)
            PsiDocumentManager.getInstance(project).commitDocument(doc)
            caretModel.moveToOffset(replaceStart + text.length)
            selModel.removeSelection()
        }
    }

    // no longer used
}
