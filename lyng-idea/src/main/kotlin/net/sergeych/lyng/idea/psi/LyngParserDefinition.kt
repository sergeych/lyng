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
package net.sergeych.lyng.idea.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.highlight.LyngLexer
import net.sergeych.lyng.idea.highlight.LyngTokenTypes

class LyngParserDefinition : ParserDefinition {
    companion object {
        val FILE: IFileElementType = IFileElementType(LyngLanguage)
        private val WHITE_SPACES: TokenSet = TokenSet.create(LyngTokenTypes.WHITESPACE, TokenType.WHITE_SPACE)
        private val COMMENTS: TokenSet = TokenSet.create(LyngTokenTypes.LINE_COMMENT, LyngTokenTypes.BLOCK_COMMENT)
        private val STRINGS: TokenSet = TokenSet.create(LyngTokenTypes.STRING)
    }

    override fun createLexer(project: Project?): Lexer = LyngLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val mark: PsiBuilder.Marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        mark.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getWhitespaceTokens(): TokenSet = WHITE_SPACES

    override fun getCommentTokens(): TokenSet = COMMENTS

    override fun getStringLiteralElements(): TokenSet = STRINGS

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = LyngFile(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode, right: ASTNode): ParserDefinition.SpaceRequirements =
        ParserDefinition.SpaceRequirements.MAY
}
