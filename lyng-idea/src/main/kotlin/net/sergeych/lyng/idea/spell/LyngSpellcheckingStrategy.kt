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
package net.sergeych.lyng.idea.spell

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer
import net.sergeych.lyng.idea.highlight.LyngTokenTypes

/**
 * Standard IntelliJ spellchecking strategy for Lyng.
 * It uses the MiniAst-driven [LyngSpellIndex] to limit identifier checks to declarations only.
 */
class LyngSpellcheckingStrategy : SpellcheckingStrategy() {
    override fun getTokenizer(element: PsiElement?): Tokenizer<*> {
        val type = element?.node?.elementType
        return when (type) {
            LyngTokenTypes.LINE_COMMENT, LyngTokenTypes.BLOCK_COMMENT -> TEXT_TOKENIZER
            LyngTokenTypes.STRING -> TEXT_TOKENIZER
            LyngTokenTypes.IDENTIFIER -> {
                // We use standard NameIdentifierOwner/PsiNamedElement-based logic
                // if it's a declaration. Argument names, class names, etc. are PSI-based.
                // However, our PSI is currently very minimal (ASTWrapperPsiElement).
                // So we stick to the index but ensure it is robustly filled.
                val file = element.containingFile
                val index = LyngSpellIndex.getUpToDate(file)
                if (index != null) {
                    val range = element.textRange
                    if (index.identifiers.any { it.contains(range) }) {
                        return TEXT_TOKENIZER
                    }
                }
                EMPTY_TOKENIZER
            }
            else -> super.getTokenizer(element)
        }
    }
}
