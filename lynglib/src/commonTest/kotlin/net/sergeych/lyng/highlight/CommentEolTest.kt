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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommentEolTest {

    @Test
    fun singleLineCommentExtendsToEol() {
        val line = "// see the difference: apply changes this to newly created Point:"
        val text = "$line\nnext"

        val spans = SimpleLyngHighlighter().highlight(text)
        // Find the comment span
        val cmt = spans.firstOrNull { it.kind == HighlightKind.Comment }
        assertTrue(cmt != null, "Expected a comment span")
        // It should start at 0 and extend exactly to the end of the line (before \n)
        val eol = text.indexOf('\n')
        assertEquals(0, cmt!!.range.start, "Comment should start at column 0")
        assertEquals(eol, cmt.range.endExclusive, "Comment should extend to EOL")
        // Ensure there is no other span overlapping within the same line
        spans.filter { it !== cmt }.forEach {
            assertTrue(it.range.start >= eol, "No span should start before EOL for single-line comment")
        }
    }

    @Test
    fun blockCommentNotExtendedPastClosing() {
        val text = "/* block */ rest"
        val spans = SimpleLyngHighlighter().highlight(text)
        val cmt = spans.firstOrNull { it.kind == HighlightKind.Comment }
        assertTrue(cmt != null, "Expected a block comment span")
        // The comment should end right after "/* block */"
        val expectedEnd = "/* block */".length
        assertEquals(expectedEnd, cmt!!.range.endExclusive, "Block comment should not be extended to EOL")
    }

    @Test
    fun twoSingleLineCommentsEachToTheirEol() {
        val text = "// first\n// second\nend"
        val spans = SimpleLyngHighlighter().highlight(text)
        val cmts = spans.filter { it.kind == HighlightKind.Comment }
        assertEquals(2, cmts.size, "Expected two single-line comment spans")

        val eol1 = text.indexOf('\n')
        assertEquals(0, cmts[0].range.start)
        assertEquals(eol1, cmts[0].range.endExclusive)

        val line2Start = eol1 + 1
        val eol2 = text.indexOf('\n', line2Start)
        assertEquals(line2Start, cmts[1].range.start)
        assertEquals(eol2, cmts[1].range.endExclusive)
    }
}
