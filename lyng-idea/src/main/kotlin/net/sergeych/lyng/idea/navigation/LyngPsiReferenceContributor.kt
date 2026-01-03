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

import LyngAstManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.highlight.LyngTokenTypes
import net.sergeych.lyng.miniast.DocLookupUtils

class LyngPsiReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement().withLanguage(LyngLanguage),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    if (element.node.elementType == LyngTokenTypes.IDENTIFIER) {
                        val file = element.containingFile
                        val mini = LyngAstManager.getMiniAst(file)
                        if (mini != null) {
                            val offset = element.textRange.startOffset
                            val name = element.text ?: ""
                            if (DocLookupUtils.findDeclarationAt(mini, offset, name) != null) {
                                return PsiReference.EMPTY_ARRAY
                            }
                        }
                        return arrayOf(LyngPsiReference(element))
                    }
                    return PsiReference.EMPTY_ARRAY
                }
            }
        )
    }
}
