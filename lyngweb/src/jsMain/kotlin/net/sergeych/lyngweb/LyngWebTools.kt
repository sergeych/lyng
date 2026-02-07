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

package net.sergeych.lyngweb

import net.sergeych.lyng.format.LyngFormatConfig
import net.sergeych.lyng.highlight.TextRange
import net.sergeych.lyng.miniast.CompletionItem
import net.sergeych.lyng.tools.LyngAnalysisResult
import net.sergeych.lyng.tools.LyngLanguageTools
import net.sergeych.lyng.tools.LyngSymbolInfo
import net.sergeych.lyng.tools.LyngSymbolTarget

/**
 * Thin JS-friendly facade for shared Lyng language tooling.
 * Keeps web editor/site integrations consistent with IDE tooling behavior.
 */
object LyngWebTools {
    suspend fun analyze(text: String, fileName: String = "<web>"): LyngAnalysisResult =
        LyngLanguageTools.analyze(text, fileName)

    suspend fun completions(text: String, offset: Int, analysis: LyngAnalysisResult? = null): List<CompletionItem> {
        val a = analysis ?: analyze(text)
        return LyngLanguageTools.completions(text, offset, a)
    }

    fun definitionAt(analysis: LyngAnalysisResult, offset: Int): LyngSymbolTarget? =
        LyngLanguageTools.definitionAt(analysis, offset)

    fun usagesAt(analysis: LyngAnalysisResult, offset: Int, includeDeclaration: Boolean = false): List<TextRange> =
        LyngLanguageTools.usagesAt(analysis, offset, includeDeclaration)

    fun docAt(analysis: LyngAnalysisResult, offset: Int): LyngSymbolInfo? =
        LyngLanguageTools.docAt(analysis, offset)

    fun format(text: String, config: LyngFormatConfig = LyngFormatConfig()): String =
        LyngLanguageTools.format(text, config)

    suspend fun disassembleSymbol(text: String, symbol: String): String =
        LyngLanguageTools.disassembleSymbol(text, symbol)
}
