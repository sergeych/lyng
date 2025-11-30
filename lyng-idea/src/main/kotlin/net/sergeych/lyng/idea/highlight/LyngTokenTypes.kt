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

import com.intellij.psi.tree.IElementType
import net.sergeych.lyng.idea.LyngLanguage

class LyngTokenType(debugName: String) : IElementType(debugName, LyngLanguage)

object LyngTokenTypes {
    val WHITESPACE = LyngTokenType("WHITESPACE")
    val LINE_COMMENT = LyngTokenType("LINE_COMMENT")
    val BLOCK_COMMENT = LyngTokenType("BLOCK_COMMENT")
    val STRING = LyngTokenType("STRING")
    val NUMBER = LyngTokenType("NUMBER")
    val KEYWORD = LyngTokenType("KEYWORD")
    val IDENTIFIER = LyngTokenType("IDENTIFIER")
    val PUNCT = LyngTokenType("PUNCT")
    val BAD_CHAR = LyngTokenType("BAD_CHAR")
}
