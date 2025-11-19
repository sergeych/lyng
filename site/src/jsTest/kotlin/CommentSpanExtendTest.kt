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

class CommentSpanExtendTest {
    @Test
    fun singleLineCommentCoversTillEol() {
        val line = "// see the difference: apply changes this to newly created Point:"
        val md = """
            ```lyng
            $line
            ```
        """.trimIndent()

        val html = renderMarkdown(md)
        // Entire line must be inside the comment span
        assertTrue(html.contains("<span class=\"hl-cmt\">$line</span>"), "Comment should extend to EOL: $html")
        // There must be no stray tail like </span>nt: (regression case)
        assertFalse(html.contains("</span>nt:"), "No trailing tail should remain outside comment span: $html")
    }
}
