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

package net.sergeych.lyng.highlight

import net.sergeych.lyng.Pos
import net.sergeych.lyng.Source
import net.sergeych.lyng.Token.Type
import net.sergeych.lyng.parseLyng

/** Extension that converts a [Pos] (line/column) into absolute character offset in the [Source] text. */
fun Source.offsetOf(pos: Pos): Int {
    var off = 0
    // Sum full preceding lines + one '\n' per line (lines[] were created by String.lines())
    var i = 0
    while (i < pos.line) {
        off += lines[i].length + 1 // assume \n as separator
        i++
    }
    off += pos.column
    return off
}

private val reservedIdKeywords = setOf("constructor", "property")
// Fallback textual keywords that might come from the lexer as ID in some contexts (e.g., snippets)
private val fallbackKeywordIds = setOf(
    // boolean operators
    "and", "or", "not",
    // declarations & modifiers
    "fun", "fn", "class", "interface", "enum", "val", "var", "import", "package",
    "abstract", "closed", "override",
    "private", "protected", "static", "open", "extern", "init", "get", "set", "by",
    // control flow and misc
    "if", "else", "when", "while", "do", "for", "try", "catch", "finally",
    "throw", "return", "break", "continue", "this", "null", "true", "false", "unset"
)

/** Maps lexer token type (and sometimes value) to a [HighlightKind]. */
private fun kindOf(type: Type, value: String): HighlightKind? = when (type) {
    // identifiers and reserved ids
    Type.ID -> when {
        value in reservedIdKeywords -> HighlightKind.Keyword
        value.lowercase() in fallbackKeywordIds -> HighlightKind.Keyword
        else -> HighlightKind.Identifier
    }

    // numbers
    Type.INT, Type.REAL, Type.HEX -> HighlightKind.Number

    // text literals
    Type.STRING, Type.STRING2 -> HighlightKind.String
    Type.CHAR -> HighlightKind.Char
    Type.REGEX -> HighlightKind.Regex

    // comments
    Type.SINGLE_LINE_COMMENT, Type.MULTILINE_COMMENT -> HighlightKind.Comment

    // punctuation
    Type.LPAREN, Type.RPAREN, Type.LBRACE, Type.RBRACE, Type.LBRACKET, Type.RBRACKET,
    Type.COMMA, Type.SEMICOLON, Type.COLON -> HighlightKind.Punctuation

    // textual control keywords
    Type.IN, Type.NOTIN, Type.IS, Type.NOTIS, Type.AS, Type.ASNULL, Type.BY, Type.OBJECT,
    Type.AND, Type.OR, Type.NOT -> HighlightKind.Keyword

    // labels / annotations
    Type.LABEL, Type.ATLABEL -> HighlightKind.Label

    // operators and symbolic constructs
    Type.PLUS, Type.MINUS, Type.STAR, Type.SLASH, Type.PERCENT,
    Type.ASSIGN, Type.PLUSASSIGN, Type.MINUSASSIGN, Type.STARASSIGN, Type.SLASHASSIGN, Type.PERCENTASSIGN, Type.IFNULLASSIGN,
    Type.PLUS2, Type.MINUS2,
    Type.EQ, Type.NEQ, Type.LT, Type.LTE, Type.GT, Type.GTE, Type.REF_EQ, Type.REF_NEQ, Type.MATCH, Type.NOTMATCH,
    Type.DOT, Type.ARROW, Type.EQARROW, Type.QUESTION, Type.COLONCOLON,
    Type.SHL, Type.SHR, Type.ELLIPSIS, Type.DOTDOT, Type.DOTDOTLT,
    Type.NULL_COALESCE, Type.ELVIS, Type.NULL_COALESCE_INDEX, Type.NULL_COALESCE_INVOKE, Type.NULL_COALESCE_BLOCKINVOKE,
    Type.SHUTTLE,
    // bitwise textual operators (treat as operators for visuals)
    Type.BITAND, Type.BITOR, Type.BITXOR, Type.BITNOT -> HighlightKind.Operator

    // non-highlighting tokens
    Type.NEWLINE, Type.EOF -> null
}

/** Merge contiguous spans of the same [HighlightKind] to reduce output size. */
private fun mergeAdjacent(spans: List<HighlightSpan>): List<HighlightSpan> {
    if (spans.isEmpty()) return spans
    val out = ArrayList<HighlightSpan>(spans.size)
    var prev = spans[0]
    for (i in 1 until spans.size) {
        val cur = spans[i]
        if (cur.kind == prev.kind && cur.range.start == prev.range.endExclusive) {
            prev = HighlightSpan(TextRange(prev.range.start, cur.range.endExclusive), prev.kind)
        } else {
            out += prev
            prev = cur
        }
    }
    out += prev
    return out
}

/** Simple highlighter using the existing Lyng lexer (no incremental support yet). */
class SimpleLyngHighlighter : LyngHighlighter {
    override fun highlight(text: String): List<HighlightSpan> {
        val src = Source("<snippet>", text)
        val tokens = parseLyng(src)
        val raw = ArrayList<HighlightSpan>(tokens.size)
        fun adjustQuoteSpan(startOffset: Int, quoteChar: Char): TextRange {
            var s = startOffset
            if (s > 0 && text[s - 1] == quoteChar) s -= 1
            var i = s + 1
            while (i < text.length) {
                val ch = text[i]
                if (ch == '\\') {
                    i += if (i + 1 < text.length) 2 else 1
                    continue
                }
                if (ch == quoteChar) {
                    return TextRange(s, i + 1)
                }
                i++
            }
            // Unterminated, highlight till end
            return TextRange(s, text.length)
        }

        for (t in tokens) {
            val k = kindOf(t.type, t.value) ?: continue
            val start0 = src.offsetOf(t.pos)
            val range = when (t.type) {
                Type.STRING, Type.STRING2 -> adjustQuoteSpan(start0, '"')
                Type.CHAR -> adjustQuoteSpan(start0, '\'')
                Type.HEX -> {
                    // Parser returns HEX token value without the leading "0x"; include it in highlight span
                    val end = (start0 + 2 + t.value.length).coerceAtMost(text.length)
                    TextRange(start0, end)
                }
                Type.ATLABEL -> {
                    // Parser returns value without leading '@'; token pos points at '@'.
                    // So we need to include '@' (1 char) + the identifier length.
                    val end = (start0 + 1 + t.value.length).coerceAtMost(text.length)
                    TextRange(start0, end)
                }
                else -> TextRange(start0, (start0 + t.value.length).coerceAtMost(text.length))
            }
            if (range.endExclusive > range.start) raw += HighlightSpan(range, k)
        }
        // Heuristics: mark enum constants in declaration blocks and on qualified usages Foo.BAR
        val overridden = applyEnumConstantHeuristics(text, src, tokens, raw)
        // Adjust single-line comment spans to extend till EOL to compensate for lexer offset/length quirks
        val adjusted = extendSingleLineCommentsToEol(text, overridden)
        // Spans are in order; merge adjacent of the same kind for compactness
        return mergeAdjacent(adjusted)
    }
}

/**
 * Workaround/fix: ensure that single-line comment spans that start with `//` extend until the end of line.
 * Drops any subsequent spans that would overlap the extended comment on the same line.
 */
private fun extendSingleLineCommentsToEol(
    text: String,
    spans: List<HighlightSpan>
): List<HighlightSpan> {
    if (spans.isEmpty()) return spans
    val out = ArrayList<HighlightSpan>(spans.size)
    var i = 0
    while (i < spans.size) {
        val s = spans[i]
        if (s.kind == HighlightKind.Comment) {
            // Check the original text actually has '//' at span start to avoid touching block comments
            val start = s.range.start
            val ahead = if (start in text.indices) text.substring(start, minOf(text.length, start + 2)) else ""
            if (ahead == "//") {
                // Extend to end of current line
                val eol = text.indexOf('\n', start)
                val newEnd = if (eol >= 0) eol else text.length
                var j = i + 1
                while (j < spans.size && spans[j].range.start < newEnd) {
                    // Consume all overlapping spans on this line
                    j++
                }
                out += HighlightSpan(TextRange(start, newEnd), s.kind)
                i = j
                continue
            }
        }
        out += s
        i++
    }
    return out
}

/**
 * Detect enum constants both in enum declarations and in qualified usages (TypeName.CONST)
 * and override corresponding identifier spans with EnumConstant kind.
 */
private fun applyEnumConstantHeuristics(
    text: String,
    src: Source,
    tokens: List<net.sergeych.lyng.Token>,
    spans: MutableList<HighlightSpan>
): MutableList<HighlightSpan> {
    if (tokens.isEmpty() || spans.isEmpty()) return spans

    // Build quick lookup from range start to span index for identifiers only
    val byStart = HashMap<Int, Int>(spans.size * 2)
    for (i in spans.indices) {
        val s = spans[i]
        if (s.kind == HighlightKind.Identifier) byStart[s.range.start] = i
    }

    fun overrideIdAtToken(idx: Int) {
        val t = tokens[idx]
        if (t.type != Type.ID) return
        val start = src.offsetOf(t.pos)
        val spanIndex = byStart[start] ?: return
        spans[spanIndex] = HighlightSpan(spans[spanIndex].range, HighlightKind.EnumConstant)
    }

    // 1) Enum declarations: enum Name { CONST1, CONST2 }
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i]
        if (t.type == Type.ID && t.value.equals("enum", ignoreCase = true)) {
            // expect: ID(enum) ID(name) LBRACE (ID (COMMA ID)* ) RBRACE
            var j = i + 1
            // skip optional whitespace/newlines tokens are separate types, so we just check IDs and braces
            if (j < tokens.size && tokens[j].type == Type.ID) j++ else { i++; continue }
            if (j < tokens.size && tokens[j].type == Type.LBRACE) {
                j++
                while (j < tokens.size) {
                    val tk = tokens[j]
                    if (tk.type == Type.RBRACE) { j++; break }
                    if (tk.type == Type.ID) {
                        // enum entry declaration
                        overrideIdAtToken(j)
                        j++
                        // optional comma
                        if (j < tokens.size && tokens[j].type == Type.COMMA) { j++ ; continue }
                        continue
                    }
                    // Any unexpected token ends enum entries scan
                    break
                }
                i = j
                continue
            }
        }
        i++
    }

    // 2) Qualified usages: Something.CONST where CONST is ALL_UPPERCASE (with digits/underscores)
    fun isAllUpperCase(name: String): Boolean = name.isNotEmpty() && name.all { it == '_' || it.isDigit() || (it.isLetter() && it.isUpperCase()) }
    i = 1
    while (i + 0 < tokens.size) {
        val dotTok = tokens[i]
        if (dotTok.type == Type.DOT && i + 1 < tokens.size) {
            val next = tokens[i + 1]
            if (next.type == Type.ID && isAllUpperCase(next.value)) {
                overrideIdAtToken(i + 1)
                i += 2
                continue
            }
        }
        i++
    }

    return spans
}
