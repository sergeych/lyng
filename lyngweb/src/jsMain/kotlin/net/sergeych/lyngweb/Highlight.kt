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
 * Shared Lyng HTML highlighting utilities for Compose HTML apps
 */
package net.sergeych.lyngweb

import net.sergeych.lyng.highlight.HighlightKind
import net.sergeych.lyng.highlight.SimpleLyngHighlighter

/**
 * Adds a Bootstrap-friendly `code` class to every opening `<pre>` tag in the provided HTML.
 *
 * This is a lightweight post-processing step for Markdown-rendered HTML to ensure that
 * code blocks (wrapped in `<pre>...</pre>`) receive consistent styling in Bootstrap-based
 * sites without requiring changes in the Markdown renderer.
 *
 * Behavior:
 * - If a `<pre>` has no `class` attribute, a `class="code"` attribute is added.
 * - If a `<pre>` already has a `class` attribute but not `code`, the word `code` is appended.
 * - Other attributes and their order are preserved.
 *
 * Example:
 * ```kotlin
 * val withClasses = ensureBootstrapCodeBlocks("<pre><code>println(1)</code></pre>")
 * // => "<pre class=\"code\"><code>println(1)</code></pre>"
 * ```
 *
 * @param html HTML text to transform.
 * @return HTML with `<pre>` tags normalized to include the `code` class.
 */
fun ensureBootstrapCodeBlocks(html: String): String {
    val preTagRegex = Regex("""<pre(\s+[^>]*)?>""", RegexOption.IGNORE_CASE)
    val classAttrRegex = Regex("""\bclass\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)

    return preTagRegex.replace(html) { match ->
        val attrs = match.groups[1]?.value ?: ""
        if (attrs.isBlank()) return@replace "<pre class=\"code\">"

        var newAttrs = attrs
        val m = classAttrRegex.find(attrs)
        if (m != null) {
            val quote = m.groupValues[1]
            val classes = m.groupValues[2]
            val hasCode = classes.split("\\s+".toRegex()).any { it == "code" }
            if (!hasCode) {
                val updated = "class=" + quote + (classes.trim().let { if (it.isEmpty()) "code" else "$it code" }) + quote
                newAttrs = attrs.replaceRange(m.range, updated)
            }
        } else {
            newAttrs = " class=\"code\"" + attrs
        }
        "<pre$newAttrs>"
    }
}

/**
 * Highlights Lyng code blocks inside Markdown-produced HTML.
 *
 * Searches for sequences of `<pre><code ...>...</code></pre>` and, if the `<code>` element
 * carries class `language-lyng` (or if it has no `language-*` class at all), applies Lyng
 * syntax highlighting, replacing the inner HTML with spans that use `hl-*` CSS classes
 * (e.g. `hl-kw`, `hl-id`, `hl-num`, `hl-cmt`).
 *
 * Special handling:
 * - If a block has no explicit language class, doctest-style result lines starting with
 *   `>>>` at the end of the block are rendered as comments (`hl-cmt`).
 * - If the block specifies another language (e.g. `language-kotlin`), the block is left
 *   unchanged.
 *
 * Example:
 * ```kotlin
 * val mdHtml = """
 *   <pre><code class=\"language-lyng\">and or not\n</code></pre>
 * """.trimIndent()
 * val highlighted = highlightLyngHtml(mdHtml)
 * ```
 *
 * @param html HTML produced by a Markdown renderer.
 * @return HTML with Lyng code blocks highlighted using `hl-*` classes.
 */
fun highlightLyngHtml(html: String): String {
    // Regex to find <pre> ... <code class="language-lyng ...">(content)</code> ... </pre>
    val preCodeRegex = Regex(
        pattern = """<pre(\s+[^>]*)?>\s*<code([^>]*)>([\s\S]*?)</code>\s*</pre>""",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    val classAttrRegex = Regex("""\bclass\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)

    return preCodeRegex.replace(html) { m ->
        val preAttrs = m.groups[1]?.value ?: ""
        val codeAttrs = m.groups[2]?.value ?: ""
        val codeHtml = m.groups[3]?.value ?: ""

        val codeHasLyng = run {
            val cls = classAttrRegex.find(codeAttrs)?.groupValues?.getOrNull(2) ?: ""
            cls.split("\\s+".toRegex()).any { it.equals("language-lyng", ignoreCase = true) }
        }
        val hasAnyLanguage = run {
            val cls = classAttrRegex.find(codeAttrs)?.groupValues?.getOrNull(2) ?: ""
            cls.split("\\s+".toRegex()).any { it.startsWith("language-", ignoreCase = true) }
        }

        val treatAsLyng = codeHasLyng || !hasAnyLanguage
        if (!treatAsLyng) return@replace m.value

        val text = htmlUnescape(codeHtml)

        val (headText, tailTextOrNull) = if (!codeHasLyng && !hasAnyLanguage) splitDoctestTail(text) else text to null

        val headHighlighted = try {
            applyLyngHighlightToText(headText)
        } catch (_: Throwable) {
            return@replace m.value
        }
        val tailHighlighted = tailTextOrNull?.let { renderDoctestTailAsComments(it) } ?: ""

        val highlighted = headHighlighted + tailHighlighted
        "<pre$preAttrs><code$codeAttrs>$highlighted</code></pre>"
    }
}

private fun splitDoctestTail(text: String): Pair<String, String?> {
    if (text.isEmpty()) return "" to null
    val hasTrailingNewline = text.endsWith("\n")
    val lines = text.split("\n")
    var i = lines.size - 1
    while (i >= 0 && lines[i].isEmpty()) i--
    var count = 0
    while (i >= 0) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (trimmed.isNotEmpty() && !trimmed.startsWith(">>>")) break
        if (trimmed.isEmpty()) {
            if (count == 0) break else { count++; i--; continue }
        }
        count++
        i--
    }
    if (count == 0) return text to null
    val splitIndex = lines.size - count
    val head = buildString {
        for (idx in 0 until splitIndex) {
            append(lines[idx])
            if (idx < lines.size - 1 || hasTrailingNewline) append('\n')
        }
    }
    val tail = buildString {
        for (idx in splitIndex until lines.size) {
            append(lines[idx])
            if (idx < lines.size - 1 || hasTrailingNewline) append('\n')
        }
    }
    return head to tail
}

private fun renderDoctestTailAsComments(tail: String): String {
    if (tail.isEmpty()) return ""
    val sb = StringBuilder(tail.length + 32)
    var start = 0
    while (start <= tail.lastIndex) {
        val nl = tail.indexOf('\n', start)
        val line = if (nl >= 0) tail.substring(start, nl) else tail.substring(start)
        sb.append("<span class=\"hl-cmt\">")
        sb.append(htmlEscape(line))
        sb.append("</span>")
        if (nl >= 0) sb.append('\n')
        if (nl < 0) break else start = nl + 1
    }
    return sb.toString()
}

/**
 * Converts plain Lyng source text into HTML with syntax-highlight spans.
 *
 * Tokens are wrapped in `<span>` elements with `hl-*` classes (e.g., `hl-kw`, `hl-id`).
 * Text between tokens is HTML-escaped and preserved. If no tokens are detected,
 * the whole text is returned HTML-escaped.
 *
 * This is a low-level utility used by [highlightLyngHtml]. If you already have
 * Markdown-produced HTML with `<pre><code>` blocks, prefer calling [highlightLyngHtml].
 *
 * @param text Lyng source code (plain text, not HTML-escaped).
 * @return HTML string with `hl-*` spans.
 */
fun applyLyngHighlightToText(text: String): String {
    val spans = SimpleLyngHighlighter().highlight(text)
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
 * Escapes special HTML characters in a plain text string.
 *
 * Replacements:
 * - `&` → `&amp;`
 * - `<` → `&lt;`
 * - `>` → `&gt;`
 * - `"` → `&quot;`
 * - `'` → `&#39;`
 *
 * @param s Text to escape.
 * @return HTML-escaped text safe to insert into an HTML context.
 */
fun htmlEscape(s: String): String = buildString(s.length) {
    for (ch in s) when (ch) {
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '&' -> append("&amp;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(ch)
    }
}

private fun htmlUnescape(s: String): String {
    return s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
