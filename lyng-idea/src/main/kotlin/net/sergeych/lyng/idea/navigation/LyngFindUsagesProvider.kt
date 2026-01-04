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

package net.sergeych.lyng.idea.navigation

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import net.sergeych.lyng.idea.highlight.LyngLexer
import net.sergeych.lyng.idea.highlight.LyngTokenTypes
import net.sergeych.lyng.idea.util.LyngAstManager
import net.sergeych.lyng.miniast.DocLookupUtils

class LyngFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner {
        return DefaultWordsScanner(
            LyngLexer(),
            TokenSet.create(LyngTokenTypes.IDENTIFIER),
            TokenSet.create(LyngTokenTypes.LINE_COMMENT, LyngTokenTypes.BLOCK_COMMENT),
            TokenSet.create(LyngTokenTypes.STRING)
        )
    }

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean {
        return psiElement is LyngDeclarationElement || isDeclaration(psiElement)
    }

    private fun isDeclaration(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val mini = LyngAstManager.getMiniAst(file) ?: return false
        val offset = element.textRange.startOffset
        val name = element.text ?: ""
        return DocLookupUtils.findDeclarationAt(mini, offset, name) != null
    }

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String {
        if (element is LyngDeclarationElement) return element.kind
        val file = element.containingFile ?: return "Lyng declaration"
        val mini = LyngAstManager.getMiniAst(file) ?: return "Lyng declaration"
        val info = DocLookupUtils.findDeclarationAt(mini, element.textRange.startOffset, element.text ?: "")
        return info?.second ?: "Lyng declaration"
    }

    override fun getDescriptiveName(element: PsiElement): String {
        if (element is LyngDeclarationElement) {
            val file = element.containingFile
            val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
            val line = if (document != null) document.getLineNumber(element.textRange.startOffset) + 1 else "?"
            val column = if (document != null) {
                val lineStart = document.getLineStartOffset(document.getLineNumber(element.textRange.startOffset))
                element.textRange.startOffset - lineStart + 1
            } else "?"
            return "${element.name} (${file.name}:$line:$column)"
        }
        val file = element.containingFile ?: return element.text ?: "unknown"
        val mini = LyngAstManager.getMiniAst(file) ?: return element.text ?: "unknown"
        val info = DocLookupUtils.findDeclarationAt(mini, element.textRange.startOffset, element.text ?: "")

        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
        val line = if (document != null) document.getLineNumber(element.textRange.startOffset) + 1 else "?"
        val column = if (document != null) {
            val lineStart = document.getLineStartOffset(document.getLineNumber(element.textRange.startOffset))
            element.textRange.startOffset - lineStart + 1
        } else "?"
        
        val name = info?.first ?: element.text ?: "unknown"
        return "$name (${file.name}:$line:$column)"
    }

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String {
        return (element as? LyngDeclarationElement)?.name ?: element.text ?: "unknown"
    }
}
