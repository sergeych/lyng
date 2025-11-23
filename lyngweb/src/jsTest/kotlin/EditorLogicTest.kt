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

package net.sergeych.lyngweb

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorLogicTest {

    private val tab = 4

    @Test
    fun enter_after_only_rbrace_undents() {
        val line = "    }   " // 4 spaces, brace, trailing spaces; caret after last non-ws
        val res = applyEnter(line, line.length, line.length, tab)
        // Should insert newline with one indent level less (0 spaces here)
        assertEquals("    }   \n" + "" , res.text.substring(0, res.text.indexOf('\n')+1))
        // After insertion caret should be at end of inserted indentation
        // Here indent was 4 and undented by 4 -> 0 spaces after newline
        val expectedCaret = line.length + 1 + 0
        assertEquals(expectedCaret, res.selStart)
        assertEquals(expectedCaret, res.selEnd)
    }

    @Test
    fun enter_after_rbrace_with_only_spaces_to_eol_inserts_after_brace_and_dedents_and_undents_brace_line() {
        // Rule 5 exact check: last non-ws before caret is '}', remainder to EOL only spaces
        val indents = listOf(0, 4, 8)
        for (indent in indents) {
            val spaces = " ".repeat(indent)
            // Line ends with '}' followed by three spaces
            val before = (
                """
                1
                ${'$'}spaces}
                """
            ).trimIndent() + "   "
            // Caret right after '}', before trailing spaces
            val caret = before.indexOf('}') + 1
            val res = applyEnter(before, caret, caret, tab)

            val newBraceIndent = (indent - tab).coerceAtLeast(0)
            val newLineIndent = newBraceIndent
            val expected = buildString {
                append("1\n")
                append(" ".repeat(newBraceIndent))
                append("}\n")
                append(" ".repeat(newLineIndent))
            }
            assertEquals(expected, res.text)
            // Caret must be at start of the newly inserted line (after the final newline)
            assertEquals(expected.length, res.selStart)
            assertEquals(res.selStart, res.selEnd)
        }
    }

    @Test
    fun enter_after_rbrace_with_only_spaces_to_eol_crlf_and_undents_brace_line() {
        val indent = 4
        val spaces = " ".repeat(indent)
        val beforeLf = (
            """
            1
            ${'$'}spaces}
            """
        ).trimIndent() + "   "
        val before = beforeLf.replace("\n", "\r\n")
        val caret = before.indexOf('}') + 1
        val res = applyEnter(before, caret, caret, tab)
        val actual = res.text.replace("\r\n", "\n")
        val newIndent = (indent - tab).coerceAtLeast(0)
        val expected = "1\n${" ".repeat(newIndent)}}\n${" ".repeat(newIndent)}"
        assertEquals(expected, actual)
        assertEquals(expected.length, res.selStart)
        assertEquals(res.selStart, res.selEnd)
    }

    @Test
    fun enter_before_closing_brace_outdents() {
        val text = "    }" // caret before '}' at index 4
        val caret = 4
        val res = applyEnter(text, caret, caret, tab)
        // Inserted a newline with indent reduced to 0
        assertEquals("\n" + "" + "}", res.text.substring(caret, caret + 2))
        val expectedCaret = caret + 1 + 0
        assertEquals(expectedCaret, res.selStart)
        assertEquals(expectedCaret, res.selEnd)
    }

    @Test
    fun enter_between_braces_inserts_two_lines() {
        val text = "{}"
        val caret = 1 // between
        val res = applyEnter(text, caret, caret, tab)
        // Expect: "{\n    \n}"
        assertEquals("{\n    \n}", res.text)
        // Caret after first newline + 4 spaces
        assertEquals(1 + 1 + 4, res.selStart)
        assertEquals(res.selStart, res.selEnd)
    }

    @Test
    fun enter_after_open_brace_increases_indent() {
        val text = "{"
        val caret = 1
        val res = applyEnter(text, caret, caret, tab)
        assertEquals("{\n    ", res.text)
        assertEquals(1 + 1 + 4, res.selStart)
    }

    @Test
    fun enter_after_rbrace_line_undents_ignoring_trailing_ws() {
        // Line contains only '}' plus trailing spaces; caret after last non-ws
        val line = "    }   "
        val res = applyEnter(line, line.length, line.length, tab)
        // Expect insertion of a newline and an undented indentation of (indent - tab)
        val expectedIndentAfterNewline = 0 // 4 - 4
        val expected = line + "\n" + " ".repeat(expectedIndentAfterNewline)
        // Compare prefix up to inserted indentation
        val prefix = res.text.substring(0, expected.length)
        assertEquals(expected, prefix)
        // Caret positioned at end of inserted indentation
        val expectedCaret = expected.length
        assertEquals(expectedCaret, res.selStart)
        assertEquals(expectedCaret, res.selEnd)
    }

    @Test
    fun shift_tab_outdents_rbrace_only_line_no_newline() {
        // Multi-line: a block followed by a number; we outdent the '}' line only
        val text = "    }\n3"
        // Place selection inside the first line
        val caret = 5 // after the '}'
        val res = applyShiftTab(text, caret, caret, tab)
        // Should become '}' on the first line, no extra blank lines
        assertEquals("}\n3", res.text)
        // Caret should move left by tabSize on that line (but not negative)
        val expectedCaret = (caret - tab).coerceAtLeast(0)
        assertEquals(expectedCaret, res.selStart)
        assertEquals(expectedCaret, res.selEnd)
    }

    @Test
    fun shift_tab_outdents_without_newline() {
        val text = "    a\n    b"
        val res = applyShiftTab(text, 0, text.length, tab)
        assertEquals("a\nb", res.text)
    }

    @Test
    fun tab_inserts_spaces() {
        val text = "x"
        val caret = 1
        val res = applyTab(text, caret, caret, tab)
        assertEquals("x    ", res.text)
        assertEquals(1 + 4, res.selStart)
    }

    @Test
    fun enter_before_line_with_only_rbrace_dedents_that_line() {
        // Initial content as reported by user before fix
        val before = (
            """
            {
                1
                2
                3
                }
            1
            2
            3
            """
        ).trimIndent()

        // Place caret at the end of the line that contains just "    3" (before the line with '}')
        val lines = before.split('\n')
        // Build index of end of the line with the last "    3"
        var idx = 0
        var caret = 0
        for (i in lines.indices) {
            val line = lines[i]
            if (line.trimEnd() == "3" && i + 1 < lines.size && lines[i + 1].trim() == "}") {
                caret = idx + line.length // position after '3', before the newline
                break
            }
            idx += line.length + 1 // +1 for newline
        }

        val res = applyEnter(before, caret, caret, tab)

        val expected = (
            """
            {
                1
                2
                3
            }
            1
            2
            3
            """
        ).trimIndent()

        assertEquals(expected, res.text)
    }

    @Test
    fun enter_eol_before_brace_only_next_line_various_indents() {
        // Cover Rule 3 with LF newlines, at indents 0, 2, 4, 8
        val indents = listOf(0, 2, 4, 8)
        for (indent in indents) {
            val spaces = " ".repeat(indent)
            val before = (
                """
                1
                2
                3
                ${'$'}spaces}
                4
                """
            ).trimIndent()
            // Caret at end of the line with '3' (line before the rbrace-only line)
            val caret = before.indexOf("3\n") + 1 // just before LF
            val res = applyEnter(before, caret, caret, tab)

            // The '}' line must be dedented by one block (clamped at 0) and caret moved to its start
            val expectedIndent = (indent - tab).coerceAtLeast(0)
            val expected = (
                """
                1
                2
                3
                ${'$'}{" ".repeat(expectedIndent)}}
                4
                """
            ).trimIndent()
            assertEquals(expected, res.text, "EOL before '}' dedent failed for indent=${'$'}indent")
            // Caret should be at start of that '}' line (line index 3)
            val lines = res.text.split('\n')
            var pos = 0
            for (i in 0 until 3) pos += lines[i].length + 1
            assertEquals(pos, res.selStart, "Caret pos mismatch for indent=${'$'}indent")
            assertEquals(pos, res.selEnd, "Caret pos mismatch for indent=${'$'}indent")
        }
    }

    @Test
    fun enter_eol_before_brace_only_next_line_various_indents_crlf() {
        // Same as above but with CRLF newlines
        val indents = listOf(0, 2, 4, 8)
        for (indent in indents) {
            val spaces = " ".repeat(indent)
            val beforeLf = (
                """
                1
                2
                3
                ${'$'}spaces}
                4
                """
            ).trimIndent()
            val before = beforeLf.replace("\n", "\r\n")
            val caret = before.indexOf("3\r\n") + 1 // at '3' index + 1 moves to end-of-line before CR
            val res = applyEnter(before, caret, caret, tab)

            val expectedIndent = (indent - tab).coerceAtLeast(0)
            val expectedLf = (
                """
                1
                2
                3
                ${'$'}{" ".repeat(expectedIndent)}}
                4
                """
            ).trimIndent()
            assertEquals(expectedLf, res.text.replace("\r\n", "\n"), "CRLF case failed for indent=${'$'}indent")
        }
    }

    @Test
    fun enter_at_start_of_brace_only_line_at_cols_0_2_4() {
        val indents = listOf(0, 2, 4)
        for (indent in indents) {
            val spaces = " ".repeat(indent)
            val before = (
                """
                1
                2
                ${'$'}spaces}
                3
                """
            ).trimIndent()
            // Caret at start of the brace line
            val lines = before.split('\n')
            var caret = 0
            for (i in 0 until 2) caret += lines[i].length + 1
            caret += 0 // column 0 of brace line
            val res = applyEnter(before, caret, caret, tab)

            // Expect the brace line to be dedented by one block, and a new line inserted before it
            val expectedIndent = (indent - tab).coerceAtLeast(0)
            val expected = (
                """
                1
                2
                ${'$'}{" ".repeat(expectedIndent)}
                ${'$'}{" ".repeat(expectedIndent)}}
                3
                """
            ).trimIndent()
            assertEquals(expected, res.text, "Brace-line start enter failed for indent=${'$'}indent")
            // Caret must be at start of the inserted line, which has expectedIndent spaces
            val afterLines = res.text.split('\n')
            var pos = 0
            for (i in 0 until 3) pos += afterLines[i].length + 1
            // The inserted line is line index 2 (0-based), caret at its start
            pos -= afterLines[2].length + 1
            pos += 0
            assertEquals(pos, res.selStart, "Caret mismatch for indent=${'$'}indent")
            assertEquals(pos, res.selEnd, "Caret mismatch for indent=${'$'}indent")
        }
    }

    @Test
    fun enter_on_whitespace_only_line_keeps_same_indent() {
        val before = "    \nnext" // line 0 has 4 spaces only
        val caret = 0 + 4 // at end of spaces, before LF
        val res = applyEnter(before, caret, caret, tab)
        // Default smart indent should keep indent = 4
        assertEquals("    \n    \nnext", res.text)
        // Caret at start of the new blank line with 4 spaces
        assertEquals(1 + 4, res.selStart)
        assertEquals(res.selStart, res.selEnd)
    }

    @Test
    fun enter_on_line_with_rbrace_else_lbrace_defaults_smart() {
        val text = "    } else {"
        // Try caret positions after '}', before 'e', and after '{'
        val carets = listOf(5, 6, text.length)
        for (c in carets) {
            val res = applyEnter(text, c, c, tab)
            // Should not trigger special cases since line is not brace-only or only-spaces after '}'
            // Expect same indent (4 spaces)
            val expectedPrefix = text.substring(0, c) + "\n" + " ".repeat(4)
            assertEquals(expectedPrefix, res.text.substring(0, expectedPrefix.length))
            assertEquals(c + 1 + 4, res.selStart)
            assertEquals(res.selStart, res.selEnd)
        }
    }

    @Test
    fun enter_with_selection_replaces_and_uses_anchor_indent() {
        val text = (
            """
            1
                2
            3
            """
        ).trimIndent()
        // Select "2\n3" starting at column 4 of line 1 (indent = 4)
        val idxLine0 = text.indexOf('1')
        val idxLine1 = text.indexOf('\n', idxLine0) + 1
        val selStart = idxLine1 + 4 // after 4 spaces
        val selEnd = text.length
        val res = applyEnter(text, selStart, selEnd, tab)
        val expected = (
            """
            1
                
            """
        ).trimIndent()
        assertEquals(expected, res.text)
        assertEquals(expected.length, res.selStart)
        assertEquals(res.selStart, res.selEnd)
    }
}
