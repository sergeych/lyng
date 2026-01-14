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
package net.sergeych.lyng.idea.grazie

import com.intellij.grazie.text.TextContent
import com.intellij.grazie.text.TextContent.TextDomain
import com.intellij.grazie.text.TextExtractor
import com.intellij.psi.PsiElement
import net.sergeych.lyng.idea.highlight.LyngTokenTypes
import net.sergeych.lyng.idea.psi.LyngElementTypes

/**
 * Simplified TextExtractor for Lyng.
 * Designates areas for Natural Languages (Grazie) to check.
 */
class LyngTextExtractor : TextExtractor() {
    override fun buildTextContent(element: PsiElement, allowedDomains: Set<TextDomain>): TextContent? {
        val type = element.node?.elementType ?: return null
        
        val domain = when (type) {
            LyngTokenTypes.LINE_COMMENT, LyngTokenTypes.BLOCK_COMMENT -> TextDomain.COMMENTS
            LyngTokenTypes.STRING -> TextDomain.LITERALS
            LyngElementTypes.NAME_IDENTIFIER,
            LyngElementTypes.PARAMETER_NAME,
            LyngElementTypes.ENUM_CONSTANT_NAME -> TextDomain.COMMENTS
            else -> return null
        }
        
        if (!allowedDomains.contains(domain)) return null
        
        return TextContent.psiFragment(domain, element)
    }
}
