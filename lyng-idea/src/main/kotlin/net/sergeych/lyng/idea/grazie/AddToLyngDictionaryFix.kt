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
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import net.sergeych.lyng.idea.settings.LyngFormatterSettings

/**
 * Lightweight quick-fix that adds a word to the per-project Lyng dictionary.
 */
class AddToLyngDictionaryFix(private val word: String) : IntentionAction {
    override fun getText(): String = "Add '$word' to Lyng dictionary"
    override fun getFamilyName(): String = "Lyng Spelling"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = word.isNotBlank()
    override fun startInWriteAction(): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val settings = LyngFormatterSettings.getInstance(project)
        val learned = settings.learnedWords
        learned.add(word.lowercase())
        settings.learnedWords = learned
        // Restart daemon to refresh highlights
        if (file != null) DaemonCodeAnalyzer.getInstance(project).restart(file)
    }
}
