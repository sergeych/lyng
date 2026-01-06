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

class LyngHighlightTest {
    @Test
    fun highlightsReturnAndLabels() {
        val md = """
            ```lyng
            return 42
            break@outer null
            return@fn val
            ```
        """.trimIndent()
        val html = renderMarkdown(md)

        assertTrue(html.contains("hl-kw"), "Expected keyword class for 'return': $html")
        assertTrue(html.contains("hl-lbl") || html.contains("hl-ann"), "Expected label/annotation class for @outer/@fn: $html")
        assertTrue(html.contains("&gt;&gt;&gt;").xor(true), "Should not contain prompt marker unless expected")
    }

    @Test
    fun highlightsLyngFencedBlock() {
        val md = """
            ```lyng
            and or not constructor property a+b
            ```
        """.trimIndent()
        val html = renderMarkdown(md)

        // Should produce span classes for keywords and operator
        assertTrue(html.contains("hl-kw"), "Expected keyword class in highlighted Lyng block: $html")
        assertTrue(html.contains("hl-op"), "Expected operator class in highlighted Lyng block: $html")
        // Ensure code is inside <pre><code ... language-lyng>
        assertTrue(html.contains("language-lyng", ignoreCase = true), "Expected language-lyng class retained: $html")
    }

    @Test
    fun nonLyngBlocksAreUntouched() {
        val md = """
            ```kotlin
            println("Hi")
            ```
        """.trimIndent()
        val html = renderMarkdown(md)
        // Should not include our Lyng-specific classes
        assertFalse(html.contains("hl-kw"), "Non-Lyng block should not be Lyng-highlighted: $html")
        assertTrue(html.contains("<pre"), "Expected <pre> present: $html")
    }

    @Test
    fun escapesAngleBracketsInsideSpans() {
        val md = """
            ```lyng
            a<b
            ```
        """.trimIndent()
        val html = renderMarkdown(md)
        // the '<' should be escaped in HTML
        assertTrue(html.contains("&lt;"), "Expected escaped < inside highlighted HTML: $html")
    }
}
