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
 * Site-like wrapper for Lyng highlighter that renders HTML spans with CSS classes
 * compatible with the site styles. Kept in package net.sergeych.site for backwards
 * compatibility with existing code and tests.
 */
package net.sergeych.site

import net.sergeych.lyng.highlight.HighlightKind
import net.sergeych.lyng.highlight.SimpleLyngHighlighter
import net.sergeych.lyngweb.htmlEscape

/**
 * Minimal HTML renderer for Lyng syntax highlighting, compatible with the site CSS.
 *
 * This object is kept in the legacy package `net.sergeych.site` to preserve
 * backward compatibility with existing imports and tests in dependent modules.
 * It renders spans with the `hl-*` classes used by the site (e.g., `hl-kw`,
 * `hl-id`, `hl-num`).
 */
object SiteHighlight {
    private fun cssClassForKind(kind: HighlightKind): String = when (kind) {
        HighlightKind.Keyword -> "hl-kw"
        HighlightKind.TypeName -> "hl-ty"
        HighlightKind.Identifier -> "hl-id"
        HighlightKind.Number -> "hl-num"
        HighlightKind.String -> "hl-str"
        HighlightKind.Char -> "hl-ch"
        HighlightKind.Regex -> "hl-rx"
        HighlightKind.Comment -> "hl-cmt"
        HighlightKind.Operator -> "hl-op"
        HighlightKind.Punctuation -> "hl-punc"
        HighlightKind.Label -> "hl-lbl"
        HighlightKind.Directive -> "hl-dir"
        HighlightKind.Error -> "hl-err"
    }

    /**
     * Converts plain Lyng source [text] into HTML with `<span>` wrappers using
     * site-compatible `hl-*` classes.
     *
     * Non-highlighted parts are HTML-escaped. If the highlighter returns no
     * tokens, the entire string is returned as an escaped plain text.
     *
     * Example:
     * ```kotlin
     * val html = SiteHighlight.renderHtml("assertEquals(1, 1)")
     * // => "<span class=\"hl-id\">assertEquals</span><span class=\"hl-punc\">(</span>..."
     * ```
     *
     * @param text Lyng code to render (plain text).
     * @return HTML string with `hl-*` styled tokens.
     */
    fun renderHtml(text: String): String {
        val highlighter = SimpleLyngHighlighter()
        val spans = highlighter.highlight(text)
        if (spans.isEmpty()) return htmlEscape(text)
        val sb = StringBuilder(text.length + spans.size * 16)
        var pos = 0
        for (s in spans) {
            if (s.range.start > pos) sb.append(htmlEscape(text.substring(pos, s.range.start)))
            val cls = cssClassForKind(s.kind)
            sb.append('<').append("span class=\"").append(cls).append('\"').append('>')
            sb.append(htmlEscape(text.substring(s.range.start, s.range.endExclusive)))
            sb.append("</span>")
            pos = s.range.endExclusive
        }
        if (pos < text.length) sb.append(htmlEscape(text.substring(pos)))
        return sb.toString()
    }
}
