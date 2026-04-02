/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchScoringTest {
    private fun rec(text: String, title: String = "Doc") = DocRecord("docs/a.md", title, norm(text))

    @Test
    fun zeroWhenNoTerms() {
        assertEquals(0, scoreQueryAdvanced(emptyList(), rec("hello world")))
    }

    @Test
    fun coverageMatters() {
        val r = rec("alpha beta gamma alpha beta")
        val s1 = scoreQueryAdvanced(listOf("alp"), r)
        val s2 = scoreQueryAdvanced(listOf("alp", "bet"), r)
        assertTrue(s2 > s1, "two-term coverage should score higher than one-term")
    }

    @Test
    fun proximityImprovesScore() {
        val near = rec("alpha beta gamma")
        val far = rec(("alpha "+"x ").repeat(50) + "beta")
        val sNear = scoreQueryAdvanced(listOf("alp", "bet"), near)
        val sFar = scoreQueryAdvanced(listOf("alp", "bet"), far)
        assertTrue(sNear > sFar, "closer terms should have higher score")
    }

    @Test
    fun preservesInlineCodeTermsInHeadingsAndText() {
        val plain = plainFromMarkdown(
            """
            ### Preferred runtime: `EvalSession`

            For host applications, prefer `EvalSession` as the main way to run scripts.
            """.trimIndent()
        )

        assertTrue(plain.contains("evalsession"), "inline code terms should remain in searchable text")
        assertTrue(scoreQueryAdvanced(listOf("evalsession"), rec(plain)) > 0)
    }

    @Test
    fun stripsTildeCodeFencesLikeBacktickFences() {
        val plain = plainFromMarkdown(
            """
            ~~~kotlin
            val session = EvalSession()
            ~~~
            """.trimIndent()
        )

        assertFalse(plain.contains("evalsession"), "fenced code should not leak into the search corpus")
    }
}
