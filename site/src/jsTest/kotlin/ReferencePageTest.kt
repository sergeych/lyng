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
 * Tests for Reference page rendering helpers
 */
import kotlin.test.Test
import kotlin.test.assertTrue

class ReferencePageTest {
    @Test
    fun rendersReferenceListWithLinks() {
        val docs = listOf(
            "docs/Iterator.md",
            "docs/guides/perf_guide.md"
        )
        val html = renderReferenceListHtml(docs)

        // Basic structure
        assertTrue(html.contains("<ul", ignoreCase = true), "Expected <ul> in reference list HTML: $html")

        // Contains links to the docs routes
        assertTrue(html.contains("href=\"#/docs/Iterator.md\""), "Should link to #/docs/Iterator.md: $html")
        assertTrue(html.contains("Iterator.md"), "Should display file name Iterator.md: $html")

        // Nested path should display directory info
        assertTrue(html.contains("guides"), "Should include directory name for nested docs: $html")

        // Chevron icon present
        assertTrue(html.contains("bi-chevron-right"), "Should include chevron icon class: $html")
    }
}
