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
 * Tests for definition list post-processing
 */
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefinitionListTest {
    @Test
    fun singleTermMultipleDefinitions() {
        val md = """
            Term
            : First definition
            : Second definition with an [inline link](#here)
        """.trimIndent()

        val html = renderMarkdown(md)
        assertTrue(html.contains("<dl>", ignoreCase = true), "Expected <dl> in rendered HTML. Got: $html")
        assertTrue(html.contains("<dt>Term</dt>", ignoreCase = true), "Term should be inside <dt>. Got: $html")
        // There should be two <dd> entries
        val ddCount = Regex("<dd>", RegexOption.IGNORE_CASE).findAll(html).count()
        assertTrue(ddCount == 2, "Expected two <dd> elements, got $ddCount. HTML: $html")
        // No leading ':' should remain inside definitions
        assertFalse(Regex("<dd>\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(html), "Definition should not start with ':'. HTML: $html")
    }

    @Test
    fun notADefListWhenStartsWithColon() {
        val md = """
            : Not a term paragraph
            Next paragraph
        """.trimIndent()

        val html = renderMarkdown(md)
        // Should not produce a <dl>
        assertFalse(html.contains("<dl>", ignoreCase = true), "Should not create <dl> when first paragraph starts with ':'. HTML: $html")
    }
}
