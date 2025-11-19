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
 * Regression tests for highlighting real-world snippet mentioned in the issue.
 */
package net.sergeych.lyng.highlight

import kotlin.test.Test
import kotlin.test.assertTrue

class RegressionAssertEqualsTest {

    private fun spansToLabeled(text: String, spans: List<HighlightSpan>): List<Pair<String, HighlightKind>> =
        spans.map { text.substring(it.range.start, it.range.endExclusive) to it.kind }

    @Test
    fun assertEqualsSnippetIsTokenizedAndHighlightedSane() {
        val text = "assertEquals( [9,10], r.takeLast(2).toList() )"
        val spans = SimpleLyngHighlighter().highlight(text)
        val labeled = spansToLabeled(text, spans)
        // Debug print to help diagnose failures across targets
        println("[DEBUG_LOG] labeled spans: " + labeled.joinToString(" | ") { "${it.first}:{${it.second}}" })

        // Ensure identifier is not split: whole 'assertEquals' must be Identifier
        assertTrue(labeled.any { it.first == "assertEquals" && it.second == HighlightKind.Identifier })

        // Brackets and parentheses must be punctuation; spans may merge adjacent punctuation,
        // so accept combined tokens like "()" or "],"
        fun hasPunct(containing: Char) = labeled.any { containing in it.first && it.second == HighlightKind.Punctuation }
        assertTrue(hasPunct('('))
        assertTrue(hasPunct(')'))
        assertTrue(hasPunct('['))
        assertTrue(hasPunct(']'))
        assertTrue(hasPunct(','))

        // Numbers 9, 10 and 2 should be recognized as numbers
        assertTrue(labeled.any { it.first == "9" && it.second == HighlightKind.Number })
        assertTrue(labeled.any { it.first == "10" && it.second == HighlightKind.Number })
        assertTrue(labeled.any { it.first == "2" && it.second == HighlightKind.Number })

        // Method chain identifiers and dots/operators
        assertTrue(labeled.any { it.first == "." && it.second == HighlightKind.Operator })
        assertTrue(labeled.any { it.first == "r" && it.second == HighlightKind.Identifier })
        assertTrue(labeled.any { it.first == "takeLast" && it.second == HighlightKind.Identifier })
        assertTrue(labeled.any { it.first == "toList" && it.second == HighlightKind.Identifier })
    }
}
