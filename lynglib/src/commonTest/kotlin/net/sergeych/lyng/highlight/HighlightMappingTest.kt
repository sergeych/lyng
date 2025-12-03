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

class HighlightMappingTest {

    private fun spansToLabeled(text: String, spans: List<HighlightSpan>): List<Pair<String, HighlightKind>> =
        spans.map { text.substring(it.range.start, it.range.endExclusive) to it.kind }

    @Test
    fun keywordsAndIdentifiers() {
        val text = "a and b or not c"
        val spans = SimpleLyngHighlighter().highlight(text)
        val labeled = spansToLabeled(text, spans)

        // Expect identifiers and keywords in order
        assertTrue(labeled.any { it.first == "a" && it.second == HighlightKind.Identifier })
        assertTrue(labeled.any { it.first == "and" && it.second == HighlightKind.Keyword })
        assertTrue(labeled.any { it.first == "b" && it.second == HighlightKind.Identifier })
        assertTrue(labeled.any { it.first == "or" && it.second == HighlightKind.Keyword })
        assertTrue(labeled.any { it.first == "not" && it.second == HighlightKind.Keyword })
        assertTrue(labeled.any { it.first == "c" && it.second == HighlightKind.Identifier })
    }

    @Test
    fun reservedWordsConstructorPropertyAsKeywords() {
        val text = "constructor property foo"
        val spans = SimpleLyngHighlighter().highlight(text)
        val labeled = spansToLabeled(text, spans)
        assertTrue(labeled.any { it.first == "constructor" && it.second == HighlightKind.Keyword })
        assertTrue(labeled.any { it.first == "property" && it.second == HighlightKind.Keyword })
        assertTrue(labeled.any { it.first == "foo" && it.second == HighlightKind.Identifier })
    }

    @Test
    fun numbersAndStringsAndChar() {
        val text = "123 0xFF 1.0 'c' \"s\""
        val spans = SimpleLyngHighlighter().highlight(text)
        val labeled = spansToLabeled(text, spans)
        assertTrue(labeled.any { it.first == "123" && it.second == HighlightKind.Number })
        assertTrue(labeled.any { it.first.lowercase() == "0xff" && it.second == HighlightKind.Number })
        assertTrue(labeled.any { it.first == "1.0" && it.second == HighlightKind.Number })
        assertTrue(labeled.any { it.first == "'c'" && it.second == HighlightKind.Char })
        assertTrue(labeled.any { it.first == "\"s\"" && it.second == HighlightKind.String })
    }

    @Test
    fun commentsHighlighted() {
        val text = "// line\n/* block */"
        val spans = SimpleLyngHighlighter().highlight(text)
        val labeled = spansToLabeled(text, spans)
        assertTrue(labeled.any { it.first.startsWith("//") && it.second == HighlightKind.Comment })
        assertTrue(labeled.any { it.first.startsWith("/*") && it.second == HighlightKind.Comment })
    }

    @Test
    fun operatorsAndPunctuation() {
        val text = "a+b; (x)"
        val spans = SimpleLyngHighlighter().highlight(text)
        val labeled = spansToLabeled(text, spans)
        assertTrue(labeled.any { it.first == "+" && it.second == HighlightKind.Operator })
        assertTrue(labeled.any { it.first == ";" && it.second == HighlightKind.Punctuation })
        assertTrue(labeled.any { it.first == "(" && it.second == HighlightKind.Punctuation })
        assertTrue(labeled.any { it.first == ")" && it.second == HighlightKind.Punctuation })
    }

    @Test
    fun annotationsIncludeAtAndFullName() {
        // Simple standalone annotation must include full token including '@'
        run {
            val text = "@Ann"
            val spans = SimpleLyngHighlighter().highlight(text)
            val annSpans = spans.filter { it.kind == HighlightKind.Label }
            assertTrue(annSpans.size == 1)
            val frag = text.substring(annSpans[0].range.start, annSpans[0].range.endExclusive)
            assertTrue(frag == "@Ann")
        }
        // Qualified name: we at least must not drop the last character of the @segment
        run {
            val text = "@Qualified.Name"
            val spans = SimpleLyngHighlighter().highlight(text)
            val annSpans = spans.filter { it.kind == HighlightKind.Label }
            assertTrue(annSpans.size == 1)
            val s = annSpans[0]
            val frag = text.substring(s.range.start, s.range.endExclusive)
            assertTrue(frag.startsWith("@Qualified"))
            // Ensure we did not miss the last char of this segment
            assertTrue(frag.last() == 'd')
            // Next token after the span should be '.'
            assertTrue(text.getOrNull(s.range.endExclusive) == '.')
        }
        // Multiple annotations: every Label span must include '@' and end on an identifier boundary
        run {
            val text = "@Ann @Another(1) fun x() {}"
            val spans = SimpleLyngHighlighter().highlight(text)
            val annSpans = spans.filter { it.kind == HighlightKind.Label }
            assertTrue(annSpans.size >= 2)
            for (s in annSpans) {
                val frag = text.substring(s.range.start, s.range.endExclusive)
                assertTrue(frag.startsWith("@"))
                // last char must be letter/digit/underscore/tilde/dollar per idNextChars
                assertTrue(frag.last().isLetterOrDigit() || frag.last() == '_' || frag.last() == '$' || frag.last() == '~')
            }
        }
    }
}
