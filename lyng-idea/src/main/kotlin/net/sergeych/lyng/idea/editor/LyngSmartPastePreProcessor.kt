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

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.settings.LyngFormatterSettings

/**
 * Smart Paste helper for Lyng. Not registered as EP yet to keep build stable across IDE SDKs.
 * Use `processOnPasteIfEnabled` from a CopyPastePreProcessor adapter once API signature is finalized.
 */
object LyngSmartPastePreProcessorHelper {
    fun processOnPasteIfEnabled(project: Project, file: PsiFile, editor: Editor, text: String): String {
        if (file.language != LyngLanguage) return text
        val settings = LyngFormatterSettings.getInstance(project)
        if (!settings.reindentPastedBlocks) return text
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        return LyngPastePreProcessor.reindentForPaste(project, editor, file, text)
    }
}
