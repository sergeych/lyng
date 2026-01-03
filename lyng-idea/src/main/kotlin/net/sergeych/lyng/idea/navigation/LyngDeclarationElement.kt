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

import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.impl.light.LightElement
import com.intellij.util.IncorrectOperationException
import net.sergeych.lyng.idea.LyngLanguage
import javax.swing.Icon

/**
 * A light PSI element representing a Lyng declaration (function, class, enum, or variable).
 * Used for navigation and to provide a stable anchor for "Find Usages".
 */
class LyngDeclarationElement(
    private val nameElement: PsiElement,
    private val name: String,
    val kind: String = "declaration"
) : LightElement(nameElement.manager, LyngLanguage), PsiNameIdentifierOwner {

    override fun getName(): String = name

    override fun setName(name: String): PsiElement {
        throw IncorrectOperationException("Renaming is not yet supported")
    }

    override fun getNameIdentifier(): PsiElement = nameElement

    override fun getNavigationElement(): PsiElement = nameElement

    override fun getTextRange(): TextRange = nameElement.textRange

    override fun getContainingFile(): PsiFile = nameElement.containingFile

    override fun isValid(): Boolean = nameElement.isValid

    override fun getPresentation(): ItemPresentation {
        return object : ItemPresentation {
            override fun getPresentableText(): String = name
            override fun getLocationString(): String {
                val file = containingFile
                val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
                val line = if (document != null) document.getLineNumber(textRange.startOffset) + 1 else "?"
                val column = if (document != null) {
                    val lineStart = document.getLineStartOffset(document.getLineNumber(textRange.startOffset))
                    textRange.startOffset - lineStart + 1
                } else "?"
                return "${file.name}:$line:$column"
            }
            override fun getIcon(unused: Boolean): Icon {
                return when (kind) {
                    "Function" -> AllIcons.Nodes.Function
                    "Class" -> AllIcons.Nodes.Class
                    "Enum" -> AllIcons.Nodes.Enum
                    "EnumConstant" -> AllIcons.Nodes.Enum
                    "Variable" -> AllIcons.Nodes.Variable
                    "Value" -> AllIcons.Nodes.Field
                    "Parameter" -> AllIcons.Nodes.Parameter
                    "Initializer" -> AllIcons.Nodes.Method
                    else -> AllIcons.Nodes.Property
                }
            }
        }
    }

    override fun toString(): String = "$kind:$name"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LyngDeclarationElement) return false
        return name == other.name && nameElement == other.nameElement
    }

    override fun isEquivalentTo(another: PsiElement?): Boolean {
        if (this === another) return true
        if (another == nameElement) return true
        if (another is LyngDeclarationElement) {
            return name == another.name && nameElement == another.nameElement
        }
        return super.isEquivalentTo(another)
    }

    override fun hashCode(): Int {
        var result = nameElement.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }
}
