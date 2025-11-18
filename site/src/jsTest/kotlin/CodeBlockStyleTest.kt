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
 * Test that markdown fenced code blocks render with `.code` class on <pre>
 */
import kotlin.test.Test
import kotlin.test.assertTrue

class CodeBlockStyleTest {
    @Test
    fun codeBlocksGetBootstrapClass() {
        val md = """
            ```kotlin
            println("Hi")
            ```
        """.trimIndent()
        val html = renderMarkdown(md)
        assertTrue(html.contains("<pre", ignoreCase = true), "Rendered HTML should contain a <pre> tag. Got: $html")
        val hasClass = html.contains("<pre class=\"code\"", ignoreCase = true) ||
                html.contains(" class=\"code ", ignoreCase = true) ||
                html.contains(" class=\"code\"", ignoreCase = true) ||
                Regex("""<pre[^>]*class=['"][^'"]*\bcode\b[^'"]*['"]""", RegexOption.IGNORE_CASE).containsMatchIn(html)
        assertTrue(hasClass, "<pre> should have 'code' class. Got: $html")
    }
}
