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
 * Thin site-side wrapper for highlighting text with lynglib and producing HTML spans
 * with the same CSS classes as used in Main.kt.
 */
package net.sergeych.site

import net.sergeych.lyng.highlight.HighlightKind
import net.sergeych.lyng.highlight.SimpleLyngHighlighter

// Kept only to avoid breaking imports if any remain; actual implementation moved to :lyngweb
// Use net.sergeych.site.SiteHighlight from :lyngweb instead. This local copy is renamed and unused.
@Deprecated("Use lyngweb: net.sergeych.site.SiteHighlight")
object SiteHighlightLocal {
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
        HighlightKind.EnumConstant -> "hl-enumc"
    }

    private fun htmlEscape(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

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
