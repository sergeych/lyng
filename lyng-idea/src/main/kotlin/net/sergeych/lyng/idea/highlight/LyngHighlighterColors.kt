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

/*
 * Text attribute keys for Lyng token and semantic highlighting
 */
package net.sergeych.lyng.idea.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

object LyngHighlighterColors {
    val KEYWORD: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD
    )
    val STRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_STRING", DefaultLanguageHighlighterColors.STRING
    )
    val NUMBER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_NUMBER", DefaultLanguageHighlighterColors.NUMBER
    )
    val LINE_COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT
    )
    val BLOCK_COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT
    )
    val IDENTIFIER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER
    )
    val PUNCT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_PUNCT", DefaultLanguageHighlighterColors.DOT
    )

    // Semantic layer keys (placeholders for now)
    val VARIABLE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE
    )
    val VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_VALUE", DefaultLanguageHighlighterColors.INSTANCE_FIELD
    )
    val FUNCTION: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL
    )
    val TYPE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_TYPE", DefaultLanguageHighlighterColors.CLASS_REFERENCE
    )
    val NAMESPACE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_NAMESPACE", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL
    )
    val PARAMETER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "LYNG_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER
    )
}
