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
package net.sergeych.lyng.idea.format

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings

/**
 * Minimal formatting model: enables Reformat Code to at least re-apply indentation via LineIndentProvider
 * and normalize whitespace. We don’t implement a full PSI-based tree yet; this block treats the whole file
 * as a single formatting region and lets platform query line indents.
 */
class LyngFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(element: PsiElement, settings: CodeStyleSettings): FormattingModel {
        val file = element.containingFile
        val rootBlock = LineBlocksRootBlock(file, settings)
        return FormattingModelProvider.createFormattingModelForPsiFile(file, rootBlock, settings)
    }

    override fun getRangeAffectingIndent(file: PsiFile, offset: Int, elementAtOffset: ASTNode?): TextRange? = null
}

private class LineBlocksRootBlock(
    private val file: PsiFile,
    private val settings: CodeStyleSettings
) : Block {
    override fun getTextRange(): TextRange = file.textRange

    override fun getSubBlocks(): List<Block> = emptyList()

    override fun getWrap(): Wrap? = null
    override fun getIndent(): Indent? = Indent.getNoneIndent()
    override fun getAlignment(): Alignment? = null
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)
    override fun isIncomplete(): Boolean = false
    override fun isLeaf(): Boolean = false
}

// Intentionally no sub-blocks/spacing: indentation is handled by PreFormatProcessor + LineIndentProvider
