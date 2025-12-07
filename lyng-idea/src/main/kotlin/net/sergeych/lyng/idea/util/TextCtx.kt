/*
 * Shared tiny, PSI-free text helpers for Lyng editor features (Quick Doc, Completion).
 */
package net.sergeych.lyng.idea.util

import com.intellij.openapi.util.TextRange

object TextCtx {
    fun prefixAt(text: String, offset: Int): String {
        val off = offset.coerceIn(0, text.length)
        var i = (off - 1).coerceAtLeast(0)
        while (i >= 0 && isIdentChar(text[i])) i--
        val start = i + 1
        return if (start in 0..text.length && start <= off) text.substring(start, off) else ""
    }

    fun wordRangeAt(text: String, offset: Int): TextRange? {
        if (text.isEmpty()) return null
        val off = offset.coerceIn(0, text.length)
        var s = off
        var e = off
        while (s > 0 && isIdentChar(text[s - 1])) s--
        while (e < text.length && isIdentChar(text[e])) e++
        return if (s < e) TextRange(s, e) else null
    }

    fun findDotLeft(text: String, offset: Int): Int? {
        var i = (offset - 1).coerceAtLeast(0)
        while (i >= 0 && text[i].isWhitespace()) i--
        return if (i >= 0 && text[i] == '.') i else null
    }

    fun previousWordBefore(text: String, offset: Int): String? {
        var i = prevNonWs(text, (offset - 1).coerceAtLeast(0))
        // Skip trailing identifier at caret if inside word
        while (i >= 0 && isIdentChar(text[i])) i--
        i = prevNonWs(text, i)
        if (i < 0) return null
        val end = i + 1
        while (i >= 0 && isIdentChar(text[i])) i--
        val start = i + 1
        return if (start < end) text.substring(start, end) else null
    }

    fun hasDotBetween(text: String, start: Int, end: Int): Boolean {
        if (start >= end) return false
        val s = start.coerceAtLeast(0)
        val e = end.coerceAtMost(text.length)
        for (i in s until e) if (text[i] == '.') return true
        return false
    }

    fun prevNonWs(text: String, start: Int): Int {
        var i = start.coerceAtMost(text.length - 1)
        while (i >= 0 && text[i].isWhitespace()) i--
        return i
    }

    fun isIdentChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
}
