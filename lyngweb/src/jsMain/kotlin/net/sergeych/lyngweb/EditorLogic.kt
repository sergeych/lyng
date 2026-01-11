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
    val lineTrimmed = text.substring(lineStart, lineEnd).trim()

    // Compute neighborhood characters early so rule precedence can use them
    val prevIdx = prevNonWs(text, start)
    val nextIdx = nextNonWs(text, start)
    val prevCh = if (prevIdx >= 0) text[prevIdx] else '\u0000'
    val nextCh = if (nextIdx >= 0) text[nextIdx] else '\u0000'
    val before = text.substring(0, start)
    val after = text.substring(start)

    // Rule 4: Between braces on the same line {|}
    if (prevCh == '{' && nextCh == '}') {
        val innerIndent = indent + tabSize
        val insertion = "\n" + " ".repeat(innerIndent) + "\n" + " ".repeat(indent)
        val out = before + insertion + after
        val caret = start + 1 + innerIndent
        return EditResult(out, caret, caret)
    }

    // Rule 2: On a brace-only line '}'
    if (lineTrimmed == "}") {
        val newIndent = (indent - tabSize).coerceAtLeast(0)
        val newCurrentLine = " ".repeat(newIndent) + "}"
        val insertion = "\n" + " ".repeat(newIndent)
        val out = safeSubstring(text, 0, lineStart) + newCurrentLine + insertion + safeSubstring(text, lineEnd, text.length)
        val caret = lineStart + newCurrentLine.length + insertion.length
        return EditResult(out, caret, caret)
    }

    // Rule 1: After '{'
    if (prevCh == '{') {
        val insertion = "\n" + " ".repeat(indent + tabSize)
        val out = before + insertion + after
        val caret = start + insertion.length
        return EditResult(out, caret, caret)
    }

    // Rule 3: End of a line before a brace-only next line
    if (start == lineEnd && lineEnd < text.length) {
        val nextLineStart = lineEnd + 1
        val nextLineEnd = lineEndAt(text, nextLineStart)
        val nextLineTrimmed = text.substring(nextLineStart, nextLineEnd).trim()
        if (nextLineTrimmed == "}") {
            val nextLineIndent = countIndentSpaces(text, nextLineStart, nextLineEnd)
            val newNextLineIndent = (nextLineIndent - tabSize).coerceAtLeast(0)
            val newNextLine = " ".repeat(newNextLineIndent) + "}"
            val out = text.substring(0, nextLineStart) + newNextLine + text.substring(nextLineEnd)
            val caret = nextLineStart + newNextLineIndent
            return EditResult(out, caret, caret)
        }
    }

    // Rule 5: After '}' with only spaces until end-of-line
    val afterCaretOnLine = text.substring(start, lineEnd)
    if (prevCh == '}' && afterCaretOnLine.trim().isEmpty()) {
        val newIndent = (indent - tabSize).coerceAtLeast(0)
        val insertion = "\n" + " ".repeat(newIndent)
        val out = before + insertion + after
        val caret = start + insertion.length
        return EditResult(out, caret, caret)
    }

    // Rule 6: Default smart indent
    val insertion = "\n" + " ".repeat(indent)
    val out = before + insertion + after
    val caret = start + insertion.length
    return EditResult(out, caret, caret)
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

/**
 * Apply a typed character. If the character is '}', and it's the only non-whitespace on the line,
 * it may be dedented.
 */
fun applyChar(text: String, selStart: Int, selEnd: Int, ch: Char, tabSize: Int): EditResult {
    // Selection replacement
    val current = if (selStart != selEnd) {
        text.substring(0, minOf(selStart, selEnd)) + text.substring(maxOf(selStart, selEnd))
    } else text
    val pos = minOf(selStart, selEnd)

    val before = current.substring(0, pos)
    val after = current.substring(pos)
    val newText = before + ch + after
    val newPos = pos + 1

    if (ch == '}') {
        val lineStart = lineStartAt(newText, pos)
        val lineEnd = lineEndAt(newText, newPos)
        val trimmed = newText.substring(lineStart, lineEnd).trim()
        if (trimmed == "}") {
            // Dedent this line
            val indent = countIndentSpaces(newText, lineStart, lineEnd)
            val removeCount = minOf(tabSize, indent)
            if (removeCount > 0) {
                val out = newText.substring(0, lineStart) + newText.substring(lineStart + removeCount)
                return EditResult(out, newPos - removeCount, newPos - removeCount)
            }
        }
    }

    return EditResult(newText, newPos, newPos)
}
