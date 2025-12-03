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

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.CaretModel
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Lightweight quick-fix to replace a misspelled word (subrange) with a suggested alternative.
 * Works without the legacy Spell Checker. The replacement is applied directly to the file text.
 */
class ReplaceWordFix(
    private val range: TextRange,
    private val original: String,
    private val replacementRaw: String
) : IntentionAction {

    override fun getText(): String = "Replace '$original' with '$replacementRaw'"
    override fun getFamilyName(): String = "Lyng Spelling"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && range.startOffset in 0..range.endOffset

    override fun startInWriteAction(): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null) return
        val doc: Document = editor.document
        val safeRange = range.constrainTo(doc)
        val current = doc.getText(safeRange)
        // Preserve basic case style based on the original token
        val replacement = adaptCaseStyle(current, replacementRaw)
        WriteCommandAction.runWriteCommandAction(project, "Replace word", null, Runnable {
            doc.replaceString(safeRange.startOffset, safeRange.endOffset, replacement)
        }, file)
        // Move caret to end of replacement for convenience
        try {
            val caret: CaretModel = editor.caretModel
            caret.moveToOffset(safeRange.startOffset + replacement.length)
        } catch (_: Throwable) {}
        // Restart daemon to refresh highlights
        if (file != null) DaemonCodeAnalyzer.getInstance(project).restart(file)
    }

    private fun TextRange.constrainTo(doc: Document): TextRange {
        val start = startOffset.coerceIn(0, doc.textLength)
        val end = endOffset.coerceIn(start, doc.textLength)
        return TextRange(start, end)
    }

    private fun adaptCaseStyle(sample: String, suggestion: String): String {
        if (suggestion.isEmpty()) return suggestion
        return when {
            sample.all { it.isUpperCase() } -> suggestion.uppercase()
            // PascalCase / Capitalized single word
            sample.firstOrNull()?.isUpperCase() == true && sample.drop(1).any { it.isLowerCase() } ->
                suggestion.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            // snake_case -> lower
            sample.contains('_') -> suggestion.lowercase()
            // camelCase -> lower first
            sample.firstOrNull()?.isLowerCase() == true && sample.any { it.isUpperCase() } ->
                suggestion.replaceFirstChar { it.lowercase() }
            else -> suggestion
        }
    }
}
