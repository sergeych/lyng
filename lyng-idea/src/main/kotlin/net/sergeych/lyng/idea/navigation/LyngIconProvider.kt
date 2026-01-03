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
import com.intellij.ide.IconProvider
import com.intellij.psi.PsiElement
import net.sergeych.lyng.idea.util.LyngAstManager
import net.sergeych.lyng.miniast.DocLookupUtils
import javax.swing.Icon

class LyngIconProvider : IconProvider() {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val file = element.containingFile ?: return null
        val mini = LyngAstManager.getMiniAst(file) ?: return null
        
        val info = DocLookupUtils.findDeclarationAt(mini, element.textRange.startOffset, element.text ?: "")
        if (info != null) {
            return when (info.second) {
                "Function" -> AllIcons.Nodes.Function
                "Class" -> AllIcons.Nodes.Class
                "Enum" -> AllIcons.Nodes.Enum
                "EnumConstant" -> AllIcons.Nodes.Enum
                "Variable" -> AllIcons.Nodes.Variable
                "Value" -> AllIcons.Nodes.Field
                "Parameter" -> AllIcons.Nodes.Parameter
                "Initializer" -> AllIcons.Nodes.Method
                else -> null
            }
        }
        return null
    }
}
