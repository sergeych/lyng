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
 * Lightweight, pure editing helpers for the browser editor. These functions contain
 * no DOM calls and are suitable for unit testing in jsTest.
 */
package net.sergeych.lyngweb

data class EditResult(val text: String, val selStart: Int, val selEnd: Int)

private fun clamp(i: Int, lo: Int, hi: Int): Int = if (i < lo) lo else if (i > hi) hi else i
private fun safeSubstring(text: String, start: Int, end: Int): String {
    val s = clamp(start, 0, text.length)
    val e = clamp(end, 0, text.length)
    return if (e <= s) "" else text.substring(s, e)
}

private fun lineStartAt(text: String, idx: Int): Int {
    var i = idx - 1
    while (i >= 0 && text[i] != '\n') i--
    return i + 1
}

private fun lineEndAt(text: String, idx: Int): Int {
    var i = idx
    while (i < text.length && text[i] != '\n') i++
    return i
}

private fun countIndentSpaces(text: String, lineStart: Int, lineEnd: Int): Int {
    var i = lineStart
    // Tolerate optional CR at the start of the line (Windows newlines in some environments)
    if (i < lineEnd && text[i] == '\r') i++
    var n = 0
    while (i < lineEnd && text[i] == ' ') { i++; n++ }
    return n
}

private fun lastNonWsInLine(text: String, lineStart: Int, lineEnd: Int): Int {
    var i = lineEnd - 1
    while (i >= lineStart) {
        val ch = text[i]
        if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') return i
        i--
    }
    return -1
}

private fun prevNonWs(text: String, idxExclusive: Int): Int {
    var i = idxExclusive - 1
    while (i >= 0) {
        val ch = text[i]
        if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') return i
        i--
    }
    return -1
}

private fun nextNonWs(text: String, idxInclusive: Int): Int {
    var i = idxInclusive
    while (i < text.length) {
        val ch = text[i]
        if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') return i
        i++
    }
    return -1
}

/** Apply Enter key behavior with smart indent/undent rules. */
fun applyEnter(text: String, selStart: Int, selEnd: Int, tabSize: Int): EditResult {
    // Global early rule (Rule 3): if the line after the current line is brace-only, dedent that line by one block and
    // do NOT insert a newline. This uses precise line boundaries.
    run {
        val start = minOf(selStart, text.length)
        val eol = lineEndAt(text, start)
        val nextLineStart = if (eol < text.length && text[eol] == '\n') eol + 1 else eol
        val nextLineEnd = lineEndAt(text, nextLineStart)
        if (nextLineStart <= nextLineEnd && nextLineStart < text.length) {
            val trimmedNext = text.substring(nextLineStart, nextLineEnd).trim()
            if (trimmedNext == "}") {
                val rbraceIndent = countIndentSpaces(text, nextLineStart, nextLineEnd)
                val removeCount = kotlin.math.min(tabSize, rbraceIndent)
                val crShift = if (nextLineStart < text.length && text[nextLineStart] == '\r') 1 else 0
                val out = buildString(text.length) {
                    append(safeSubstring(text, 0, nextLineStart))
                    append(safeSubstring(text, nextLineStart + crShift + removeCount, text.length))
                }
                val caret = nextLineStart + kotlin.math.max(0, rbraceIndent - removeCount)
                return EditResult(out, caret, caret)
            }
        }
    }
    // Absolute top-priority: caret is exactly at a line break and the next line is '}'-only (ignoring spaces).
    // Dedent that next line by one block (tabSize) and do NOT insert a newline.
    run {
        if (selStart < text.length) {
            val isCrLf = selStart + 1 < text.length && text[selStart] == '\r' && text[selStart + 1] == '\n'
            val isLf = text[selStart] == '\n'
            if (isCrLf || isLf) {
                val nextLineStart = selStart + if (isCrLf) 2 else 1
                val nextLineEnd = lineEndAt(text, nextLineStart)
                if (nextLineStart <= nextLineEnd) {
                    val trimmed = text.substring(nextLineStart, nextLineEnd).trim()
                    if (trimmed == "}") {
                        val rbraceIndent = countIndentSpaces(text, nextLineStart, nextLineEnd)
                        val removeCount = kotlin.math.min(tabSize, rbraceIndent)
                        val crShift = if (nextLineStart < text.length && text[nextLineStart] == '\r') 1 else 0
                        val out = buildString(text.length) {
                            append(safeSubstring(text, 0, nextLineStart))
                            append(safeSubstring(text, nextLineStart + crShift + removeCount, text.length))
                        }
                        val caret = nextLineStart + kotlin.math.max(0, rbraceIndent - removeCount)
                        return EditResult(out, caret, caret)
                    }
                }
            }
        }
    }

    // If there is a selection, replace it by newline + current line indent
    if (selEnd != selStart) {
        val lineStart = lineStartAt(text, selStart)
        val lineEnd = lineEndAt(text, selStart)
        val indent = countIndentSpaces(text, lineStart, lineEnd)
        val insertion = "\n" + " ".repeat(indent)
        val out = safeSubstring(text, 0, selStart) + insertion + safeSubstring(text, selEnd, text.length)
        val caret = selStart + insertion.length
        return EditResult(out, caret, caret)
    }

    val start = selStart
    val lineStart = lineStartAt(text, start)
    val lineEnd = lineEndAt(text, start)
    val indent = countIndentSpaces(text, lineStart, lineEnd)

    // (Handled by the global early rule above; no need for additional EOL variants.)

    // Compute neighborhood characters early so rule precedence can use them
    val prevIdx = prevNonWs(text, start)
    val nextIdx = nextNonWs(text, start)
    val prevCh = if (prevIdx >= 0) text[prevIdx] else '\u0000'
    val nextCh = if (nextIdx >= 0) text[nextIdx] else '\u0000'
    val before = text.substring(0, start)
    val after = text.substring(start)

    // Rule 2: On a brace-only line '}' (caret on the same line)
    // If the current line’s trimmed text is exactly '}', decrease that line’s indent by one block (not below 0),
    // then insert a newline. The newly inserted line uses the (decreased) indent. Place the caret at the start of
    // the newly inserted line.
    run {
        val trimmed = text.substring(lineStart, lineEnd).trim()
        // IMPORTANT: Rule precedence — do NOT trigger this rule when caret is after '}' and the rest of the line
        // up to EOL contains only spaces (Rule 5 handles that case). That scenario must insert AFTER the brace,
        // not before it.
        var onlySpacesAfterCaret = true
        var k = start
        while (k < lineEnd) { if (text[k] != ' ') { onlySpacesAfterCaret = false; break }; k++ }
        val rule5Situation = (prevCh == '}') && onlySpacesAfterCaret

        if (trimmed == "}" && !rule5Situation) {
            val removeCount = kotlin.math.min(tabSize, indent)
            val newIndent = (indent - removeCount).coerceAtLeast(0)
            val crShift = if (lineStart < text.length && text[lineStart] == '\r') 1 else 0
            val out = buildString(text.length + 1 + newIndent) {
                append(safeSubstring(text, 0, lineStart))
                append("\n")
                append(" ".repeat(newIndent))
                // Write the brace line but with its indent reduced by removeCount spaces
                append(safeSubstring(text, lineStart + crShift + removeCount, text.length))
            }
            val caret = lineStart + 1 + newIndent
            return EditResult(out, caret, caret)
        }
    }

    // (The special case of caret after the last non-ws on a '}'-only line is covered by the rule above.)

    // 0) Caret is at end-of-line and the next line is a closing brace-only line: dedent that line, no extra newline
    run {
        val atCr = start + 1 < text.length && text[start] == '\r' && text[start + 1] == '\n'
        val atNl = start < text.length && text[start] == '\n'
        val atEol = atNl || atCr
        if (atEol) {
            val nlAdvance = if (atCr) 2 else 1
            val nextLineStart = start + nlAdvance
            val nextLineEnd = lineEndAt(text, nextLineStart)
            val trimmedNext = text.substring(nextLineStart, nextLineEnd).trim()
            if (trimmedNext == "}") {
                val rbraceIndent = countIndentSpaces(text, nextLineStart, nextLineEnd)
                // Dedent the '}' line by one block level (tabSize), but not below column 0
                val removeCount = kotlin.math.min(tabSize, rbraceIndent)
                val crShift = if (nextLineStart < text.length && text[nextLineStart] == '\r') 1 else 0
                val out = buildString(text.length) {
                    append(safeSubstring(text, 0, nextLineStart))
                    append(safeSubstring(text, nextLineStart + crShift + removeCount, text.length))
                }
                val caret = start + nlAdvance + kotlin.math.max(0, rbraceIndent - removeCount)
                return EditResult(out, caret, caret)
            }
        }
    }

    // 0b) If there is a newline at or after caret and the next line starts (ignoring spaces) with '}',
    // dedent that '}' line without inserting an extra newline.
    run {
        val nlPos = text.indexOf('\n', start)
        if (nlPos >= 0) {
            val nextLineStart = nlPos + 1
            val nextLineEnd = lineEndAt(text, nextLineStart)
            val nextLineFirstNonWs = nextNonWs(text, nextLineStart)
            if (nextLineFirstNonWs in nextLineStart until nextLineEnd && text[nextLineFirstNonWs] == '}') {
                val rbraceIndent = countIndentSpaces(text, nextLineStart, nextLineEnd)
                val removeCount = kotlin.math.min(tabSize, rbraceIndent)
                val crShift = if (nextLineStart < text.length && text[nextLineStart] == '\r') 1 else 0
                val out = buildString(text.length) {
                    append(safeSubstring(text, 0, nextLineStart))
                    append(safeSubstring(text, nextLineStart + crShift + removeCount, text.length))
                }
                val caret = nextLineStart + kotlin.math.max(0, rbraceIndent - removeCount)
                return EditResult(out, caret, caret)
            }
        }
    }

    // 1) Between braces { | } -> two lines, inner indented
    if (prevCh == '{' && nextCh == '}') {
        val innerIndent = indent + tabSize
        val insertion = "\n" + " ".repeat(innerIndent) + "\n" + " ".repeat(indent)
        val out = before + insertion + after
        val caret = start + 1 + innerIndent
        return EditResult(out, caret, caret)
    }
    // 2) After '{'
    if (prevCh == '{') {
        val insertion = "\n" + " ".repeat(indent + tabSize)
        val out = before + insertion + after
        val caret = start + insertion.length
        return EditResult(out, caret, caret)
    }
    // 3) Before '}'
    if (nextCh == '}') {
        // We want two things:
        //  - reduce indentation of the upcoming '}' line by one level
        //  - avoid creating an extra blank line if caret is already at EOL (the next char is a newline)

        // Compute where the '}' line starts and how many leading spaces it has
        val rbraceLineStart = lineStartAt(text, nextIdx)
        val rbraceLineEnd = lineEndAt(text, nextIdx)
        val rbraceIndent = countIndentSpaces(text, rbraceLineStart, rbraceLineEnd)
        // Dedent the '}' line by one block level (tabSize), but not below column 0
        val removeCount = kotlin.math.min(tabSize, rbraceIndent)
        val crShift = if (rbraceLineStart < text.length && text[rbraceLineStart] == '\r') 1 else 0

        // If there is already a newline between caret and the '}', do NOT insert another newline.
        // Just dedent the existing '}' line by one block and place caret at its start.
        run {
            val nlBetween = text.indexOf('\n', start)
            if (nlBetween in start until rbraceLineStart) {
                val out = buildString(text.length) {
                    append(safeSubstring(text, 0, rbraceLineStart))
                    append(safeSubstring(text, rbraceLineStart + crShift + removeCount, text.length))
                }
                val caret = rbraceLineStart + kotlin.math.max(0, rbraceIndent - removeCount)
                return EditResult(out, caret, caret)
            }
        }

        val hasNewlineAtCaret = (start < text.length && text[start] == '\n')

        // New indentation for the line we create (if we actually insert one now)
        val newLineIndent = (indent - tabSize).coerceAtLeast(0)
        val insertion = if (hasNewlineAtCaret) "" else "\n" + " ".repeat(newLineIndent)

        val out = buildString(text.length + insertion.length) {
            append(before)
            append(insertion)
            // keep text up to the start of '}' line
            append(safeSubstring(text, start, rbraceLineStart))
            // drop up to tabSize spaces before '}'
            append(safeSubstring(text, rbraceLineStart + crShift + removeCount, text.length))
        }
        val caret = if (hasNewlineAtCaret) {
            // Caret moves to the beginning of the '}' line after dedent (right after the single newline)
            start + 1 + kotlin.math.max(0, rbraceIndent - removeCount)
        } else {
            start + insertion.length
        }
        return EditResult(out, caret, caret)
    }
    // 4) After '}' with only trailing spaces before EOL
    // According to Rule 5: if the last non-whitespace before the caret is '}' and
    // only spaces remain until EOL, we must:
    //  - dedent the current (brace) line by one block (not below 0)
    //  - insert a newline just AFTER '}' (do NOT move caret backward)
    //  - set the caret at the start of the newly inserted blank line, whose indent equals the dedented indent
    if (prevCh == '}') {
        var onlySpaces = true
        var k = prevIdx + 1
        while (k < lineEnd) { if (text[k] != ' ') { onlySpaces = false; break }; k++ }
        if (onlySpaces) {
            val removeCount = kotlin.math.min(tabSize, indent)
            val newIndent = (indent - removeCount).coerceAtLeast(0)
            val crShift = if (lineStart < text.length && text[lineStart] == '\r') 1 else 0

            // Build the result:
            //  - keep everything before the line start
            //  - write the current line content up to the caret, but with its left indent reduced by removeCount
            //  - insert newline + spaces(newIndent)
            //  - drop trailing spaces after caret up to EOL
            //  - keep the rest of the text starting from EOL
            val out = buildString(text.length) {
                append(safeSubstring(text, 0, lineStart))
                append(safeSubstring(text, lineStart + crShift + removeCount, start))
                append("\n")
                append(" ".repeat(newIndent))
                append(safeSubstring(text, lineEnd, text.length))
            }
            val caret = (lineStart + (start - (lineStart + crShift + removeCount)) + 1 + newIndent)
            return EditResult(out, caret, caret)
        } else {
            // Default smart indent for cases where there are non-space characters after '}'
            val insertion = "\n" + " ".repeat(indent)
            val out = before + insertion + after
            val caret = start + insertion.length
            return EditResult(out, caret, caret)
        }
    }
    // 5) Fallback: if there is a newline ahead and the next line, trimmed, equals '}', dedent that '}' line by one block
    run {
        val nlPos = text.indexOf('\n', start)
        if (nlPos >= 0) {
            val nextLineStart = nlPos + 1
            val nextLineEnd = lineEndAt(text, nextLineStart)
            val trimmedNext = text.substring(nextLineStart, nextLineEnd).trim()
            if (trimmedNext == "}") {
                val rbraceIndent = countIndentSpaces(text, nextLineStart, nextLineEnd)
                val removeCount = kotlin.math.min(tabSize, rbraceIndent)
                val crShift = if (nextLineStart < text.length && text[nextLineStart] == '\r') 1 else 0
                val out = buildString(text.length) {
                    append(safeSubstring(text, 0, nextLineStart))
                    append(safeSubstring(text, nextLineStart + crShift + removeCount, text.length))
                }
                val caret = nextLineStart + kotlin.math.max(0, rbraceIndent - removeCount)
                return EditResult(out, caret, caret)
            }
        }
    }
    // default keep same indent
    run {
        val insertion = "\n" + " ".repeat(indent)
        val out = before + insertion + after
        val caret = start + insertion.length
        return EditResult(out, caret, caret)
    }
}

/** Apply Tab key: insert spaces at caret (single-caret only). */
fun applyTab(text: String, selStart: Int, selEnd: Int, tabSize: Int): EditResult {
    val spaces = " ".repeat(tabSize)
    val out = text.substring(0, selStart) + spaces + text.substring(selEnd)
    val caret = selStart + spaces.length
    return EditResult(out, caret, caret)
}

/** Apply Shift+Tab: outdent each selected line (or current line) by up to tabSize spaces. */
fun applyShiftTab(text: String, selStart: Int, selEnd: Int, tabSize: Int): EditResult {
    val start = minOf(selStart, selEnd)
    val end = maxOf(selStart, selEnd)
    val firstLineStart = lineStartAt(text, start)
    val lastLineEnd = lineEndAt(text, end)

    val sb = StringBuilder(text.length)
    if (firstLineStart > 0) sb.append(text, 0, firstLineStart)

    var i = firstLineStart
    var newSelStart = selStart
    var newSelEnd = selEnd
    while (i <= lastLineEnd) {
        val ls = i
        var le = i
        while (le < text.length && text[le] != '\n') le++
        val isLast = le >= lastLineEnd

        var j = ls
        var removed = 0
        while (j < le && removed < tabSize && text[j] == ' ') { j++; removed++ }

        sb.append(text, ls + removed, le)
        if (le < text.length && !isLast) sb.append('\n')

        fun adjustIndex(idx: Int): Int {
            if (idx <= ls) return idx
            val remBefore = when {
                idx >= le -> removed
                else -> kotlin.math.max(0, kotlin.math.min(removed, idx - ls))
            }
            return idx - remBefore
        }
        newSelStart = adjustIndex(newSelStart)
        newSelEnd = adjustIndex(newSelEnd)

        i = if (le < text.length) le + 1 else le + 1
        if (isLast) break
    }
    if (lastLineEnd < text.length) sb.append(text, lastLineEnd, text.length)

    val s = minOf(newSelStart, newSelEnd)
    val e = maxOf(newSelStart, newSelEnd)
    return EditResult(sb.toString(), s, e)
}
