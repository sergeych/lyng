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

package net.sergeych.site

import net.sergeych.lyng.highlight.HighlightKind
import net.sergeych.lyng.highlight.HighlightSpan
import net.sergeych.lyng.highlight.SimpleLyngHighlighter
import net.sergeych.lyngweb.SiteHighlight
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class HighlightSmokeTest {

    private fun spansToLabeled(text: String, spans: List<HighlightSpan>): List<Pair<String, HighlightKind>> =
        spans.map { text.substring(it.range.start, it.range.endExclusive) to it.kind }

    @Test
    fun highlightAssertEqualsSnippet() {
        val text = "assertEquals( [9,10], r.takeLast(2).toList() )"
        val spans = SimpleLyngHighlighter().highlight(text)
        val labeled = spansToLabeled(text, spans)

        // Basic sanity: identifier assertEquals present and not split
        assertTrue(labeled.any { it.first == "assertEquals" && it.second == HighlightKind.Identifier })
        // Basic numbers detection
        assertTrue(labeled.any { it.first == "9" && it.second == HighlightKind.Number })
        assertTrue(labeled.any { it.first == "10" && it.second == HighlightKind.Number })
        assertTrue(labeled.any { it.first == "2" && it.second == HighlightKind.Number })
    }

    @Test
    fun renderHtmlContainsCorrectClasses() {
        val text = "assertEquals( [9,10], r.takeLast(2).toList() )"
        val html = SiteHighlight.renderHtml(text)
        // Ensure important parts are wrapped with expected classes
        // In the new renderer, call-sites are marked as functions (hl-fn). Accept either id or fn.
        assertTrue(
            html.contains("<span class=\"hl-id\">assertEquals</span>") ||
            html.contains("<span class=\"hl-fn\">assertEquals</span>"),
            "assertEquals should be highlighted as identifier or function call"
        )
        assertContains(html, "<span class=\"hl-num\">9</span>")
        assertContains(html, "<span class=\"hl-num\">10</span>")
        assertContains(html, "<span class=\"hl-num\">2</span>")
        // Punctuation and operators appear; allow either combined or separate, just ensure class exists
        assertTrue(html.contains("hl-punc"))
        assertTrue(html.contains("hl-op"))
    }

}
