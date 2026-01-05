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

package net.sergeych.lyngweb

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorLogicTest {

    private val tab = 4

    @Test
    fun type_rbrace_dedents_if_only_char_on_line() {
        val text = "    "
        val res = applyChar(text, 4, 4, '}', tab)
        assertEquals("}", res.text)
        assertEquals(1, res.selStart)
    }

    @Test
    fun type_rbrace_no_dedent_if_not_only_char() {
        val text = "    foo "
        val res = applyChar(text, 8, 8, '}', tab)
        assertEquals("    foo }", res.text)
        assertEquals(9, res.selStart)
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

    /*
    @Test
    fun enter_before_line_with_only_rbrace_dedents_that_line() {
    ...
    */

    /*
    @Test
    fun enter_eol_before_brace_only_next_line_various_indents() {
    ...
    */

    /*
    @Test
    fun enter_eol_before_brace_only_next_line_various_indents_crlf() {
    ...
    */

    /*
    @Test
    fun enter_at_start_of_brace_only_line_at_cols_0_2_4() {
    ...
    */

    @Test
    fun enter_on_whitespace_only_line_keeps_same_indent() {
        val before = "    \nnext" // line 0 has 4 spaces only
        val caret = 4 // at end of spaces, before LF
        val res = applyEnter(before, caret, caret, tab)
        // Default smart indent should keep indent = 4
        // Original text: '    ' + '\n' + 'next'
        // Inserted: '\n' + '    ' at caret 4
        // Result: '    ' + '\n' + '    ' + '\n' + 'next'
        assertEquals("    \n    \nnext", res.text)
        // Caret at start of the new blank line with 4 spaces (after first newline)
        // lineStart(0) + indent(4) + newline(1) + newIndent(4) = 9
        assertEquals(4 + 1 + 4, res.selStart)
        assertEquals(res.selStart, res.selEnd)
    }

    /*
    @Test
    fun enter_on_line_with_rbrace_else_lbrace_defaults_smart() {
    ...
    */

    /*
    @Test
    fun enter_with_selection_replaces_and_uses_anchor_indent() {
    ...
    */
}
