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
import net.sergeych.lyng.idea.psi.LyngElementTypes

/**
 * Standard IntelliJ spellchecking strategy for Lyng.
 * Uses the simplified PSI structure to identify declarations.
 */
class LyngSpellcheckingStrategy : SpellcheckingStrategy() {
    override fun getTokenizer(element: PsiElement?): Tokenizer<*> {
        val type = element?.node?.elementType
        return when (type) {
            LyngTokenTypes.LINE_COMMENT, LyngTokenTypes.BLOCK_COMMENT -> TEXT_TOKENIZER
            LyngTokenTypes.STRING -> TEXT_TOKENIZER
            LyngElementTypes.NAME_IDENTIFIER,
            LyngElementTypes.PARAMETER_NAME,
            LyngElementTypes.ENUM_CONSTANT_NAME -> TEXT_TOKENIZER
            else -> super.getTokenizer(element)
        }
    }
}
