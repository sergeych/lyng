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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnfencedLyngTest {
    @Test
    fun indentedCodeBlocksAreLyngByDefault() {
        val md = """
            Some text paragraph.

                and or not a+b

            Next paragraph.
        """.trimIndent()

        val html = renderMarkdown(md)
        // Should contain a code block and Lyng highlight classes
        assertTrue(html.contains("<pre", ignoreCase = true), "Expected <pre> in rendered HTML: $html")
        assertTrue(html.contains("<code", ignoreCase = true), "Expected <code> in rendered HTML: $html")
        assertTrue(html.contains("hl-kw"), "Expected keyword highlight in indented code block: $html")
        assertTrue(html.contains("hl-op"), "Expected operator highlight in indented code block: $html")
        // Should not introduce a non-Lyng language class; language-lyng may or may not be present, but other languages shouldn't
        assertFalse(html.contains("language-kotlin", ignoreCase = true), "Should not mark indented block as another language: $html")
    }

    @Test
    fun doctestTailLinesRenderedAsComments() {
        val md = """
            Intro paragraph.

                a + b
                >>> 3
                >>> ok
        """.trimIndent()

        val html = renderMarkdown(md)
        // Lyng highlight should appear for the code line (the '+')
        assertTrue(html.contains("hl-op"), "Expected operator highlighting for head part: $html")
        // The doctest tail lines should be wrapped as comments
        assertTrue(html.contains("hl-cmt"), "Expected comment highlighting for doctest tail: $html")
        // Ensure the markers are inside the comment span content
        assertTrue(html.contains("<span class=\"hl-cmt\">&gt;&gt;&gt;"), "Doctest lines should start with >>> inside comment span: $html")
    }
}
