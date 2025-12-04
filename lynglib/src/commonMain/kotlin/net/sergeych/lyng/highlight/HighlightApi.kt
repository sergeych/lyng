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
 * Cross-platform highlighting API for the Lyng language.
 */
package net.sergeych.lyng.highlight

/** Represents a half-open character range [start, endExclusive). */
data class TextRange(val start: Int, val endExclusive: Int) {
    init { require(start <= endExclusive) { "Invalid range: $start..$endExclusive" } }
}

/** Kinds of tokens for syntax highlighting. */
enum class HighlightKind {
    Keyword,
    TypeName,
    Identifier,
    Number,
    String,
    Char,
    Regex,
    Comment,
    Operator,
    Punctuation,
    Label,
    Directive,
    Error,
    /** Enum constant (both declaration and usage). */
    EnumConstant,
}

/** A highlighted span: character range and its semantic/lexical kind. */
data class HighlightSpan(val range: TextRange, val kind: HighlightKind)

/** Base interface for Lyng syntax highlighters. */
interface LyngHighlighter {
    /**
     * Produce highlight spans for the given [text]. Spans are non-overlapping and
     * ordered by start position. Adjacent spans of the same kind may be merged.
     */
    fun highlight(text: String): List<HighlightSpan>
}
