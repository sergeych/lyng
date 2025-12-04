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

package net.sergeych.lyng.idea.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class LyngColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Lyng"

    override fun getIcon(): Icon? = null

    override fun getHighlighter(): SyntaxHighlighter = LyngSyntaxHighlighter()

    override fun getDemoText(): String = """
        // Lyng demo
        import lyng.stdlib as std
        
        class Sample {
          fun greet(name: String): String {
            val message = "Hello, " + name
            return message
          }
        }
        
        var counter = 0
        counter = counter + 1
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Keyword", LyngHighlighterColors.KEYWORD),
        AttributesDescriptor("String", LyngHighlighterColors.STRING),
        AttributesDescriptor("Number", LyngHighlighterColors.NUMBER),
        AttributesDescriptor("Line comment", LyngHighlighterColors.LINE_COMMENT),
        AttributesDescriptor("Block comment", LyngHighlighterColors.BLOCK_COMMENT),
        AttributesDescriptor("Identifier", LyngHighlighterColors.IDENTIFIER),
        AttributesDescriptor("Punctuation", LyngHighlighterColors.PUNCT),
        // Semantic
        AttributesDescriptor("Annotation (semantic)", LyngHighlighterColors.ANNOTATION),
        AttributesDescriptor("Variable (semantic)", LyngHighlighterColors.VARIABLE),
        AttributesDescriptor("Value (semantic)", LyngHighlighterColors.VALUE),
        AttributesDescriptor("Function (semantic)", LyngHighlighterColors.FUNCTION),
        AttributesDescriptor("Function declaration (semantic)", LyngHighlighterColors.FUNCTION_DECLARATION),
        AttributesDescriptor("Type (semantic)", LyngHighlighterColors.TYPE),
        AttributesDescriptor("Namespace (semantic)", LyngHighlighterColors.NAMESPACE),
        AttributesDescriptor("Parameter (semantic)", LyngHighlighterColors.PARAMETER),
        AttributesDescriptor("Enum constant (semantic)", LyngHighlighterColors.ENUM_CONSTANT),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
