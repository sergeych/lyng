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

import kotlinx.browser.window
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Source
import net.sergeych.lyng.binding.Binder
import net.sergeych.lyng.binding.SymbolKind
import net.sergeych.lyng.highlight.HighlightKind
import net.sergeych.lyng.highlight.SimpleLyngHighlighter
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.miniast.MiniAstBuilder
import net.sergeych.lyng.miniast.MiniClassDecl
import net.sergeych.lyng.miniast.MiniFunDecl
import net.sergeych.lyng.miniast.MiniTypeName
import org.w3c.dom.HTMLStyleElement

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

fun highlightLyngHtml(html: String): String {
    ensureLyngHighlightStyles()
    val preCodeRegex = Regex(
        pattern = """<pre(\s+[^>]*)?>\s*<code([^>]*)>([\s\S]*?)</code>\s*</pre>""",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    val classAttrRegex = Regex("""\bclass\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)

    return preCodeRegex.replace(html) { m ->
        val preAttrs = m.groups[1]?.value ?: ""
        val codeAttrs = m.groups[2]?.value ?: ""
        val codeHtml = m.groups[3]?.value ?: ""

        val cls = classAttrRegex.find(codeAttrs)?.groupValues?.getOrNull(2) ?: ""
        val classes = cls.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val codeHasLyng = classes.any { it.equals("language-lyng", ignoreCase = true) }
        val hasAnyLanguage = classes.any { it.startsWith("language-", ignoreCase = true) }
        val treatAsLyng = codeHasLyng || !hasAnyLanguage
        if (!treatAsLyng) return@replace m.value

        val text = htmlUnescape(codeHtml)
        val (headText, tailTextOrNull) = if (!codeHasLyng && !hasAnyLanguage) splitDoctestTail(text) else text to null

        val headHighlighted = try { applyLyngHighlightToText(headText) } catch (_: Throwable) { return@replace m.value }
        val tailHighlighted = tailTextOrNull?.let { renderDoctestTailAsComments(it) } ?: ""
        val highlighted = headHighlighted + tailHighlighted
        "<pre$preAttrs><code$codeAttrs>$highlighted</code></pre>"
    }
}

suspend fun highlightLyngHtmlAsync(html: String): String {
    ensureLyngHighlightStyles()
    val preCodeRegex = Regex(
        pattern = """<pre(\s+[^>]*)?>\s*<code([^>]*)>([\s\S]*?)</code>\s*</pre>""",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    val classAttrRegex = Regex("""\bclass\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)

    return try {
        val sb = StringBuilder(html.length + 64)
        var lastEnd = 0
        val matches = preCodeRegex.findAll(html).toList()
        for (m in matches) {
            if (m.range.first > lastEnd) sb.append(html, lastEnd, m.range.first)

            val preAttrs = m.groups[1]?.value ?: ""
            val codeAttrs = m.groups[2]?.value ?: ""
            val codeHtml = m.groups[3]?.value ?: ""

            val clsVal = classAttrRegex.find(codeAttrs)?.groupValues?.getOrNull(2) ?: ""
            val classes = clsVal.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            val codeHasLyng = classes.any { it.equals("language-lyng", ignoreCase = true) }
            val hasAnyLanguage = classes.any { it.startsWith("language-", ignoreCase = true) }
            val treatAsLyng = codeHasLyng || !hasAnyLanguage

            if (!treatAsLyng) {
                sb.append(m.value)
                lastEnd = m.range.last + 1
                continue
            }

            val text = htmlUnescape(codeHtml)
            val (headText, tailTextOrNull) = if (!codeHasLyng && !hasAnyLanguage) splitDoctestTail(text) else text to null
            val headHighlighted = applyLyngHighlightToTextAst(headText)
            val tailHighlighted = tailTextOrNull?.let { renderDoctestTailAsComments(it) } ?: ""
            val highlighted = headHighlighted + tailHighlighted
            sb.append("<pre").append(preAttrs).append(">")
                .append("<code").append(codeAttrs).append(">").append(highlighted).append("</code></pre>")

            lastEnd = m.range.last + 1
        }
        if (lastEnd < html.length) sb.append(html, lastEnd, html.length)
        sb.toString()
    } catch (_: Throwable) {
        highlightLyngHtml(html)
    }
}

fun ensureLyngHighlightStyles() {
    try {
        val doc = window.document
        if (doc.getElementById("lyng-highlight-style") == null) {
            val style = doc.createElement("style") as HTMLStyleElement
            style.id = "lyng-highlight-style"
            style.textContent = (
                """
                /* Lyng syntax highlighting defaults */
                .hl-kw  { color: #d73a49; font-weight: 600; }
                .hl-ty  { color: #6f42c1; }
                .hl-id  { color: #24292e; }
                /* Declarations (semantic roles) */
                .hl-fn  { color: #005cc5; font-weight: 600; }
                .hl-class { color: #5a32a3; font-weight: 600; }
                .hl-val { color: #1b7f5a; }
                .hl-var { color: #1b7f5a; text-decoration: underline dotted currentColor; }
                .hl-param { color: #0969da; font-style: italic; }
                .hl-num { color: #005cc5; }
                .hl-str { color: #032f62; }
                .hl-ch  { color: #032f62; }
                .hl-rx  { color: #116329; }
                .hl-cmt { color: #6a737d; font-style: italic; }
                .hl-op  { color: #8250df; }
                .hl-punc{ color: #57606a; }
                .hl-lbl { color: #e36209; }
                .hl-ann { color: #e36209; font-style: italic; }
                .hl-dir { color: #6f42c1; }
                .hl-err { color: #b31d28; text-decoration: underline wavy #b31d28; }

                /* Dark theme (Bootstrap data attribute) */
                [data-bs-theme="dark"] .hl-id   { color: #c9d1d9; }
                [data-bs-theme="dark"] .hl-op   { color: #d2a8ff; }
                [data-bs-theme="dark"] .hl-punc { color: #8b949e; }
                [data-bs-theme="dark"] .hl-kw   { color: #ff7b72; }
                [data-bs-theme="dark"] .hl-ty   { color: #d2a8ff; }
                [data-bs-theme="dark"] .hl-fn   { color: #79c0ff; font-weight: 700; }
                [data-bs-theme="dark"] .hl-class{ color: #d2a8ff; font-weight: 700; }
                [data-bs-theme="dark"] .hl-val  { color: #7ee787; }
                [data-bs-theme="dark"] .hl-var  { color: #7ee787; text-decoration: underline dotted currentColor; }
                [data-bs-theme="dark"] .hl-param{ color: #a5d6ff; font-style: italic; }
                [data-bs-theme="dark"] .hl-num  { color: #79c0ff; }
                [data-bs-theme="dark"] .hl-str,
                [data-bs-theme="dark"] .hl-ch   { color: #a5d6ff; }
                [data-bs-theme="dark"] .hl-rx   { color: #7ee787; }
                [data-bs-theme="dark"] .hl-cmt  { color: #8b949e; }
                [data-bs-theme="dark"] .hl-lbl  { color: #ffa657; }
                [data-bs-theme="dark"] .hl-ann  { color: #ffa657; font-style: italic; }
                [data-bs-theme="dark"] .hl-dir  { color: #d2a8ff; }
                [data-bs-theme="dark"] .hl-err  { color: #ffa198; text-decoration-color: #ffa198; }
                """
                .trimIndent()
            )
            doc.head?.appendChild(style)
        }
    } catch (_: Throwable) {
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

fun applyLyngHighlightToText(text: String): String {
    ensureLyngHighlightStyles()
    val spans = SimpleLyngHighlighter().highlight(text)
    if (spans.isEmpty()) return htmlEscape(text)
    val sb = StringBuilder(text.length + spans.size * 16)
    fun clamp(i: Int, lo: Int = 0, hi: Int = text.length): Int = i.coerceIn(lo, hi)
    fun safeSubstring(start: Int, endExclusive: Int): String {
        val s = clamp(start)
        val e = clamp(endExclusive)
        return if (e <= s) "" else text.substring(s, e)
    }

    // Compute declaration/param overrides by a fast textual scan
    val overrides = detectDeclarationAndParamOverrides(text).toMutableMap()

    // Helper: check next non-space char after a given end offset
    fun isFollowedBy(end: Int, ch: Char): Boolean {
        var i = end
        while (i < text.length) {
            val c = text[i]
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { i++; continue }
            return c == ch
        }
        return false
    }

    // 1) Mark function call-sites even in the sync path: identifier immediately followed by '(' or trailing block '{'
    run {
        for (s in spans) {
            if (s.kind == HighlightKind.Identifier) {
                val key = s.range.start to s.range.endExclusive
                if (!overrides.containsKey(key)) {
                    if (isFollowedBy(s.range.endExclusive, '(') || isFollowedBy(s.range.endExclusive, '{')) {
                        overrides[key] = "hl-fn"
                    }
                }
            }
        }
    }

    // 2) Color import module path segments after the `import` keyword as hl-dir (best-effort)
    run {
        var i = 0
        while (i < spans.size) {
            val s = spans[i]
            if (s.kind == HighlightKind.Identifier) {
                val token = safeSubstring(s.range.start, s.range.endExclusive)
                if (token == "import") {
                    var j = i + 1
                    while (j < spans.size) {
                        val sj = spans[j]
                        val segText = safeSubstring(sj.range.start, sj.range.endExclusive)
                        val isDot = sj.kind == HighlightKind.Punctuation && segText == "."
                        if (sj.kind == HighlightKind.Identifier) {
                            overrides[sj.range.start to sj.range.endExclusive] = "hl-dir"
                        } else if (!isDot) {
                            break
                        }
                        j++
                    }
                    i = j - 1
                }
            }
            i++
        }
    }

    var pos = 0
    for (s in spans) {
        if (s.range.start > pos) sb.append(htmlEscape(safeSubstring(pos, s.range.start)))
        val cls = when (s.kind) {
            HighlightKind.Identifier -> overrides[s.range.start to s.range.endExclusive] ?: cssClassForKind(s.kind)
            HighlightKind.Label -> {
                val lex = safeSubstring(s.range.start, s.range.endExclusive)
                if (lex.startsWith("@")) "hl-ann" else cssClassForKind(s.kind)
            }
            else -> cssClassForKind(s.kind)
        }
        sb.append('<').append("span class=\"").append(cls).append('\"').append('>')
        sb.append(htmlEscape(safeSubstring(s.range.start, s.range.endExclusive)))
        sb.append("</span>")
        pos = clamp(s.range.endExclusive)
    }
    if (pos < text.length) sb.append(htmlEscape(text.substring(pos)))
    return sb.toString()
}

/**
 * AST-backed highlighter: uses the compiler's optional Mini-AST to precisely mark
 * declaration names (functions, classes, vals/vars), parameters, and type name segments.
 * Falls back to token-only rendering if anything goes wrong.
 */
suspend fun applyLyngHighlightToTextAst(text: String): String {
    return try {
        // Ensure CSS present
        ensureLyngHighlightStyles()
        val source = Source("<web>", text)
        // Token baseline
        val tokenSpans = SimpleLyngHighlighter().highlight(text)
        if (tokenSpans.isEmpty()) return htmlEscape(text)

        // Build Mini-AST
        val sink = MiniAstBuilder()
        Compiler.compileWithMini(text, sink)
        val mini = sink.build()

        // Collect overrides from AST and Binding with precise offsets
        val overrides = HashMap<Pair<Int, Int>, String>()
        fun putName(startPos: net.sergeych.lyng.Pos, name: String, cls: String) {
            val s = source.offsetOf(startPos)
            val e = s + name.length
            if (s >= 0 && e <= text.length && s < e) overrides[s to e] = cls
        }
        // Declarations
        mini?.declarations?.forEach { d ->
            when (d) {
                is MiniFunDecl -> putName(d.nameStart, d.name, "hl-fn")
                is MiniClassDecl -> putName(d.nameStart, d.name, "hl-class")
                is net.sergeych.lyng.miniast.MiniValDecl -> putName(d.nameStart, d.name, if (d.mutable) "hl-var" else "hl-val")
            }
        }
        // Imports: color each segment as directive/path
        mini?.imports?.forEach { imp ->
            imp.segments.forEach { seg ->
                val s = source.offsetOf(seg.range.start)
                val e = source.offsetOf(seg.range.end)
                if (s >= 0 && e <= text.length && s < e) overrides[s to e] = "hl-dir"
            }
        }
        // Parameters
        mini?.declarations?.filterIsInstance<MiniFunDecl>()?.forEach { fn ->
            fn.params.forEach { p -> putName(p.nameStart, p.name, "hl-param") }
        }
        // Type name segments
        fun addTypeSegments(t: net.sergeych.lyng.miniast.MiniTypeRef?) {
            when (t) {
                is MiniTypeName -> t.segments.forEach { seg ->
                    val s = source.offsetOf(seg.range.start)
                    val e = s + seg.name.length
                    if (s >= 0 && e <= text.length && s < e) overrides[s to e] = "hl-ty"
                }
                is net.sergeych.lyng.miniast.MiniGenericType -> {
                    addTypeSegments(t.base)
                    t.args.forEach { addTypeSegments(it) }
                }
                else -> {}
            }
        }
        mini?.declarations?.forEach { d ->
            when (d) {
                is MiniFunDecl -> {
                    addTypeSegments(d.returnType)
                    d.params.forEach { addTypeSegments(it.type) }
                }
                is net.sergeych.lyng.miniast.MiniValDecl -> addTypeSegments(d.type)
                is MiniClassDecl -> {}
            }
        }

        // Apply binder results to mark usages by semantic kind (params, locals, top-level, functions, classes)
        try {
            if (mini != null) {
                val binding = Binder.bind(text, mini)
                // Map decl ranges to avoid overriding declarations
                val declKeys = HashSet<Pair<Int, Int>>()
                for (sym in binding.symbols) {
                    declKeys += (sym.declStart to sym.declEnd)
                }
                fun classForKind(k: SymbolKind): String? = when (k) {
                    SymbolKind.Function -> "hl-fn"
                    SymbolKind.Class, SymbolKind.Enum -> "hl-class"
                    SymbolKind.Param -> "hl-param"
                    SymbolKind.Val -> "hl-val"
                    SymbolKind.Var -> "hl-var"
                }
                for (ref in binding.references) {
                    val key = ref.start to ref.end
                    if (declKeys.contains(key)) continue
                    if (!overrides.containsKey(key)) {
                        val sym = binding.symbols.firstOrNull { it.id == ref.symbolId }
                        val cls = sym?.let { classForKind(it.kind) }
                        if (cls != null) overrides[key] = cls
                    }
                }
            }
        } catch (_: Throwable) {
            // Binder is best-effort; ignore on any failure
        }

        fun isFollowedByParen(rangeEnd: Int): Boolean {
            var i = rangeEnd
            while (i < text.length) {
                val ch = text[i]
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') { i++; continue }
                return ch == '('
            }
            return false
        }

        fun isFollowedByBlock(rangeEnd: Int): Boolean {
            var i = rangeEnd
            while (i < text.length) {
                val ch = text[i]
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') { i++; continue }
                return ch == '{'
            }
            return false
        }

        // First: mark function call-sites (identifier immediately followed by '('), best-effort.
        // Do this before vars/params so it takes precedence where both could match.
        run {
            for (s in tokenSpans) {
                if (s.kind == HighlightKind.Identifier) {
                    val key = s.range.start to s.range.endExclusive
                    if (!overrides.containsKey(key)) {
                        if (isFollowedByParen(s.range.endExclusive) || isFollowedByBlock(s.range.endExclusive)) {
                            overrides[key] = "hl-fn"
                        }
                    }
                }
            }
        }

        // Highlight usages of top-level vals/vars and parameters (best-effort, no binder yet)
        val nameRoleMap = HashMap<String, String>(8)
        mini?.declarations?.forEach { d ->
            when (d) {
                is net.sergeych.lyng.miniast.MiniValDecl -> nameRoleMap[d.name] = if (d.mutable) "hl-var" else "hl-val"
                is MiniFunDecl -> d.params.forEach { p -> nameRoleMap[p.name] = "hl-param" }
                else -> {}
            }
        }
        // For every identifier token not already overridden, apply role based on known names
        for (s in tokenSpans) {
            if (s.kind == HighlightKind.Identifier) {
                val key = s.range.start to s.range.endExclusive
                if (!overrides.containsKey(key)) {
                    val ident = text.substring(s.range.start, s.range.endExclusive)
                    val cls = nameRoleMap[ident]
                    if (cls != null) {
                        // Avoid marking function call sites as vars/params
                        if (!isFollowedByParen(s.range.endExclusive)) {
                            overrides[key] = cls
                        }
                    }
                }
            }
        }

        // Render merging overrides
        val sb = StringBuilder(text.length + tokenSpans.size * 16)
        var pos = 0
        for (s in tokenSpans) {
            if (s.range.start > pos) sb.append(htmlEscape(text.substring(pos, s.range.start)))
            val cls = when (s.kind) {
                HighlightKind.Identifier -> overrides[s.range.start to s.range.endExclusive] ?: cssClassForKind(s.kind)
                HighlightKind.TypeName -> overrides[s.range.start to s.range.endExclusive] ?: cssClassForKind(s.kind)
                else -> cssClassForKind(s.kind)
            }
            sb.append('<').append("span class=\"").append(cls).append('\"').append('>')
            sb.append(htmlEscape(text.substring(s.range.start, s.range.endExclusive)))
            sb.append("</span>")
            pos = s.range.endExclusive
        }
        if (pos < text.length) sb.append(htmlEscape(text.substring(pos)))
        sb.toString()
    } catch (_: Throwable) {
        // Fallback to legacy path (token + heuristic overlay)
        applyLyngHighlightToText(text)
    }
}

// Map of (start,end) -> cssClass for declaration/param identifiers
private fun detectDeclarationAndParamOverrides(text: String): Map<Pair<Int, Int>, String> {
    val result = HashMap<Pair<Int, Int>, String>()
    val n = text.length
    var i = 0
    fun isIdentStart(ch: Char) = ch == '_' || ch == '$' || ch == '~' || ch.isLetter()
    fun isIdentPart(ch: Char) = ch == '_' || ch == '$' || ch == '~' || ch.isLetterOrDigit()
    // A conservative list of language keywords to avoid misclassifying as function calls
    val kw = setOf(
        "package", "import", "fun", "fn", "class", "enum", "val", "var",
        "if", "else", "while", "do", "for", "when", "try", "catch", "finally",
        "throw", "return", "break", "continue", "in", "is", "as", "as?", "not",
        "true", "false", "null", "private", "protected", "open", "extern", "static"
    )
    fun skipWs(idx0: Int): Int {
        var idx = idx0
        while (idx < n) {
            val c = text[idx]
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') idx++ else break
        }
        return idx
    }
    fun readIdent(idx0: Int): Pair<String, Int>? {
        var idx = idx0
        if (idx >= n || !isIdentStart(text[idx])) return null
        val start = idx
        idx++
        while (idx < n && isIdentPart(text[idx])) idx++
        return text.substring(start, idx) to idx
    }
    fun readQualified(idx0: Int): Pair<Unit, Int> {
        var idx = idx0
        // Read A(.B)*
        var first = true
        while (true) {
            val id = readIdent(idx) ?: return Unit to idx0 // nothing read
            idx = id.second
            val save = idx
            if (idx < n && text[idx] == '.') { idx++; first = false; continue } else { idx = save; break }
        }
        return Unit to idx
    }
    while (i < n) {
        // scan for keywords fun/fn/val/var/class with word boundaries
        if (text.startsWith("fun", i) && (i == 0 || !isIdentPart(text[i-1])) && (i+3 >= n || !isIdentPart(text.getOrNull(i+3) ?: '\u0000'))) {
            var p = skipWs(i + 3)
            // optional receiver Type.
            val (u, p2) = readQualified(p)
            p = p2
            if (p < n && text[p] == '.') {
                p++
            }
            p = skipWs(p)
            val name = readIdent(p)
            if (name != null) {
                val start = p; val end = name.second
                result[start to end] = "hl-fn"
                // Try to find params list and mark parameter names
                var q = skipWs(end)
                if (q < n && text[q] == '(') {
                    q++
                    loop@ while (q < n) {
                        q = skipWs(q)
                        // end of params
                        if (q < n && text[q] == ')') { q++; break }
                        val param = readIdent(q)
                        if (param != null) {
                            val ps = q; val pe = param.second
                            // ensure followed by ':' (type)
                            var t = skipWs(pe)
                            if (t < n && text[t] == ':') {
                                result[ps to pe] = "hl-param"
                                q = t + 1
                            } else {
                                q = pe
                            }
                        } else {
                            // skip until next comma or ')'
                            while (q < n && text[q] != ',' && text[q] != ')') q++
                        }
                        q = skipWs(q)
                        if (q < n && text[q] == ',') { q++; continue@loop }
                        if (q < n && text[q] == ')') { q++; break@loop }
                    }
                }
            }
            i = p
            continue
        }
        if (text.startsWith("fn", i) && (i == 0 || !isIdentPart(text[i-1])) && (i+2 >= n || !isIdentPart(text.getOrNull(i+2) ?: '\u0000'))) {
            // Treat same as fun
            var p = skipWs(i + 2)
            val (u, p2) = readQualified(p)
            p = p2
            if (p < n && text[p] == '.') p++
            p = skipWs(p)
            val name = readIdent(p)
            if (name != null) {
                val start = p; val end = name.second
                result[start to end] = "hl-fn"
            }
            i = p
            continue
        }
        if (text.startsWith("val", i) && (i == 0 || !isIdentPart(text[i-1])) && (i+3 >= n || !isIdentPart(text.getOrNull(i+3) ?: '\u0000'))) {
            var p = skipWs(i + 3)
            val name = readIdent(p)
            if (name != null) result[p to name.second] = "hl-val"
            i = p
            continue
        }
        if (text.startsWith("var", i) && (i == 0 || !isIdentPart(text[i-1])) && (i+3 >= n || !isIdentPart(text.getOrNull(i+3) ?: '\u0000'))) {
            var p = skipWs(i + 3)
            val name = readIdent(p)
            if (name != null) result[p to name.second] = "hl-var"
            i = p
            continue
        }
        if (text.startsWith("class", i) && (i == 0 || !isIdentPart(text[i-1])) && (i+5 >= n || !isIdentPart(text.getOrNull(i+5) ?: '\u0000'))) {
            var p = skipWs(i + 5)
            val name = readIdent(p)
            if (name != null) result[p to name.second] = "hl-class"
            i = p
            continue
        }
        // Generic function call site: ident followed by '(' (after optional spaces)
        readIdent(i)?.let { (name, endIdx) ->
            val startIdx = i
            // Avoid keywords; allow member calls too (a.b()) by not checking preceding char
            if (name !in kw) {
                var q = skipWs(endIdx)
                if (q < n && text[q] == '(') {
                    // Mark as function identifier at call site
                    result[startIdx to endIdx] = "hl-fn"
                    i = endIdx
                    return@let
                }
            }
        }
        i++
    }
    return result
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
