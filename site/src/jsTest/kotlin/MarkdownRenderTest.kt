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
 * Basic test to ensure markdown is actually rendered by the ESM `marked` import
 */
import kotlin.test.Test
import kotlin.test.assertTrue

class MarkdownRenderTest {
    @Test
    fun rendersHeading() {
        val html = renderMarkdown("# Hello")
        assertTrue(html.contains("<h1", ignoreCase = true), "Expected <h1> in rendered HTML, got: $html")
        assertTrue(html.contains("Hello"), "Rendered HTML should contain the heading text")
    }
}
