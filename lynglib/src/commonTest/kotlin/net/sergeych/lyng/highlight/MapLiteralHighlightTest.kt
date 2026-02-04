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

package net.sergeych.lyng.highlight

import kotlin.test.Test
import kotlin.test.assertTrue

class MapLiteralHighlightTest {

    private fun spansToLabeled(text: String, spans: List<HighlightSpan>): List<Pair<String, HighlightKind>> =
        spans.map { text.substring(it.range.start, it.range.endExclusive) to it.kind }

    @Test
    fun highlightsMapLiteralBasics() {
        val text = """
            val x = 2
            val m = { "a": 1, b: , ...base, }
        """.trimIndent()

        val spans = SimpleLyngHighlighter().highlight(text)
        val labeled = spansToLabeled(text, spans)

        // Brace punctuation
        assertTrue(labeled.any { it.first == "{" && it.second == HighlightKind.Punctuation })
        assertTrue(labeled.any { it.first == "}" && it.second == HighlightKind.Punctuation })

        // String key and number value
        assertTrue(labeled.any { it.first == "\"a\"" && it.second == HighlightKind.String })
        assertTrue(labeled.any { it.first == "1" && it.second == HighlightKind.Number })

        // Identifier key and shorthand (b:)
        assertTrue(labeled.any { it.first == "b" && it.second == HighlightKind.Identifier })
        // The colon after key is punctuation
        assertTrue(labeled.any { it.first == ":" && it.second == HighlightKind.Punctuation })

        // Spread operator
        assertTrue(labeled.any { it.first == "..." && it.second == HighlightKind.Operator })

        // Trailing comma
        assertTrue(labeled.any { it.first == "," && it.second == HighlightKind.Punctuation })
    }
}
