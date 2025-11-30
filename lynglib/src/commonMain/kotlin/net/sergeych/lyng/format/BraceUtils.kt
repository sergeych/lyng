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
package net.sergeych.lyng.format

/**
 * Simple text-based brace utilities for Lyng.
 *
 * The scan ignores everything after `//` on a line.
 */
object BraceUtils {
    /**
     * Finds the index of the matching opening '{' for the closing '}' at [closeIndex].
     * Returns null if not found. The scan moves left from [closeIndex] and ignores text after // comments.
     */
    fun findMatchingOpenBrace(text: CharSequence, closeIndex: Int): Int? {
        if (closeIndex < 0 || closeIndex >= text.length || text[closeIndex] != '}') return null
        var i = closeIndex
        var balance = 0
        while (i >= 0) {
            val ch = text[i]
            if (ch == '\n') {
                // skip comment tails on this line by finding // and ignoring chars after it
                val lineStart = lastIndexOf(text, '\n', i - 1) + 1
                val slashes = indexOf(text, '/', lineStart, i)
                if (slashes >= 0 && slashes + 1 <= i && slashes + 1 < text.length && text[slashes + 1] == '/') {
                    i = slashes - 1
                    continue
                }
            }
            when (ch) {
                '}' -> balance++
                '{' -> {
                    // When scanning left, the matching '{' is the point where balance==1
                    // (i.e., it pairs with the first '}' we started from)
                    if (balance == 1) return i else if (balance > 1) balance--
                }
            }
            i--
        }
        return null
    }

    /** Returns the start offset of the line that contains [offset]. */
    fun lineStart(text: CharSequence, offset: Int): Int {
        var i = (offset.coerceIn(0, text.length)) - 1
        while (i >= 0) {
            if (text[i] == '\n') return i + 1
            i--
        }
        return 0
    }

    /** Returns the end offset (exclusive) of the line that contains [offset]. */
    fun lineEnd(text: CharSequence, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i < text.length) {
            if (text[i] == '\n') return i
            i++
        }
        return text.length
    }

    /**
     * Finds the enclosing block range when pressing Enter after a closing brace '}' at [closeIndex].
     * Returns an [IntRange] covering from the start of the opening-brace line through the end of the
     * closing-brace line. If [includeTrailingNewline] is true and there is a newline after '}', the range
     * extends to the start of the next line.
     */
    fun findEnclosingBlockRange(
        text: CharSequence,
        closeIndex: Int,
        includeTrailingNewline: Boolean = true
    ): IntRange? {
        // Be tolerant: if closeIndex doesn't point at '}', scan left to nearest '}'
        var ci = closeIndex.coerceIn(0, text.length)
        while (ci >= 0 && text[ci] != '}') ci--
        if (ci < 0) return null
        val open = findMatchingOpenBrace(text, ci) ?: return null
        val start = lineStart(text, open)
        val closeLineEnd = lineEnd(text, ci)
        val endExclusive = if (includeTrailingNewline && closeLineEnd < text.length && text[closeLineEnd] == '\n')
            closeLineEnd + 1 else closeLineEnd
        return start until endExclusive
    }

    private fun lastIndexOf(text: CharSequence, ch: Char, fromIndex: Int): Int {
        var i = fromIndex
        while (i >= 0) {
            if (text[i] == ch) return i
            i--
        }
        return -1
    }

    private fun indexOf(text: CharSequence, ch: Char, fromIndex: Int, toIndexExclusive: Int): Int {
        var i = fromIndex
        val to = toIndexExclusive.coerceAtMost(text.length)
        while (i < to) {
            if (text[i] == ch) return i
            i++
        }
        return -1
    }
}
