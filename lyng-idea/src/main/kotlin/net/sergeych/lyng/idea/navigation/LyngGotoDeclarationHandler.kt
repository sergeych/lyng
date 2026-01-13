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

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import net.sergeych.lyng.idea.LyngLanguage

/**
 * Ensures Ctrl+B (Go to Definition) works on Lyng identifiers by resolving through LyngPsiReference.
 */
class LyngGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (sourceElement == null || sourceElement.language != LyngLanguage) return null
        
        val allTargets = mutableListOf<PsiElement>()

        // Find reference at the element or its parent (sometimes the identifier token is wrapped)
        val ref = sourceElement.reference ?: sourceElement.parent?.reference
        if (ref is LyngPsiReference) {
            val resolved = ref.multiResolve(false)
            allTargets.addAll(resolved.mapNotNull { it.element })
        } else {
            // Manual check if not picked up by reference (e.g. if contributor didn't run yet)
            val manualRef = LyngPsiReference(sourceElement)
            val manualResolved = manualRef.multiResolve(false)
            allTargets.addAll(manualResolved.mapNotNull { it.element })
        }
        
        if (allTargets.isEmpty()) return null

        // If there is only one target and it's equivalent to the source, return null.
        // This allows IDEA to treat it as a declaration site and trigger "Show Usages".
        if (allTargets.size == 1) {
            val target = allTargets[0]
            if (target == sourceElement || target.isEquivalentTo(sourceElement)) {
                return null
            }
        }

        return allTargets.toTypedArray()
    }
}
