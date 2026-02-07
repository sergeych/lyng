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

package net.sergeych.lyngweb

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import net.sergeych.lyng.highlight.TextRange
import net.sergeych.lyng.miniast.CompletionItem
import net.sergeych.lyng.tools.LyngAnalysisResult
import net.sergeych.lyng.tools.LyngSymbolInfo
import net.sergeych.lyng.tools.LyngSymbolTarget
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.events.SyntheticKeyboardEvent
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement

/**
 * A lightweight, dependency-free code editor for Compose HTML that renders syntax highlight
 * in an overlay while keeping the native textarea for input and caret/selection.
 *
 * Features:
 * - Pure editor: no built-in buttons or actions; wire shortcuts via [onKeyDown].
 * - Tab insertion and smart newline indentation.
 * - Keeps overlay scroll, paddings, and line-height in sync with the textarea for glyph alignment.
 * - No external CSS dependency: all essential styles are injected inline.
 *
 * Parameters:
 * - [code]: current text value.
 * - [setCode]: callback to update text.
 * - [tabSize]: number of spaces to insert on Tab and used for visual tab width.
 * - [onKeyDown]: optional raw keydown hook to handle shortcuts like Ctrl/Cmd+Enter.
 */
@Composable
fun EditorWithOverlay(
    code: String,
    setCode: (String) -> Unit,
    tabSize: Int = 4,
    onKeyDown: ((SyntheticKeyboardEvent) -> Unit)? = null,
    onAnalysisReady: ((LyngAnalysisResult) -> Unit)? = null,
    onCompletionRequested: ((Int, List<CompletionItem>) -> Unit)? = null,
    onDefinitionResolved: ((Int, LyngSymbolTarget?) -> Unit)? = null,
    onUsagesResolved: ((Int, List<TextRange>) -> Unit)? = null,
    onDocRequested: ((Int, LyngSymbolInfo?) -> Unit)? = null,
    // New sizing controls
    minRows: Int = 6,
    maxRows: Int? = null,
    autoGrow: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var containerEl by remember { mutableStateOf<HTMLElement?>(null) }
    var overlayEl by remember { mutableStateOf<HTMLElement?>(null) }
    var diagOverlayEl by remember { mutableStateOf<HTMLElement?>(null) }
    var taEl by remember { mutableStateOf<HTMLTextAreaElement?>(null) }
    var lastGoodHtml by remember { mutableStateOf<String?>(null) }
    var lastGoodText by remember { mutableStateOf<String?>(null) }
    var pendingSelStart by remember { mutableStateOf<Int?>(null) }
    var pendingSelEnd by remember { mutableStateOf<Int?>(null) }
    var pendingScrollTop by remember { mutableStateOf<Double?>(null) }
    var pendingScrollLeft by remember { mutableStateOf<Double?>(null) }
    var cachedLineHeight by remember { mutableStateOf<Double?>(null) }
    var cachedVInsets by remember { mutableStateOf<Double?>(null) }
    var cachedCharWidth by remember { mutableStateOf<Double?>(null) }
    var lastAnalysis by remember { mutableStateOf<LyngAnalysisResult?>(null) }
    var lastAnalysisText by remember { mutableStateOf<String?>(null) }
    var lineStarts by remember { mutableStateOf(IntArray(0)) }
    var tooltipText by remember { mutableStateOf<String?>(null) }
    var tooltipX by remember { mutableStateOf<Double?>(null) }
    var tooltipY by remember { mutableStateOf<Double?>(null) }

    fun ensureMetrics(ta: HTMLTextAreaElement) {
        if (cachedLineHeight == null || cachedVInsets == null || cachedCharWidth == null) {
            val cs = window.getComputedStyle(ta)
            val lhStr = cs.getPropertyValue("line-height").trim()
            val lh = lhStr.removeSuffix("px").toDoubleOrNull() ?: 20.0
            fun parsePx(name: String): Double {
                val v = cs.getPropertyValue(name).trim().removeSuffix("px").toDoubleOrNull()
                return v ?: 0.0
            }
            val pt = parsePx("padding-top")
            val pb = parsePx("padding-bottom")
            val bt = parsePx("border-top-width")
            val bb = parsePx("border-bottom-width")
            cachedLineHeight = lh
            cachedVInsets = pt + pb + bt + bb

            val canvas = window.document.createElement("canvas") as HTMLCanvasElement
            val ctx = canvas.getContext("2d") as? CanvasRenderingContext2D
            if (ctx != null) {
                val fontSize = cs.fontSize
                val fontFamily = cs.fontFamily
                val fontWeight = cs.fontWeight
                val fontStyle = cs.fontStyle
                ctx.font = "$fontStyle $fontWeight $fontSize $fontFamily"
                val m = ctx.measureText("M")
                val w = if (m.width > 0.0) m.width else 8.0
                cachedCharWidth = w
            } else {
                cachedCharWidth = 8.0
            }
        }
    }

    fun rowsToPx(rows: Int): Double? {
        val lh = cachedLineHeight ?: return null
        val ins = cachedVInsets ?: 0.0
        return lh * rows + ins
    }

    fun adjustTextareaHeight() {
        val ta = taEl ?: return
        if (!autoGrow) return
        ensureMetrics(ta)
        // reset to auto to measure full scrollHeight
        ta.style.height = "auto"
        val minPx = rowsToPx(minRows)
        val maxPx = maxRows?.let { rowsToPx(it) }
        var target = ta.scrollHeight.toDouble()
        if (minPx != null && target < minPx) target = minPx
        if (maxPx != null && target > maxPx) target = maxPx
        // Apply target height
        ta.style.height = "${target}px"
    }

    suspend fun ensureAnalysis(text: String): LyngAnalysisResult {
        val cached = lastAnalysis
        val cachedText = lastAnalysisText
        if (cached != null && cachedText == text) return cached
        val analysis = LyngWebTools.analyze(text)
        lastAnalysis = analysis
        lastAnalysisText = text
        onAnalysisReady?.invoke(analysis)
        return analysis
    }

    fun htmlEscapeLocal(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    fun buildLineStarts(text: String): IntArray {
        val starts = ArrayList<Int>(maxOf(1, text.length / 16))
        starts.add(0)
        for (i in text.indices) {
            if (text[i] == '\n') starts.add(i + 1)
        }
        return starts.toIntArray()
    }

    fun offsetFromMouse(ta: HTMLTextAreaElement, clientX: Double, clientY: Double): Int? {
        ensureMetrics(ta)
        val rect = ta.getBoundingClientRect()
        val lineHeight = cachedLineHeight ?: return null
        val charWidth = cachedCharWidth ?: return null
        val cs = window.getComputedStyle(ta)
        fun parsePx(name: String): Double {
            val v = cs.getPropertyValue(name).trim().removeSuffix("px").toDoubleOrNull()
            return v ?: 0.0
        }
        val padLeft = parsePx("padding-left") + parsePx("border-left-width")
        val padTop = parsePx("padding-top") + parsePx("border-top-width")
        val x = clientX - rect.left + ta.scrollLeft - padLeft
        val y = clientY - rect.top + ta.scrollTop - padTop
        if (y < 0) return 0
        val lineIdx = (y / lineHeight).toInt().coerceAtLeast(0)
        if (lineStarts.isEmpty()) return 0
        val actualLineIdx = lineIdx.coerceAtMost(lineStarts.size - 1)
        val lineStart = lineStarts[actualLineIdx]
        val lineEnd = if (actualLineIdx + 1 < lineStarts.size) lineStarts[actualLineIdx + 1] - 1 else code.length
        val lineLen = (lineEnd - lineStart).coerceAtLeast(0)
        val col = (x / charWidth).toInt().coerceAtLeast(0)
        val clampedCol = col.coerceAtMost(lineLen)
        return (lineStart + clampedCol).coerceIn(0, code.length)
    }

    // Update overlay HTML whenever code changes
    LaunchedEffect(code) {
        fun clamp(i: Int, lo: Int, hi: Int): Int = if (i < lo) lo else if (i > hi) hi else i
        fun safeSubstring(text: String, start: Int, end: Int): String {
            val s = clamp(start, 0, text.length)
            val e = clamp(end, 0, text.length)
            return if (e <= s) "" else text.substring(s, e)
        }

        fun trimHtmlToTextPrefix(html: String, prefixChars: Int): String {
            if (prefixChars <= 0) return ""
            var i = 0
            var textCount = 0
            val n = html.length
            val out = StringBuilder(prefixChars + 64)
            val stack = mutableListOf<String>()
            while (i < n && textCount < prefixChars) {
                val ch = html[i]
                if (ch == '<') {
                    val close = html.indexOf('>', i).let { if (it < 0) n - 1 else it }
                    if (close == -1) break
                    val tag = safeSubstring(html, i, close + 1)
                    out.append(tag)
                    val tagLower = tag.lowercase()
                    if (tagLower.startsWith("<span")) {
                        stack.add("</span>")
                    } else if (tagLower.startsWith("</span")) {
                        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    }
                    i = (close + 1).coerceAtMost(n)
                } else if (ch == '&') {
                    val semi = html.indexOf(';', i + 1).let { if (it == -1) n - 1 else it }
                    val entity = safeSubstring(html, i, semi + 1)
                    out.append(entity)
                    textCount += 1
                    i = (semi + 1).coerceAtMost(n)
                } else {
                    out.append(ch)
                    textCount += 1
                    i += 1
                }
            }
            for (j in stack.size - 1 downTo 0) out.append(stack[j])
            return out.toString()
        }

        fun appendSentinel(html: String): String =
            html + "<span data-sentinel=\"1\">&#8203;</span>"

        try {
            // Prefer AST-backed highlighting for precise roles; it gracefully falls back.
            val html = SiteHighlight.renderHtmlAsync(code)
            overlayEl?.innerHTML = appendSentinel(html)
            lastGoodHtml = html
            lastGoodText = code
        } catch (_: Throwable) {
            val prevHtml = lastGoodHtml
            val prevText = lastGoodText
            if (prevHtml != null && prevText != null) {
                val max = minOf(prevText.length, code.length)
                var k = 0
                while (k < max && prevText[k] == code[k]) k++
                val prefixLen = k
                val trimmed = trimHtmlToTextPrefix(prevHtml, prefixLen)
                val tail = code.substring(prefixLen)
                val combined = trimmed + htmlEscapeLocal(tail)
                overlayEl?.innerHTML = appendSentinel(combined)
            } else {
                overlayEl?.innerHTML = appendSentinel(htmlEscapeLocal(code))
            }
        }

        val st = pendingScrollTop ?: (taEl?.scrollTop ?: 0.0)
        val sl = pendingScrollLeft ?: (taEl?.scrollLeft ?: 0.0)
        overlayEl?.scrollTop = st
        overlayEl?.scrollLeft = sl
        diagOverlayEl?.scrollTop = st
        diagOverlayEl?.scrollLeft = sl
        pendingScrollTop = null
        pendingScrollLeft = null
        // If text changed and autoGrow enabled, adjust height
        adjustTextareaHeight()
        lineStarts = buildLineStarts(code)
    }

    fun buildDiagnosticsHtml(
        text: String,
        diagnostics: List<net.sergeych.lyng.tools.LyngDiagnostic>
    ): String {
        if (diagnostics.isEmpty()) return ""
        val ranges = diagnostics.mapNotNull { d ->
            val r = d.range ?: return@mapNotNull null
            if (r.start < 0 || r.endExclusive <= r.start || r.endExclusive > text.length) return@mapNotNull null
            Triple(r, d.severity, d.message)
        }.sortedBy { it.first.start }
        if (ranges.isEmpty()) return ""
        val out = StringBuilder(text.length + 64)
        var cursor = 0
        for ((range, severity, message) in ranges) {
            if (range.start < cursor) continue
            if (cursor < range.start) {
                out.append(htmlEscapeLocal(text.substring(cursor, range.start)))
            }
            val color = when (severity) {
                net.sergeych.lyng.tools.LyngDiagnosticSeverity.Error -> "#dc3545"
                net.sergeych.lyng.tools.LyngDiagnosticSeverity.Warning -> "#ffc107"
            }
            val seg = htmlEscapeLocal(text.substring(range.start, range.endExclusive))
            val tip = htmlEscapeLocal(message).replace("\"", "&quot;")
            out.append("<span title=\"").append(tip).append("\" style=\"text-decoration-line:underline;text-decoration-style:wavy;")
            out.append("text-decoration-color:").append(color).append(";\">")
            out.append(seg)
            out.append("</span>")
            cursor = range.endExclusive
        }
        if (cursor < text.length) out.append(htmlEscapeLocal(text.substring(cursor)))
        return out.toString()
    }

    fun diagnosticMessageAt(offset: Int, analysis: LyngAnalysisResult?): String? {
        val list = analysis?.diagnostics ?: return null
        for (d in list) {
            val r = d.range ?: continue
            if (offset in r.start until r.endExclusive) return d.message
        }
        return null
    }

    fun updateCaretTooltip() {
        val ta = taEl ?: return
        val offset = ta.selectionStart ?: return
        val msg = diagnosticMessageAt(offset, lastAnalysis)
        if (msg.isNullOrBlank()) {
            ta.removeAttribute("title")
        } else {
            ta.setAttribute("title", msg)
        }
    }

    fun updateHoverTooltip(clientX: Double, clientY: Double) {
        val ta = taEl ?: return
        val offset = offsetFromMouse(ta, clientX, clientY) ?: return
        val msg = diagnosticMessageAt(offset, lastAnalysis)
        if (msg.isNullOrBlank()) {
            tooltipText = null
            return
        }
        val container = containerEl ?: return
        val rect = container.getBoundingClientRect()
        tooltipText = msg
        tooltipX = (clientX - rect.left + 12.0).coerceAtLeast(0.0)
        tooltipY = (clientY - rect.top + 12.0).coerceAtLeast(0.0)
    }

    LaunchedEffect(code, lastAnalysis) {
        val analysis = lastAnalysis ?: return@LaunchedEffect
        if (lastAnalysisText != code) {
            diagOverlayEl?.innerHTML = htmlEscapeLocal(code)
            updateCaretTooltip()
            return@LaunchedEffect
        }
        val html = buildDiagnosticsHtml(code, analysis.diagnostics)
        val content = if (html.isEmpty()) htmlEscapeLocal(code) else html
        diagOverlayEl?.innerHTML = content
        updateCaretTooltip()
    }

    LaunchedEffect(code, onAnalysisReady) {
        if (onAnalysisReady == null) return@LaunchedEffect
        try {
            ensureAnalysis(code)
        } catch (_: Throwable) {
        }
    }

    fun setSelection(start: Int, end: Int = start) {
        (taEl ?: return).apply {
            selectionStart = start
            selectionEnd = end
            focus()
        }
    }

    Div({
        // avoid external CSS dependency: ensure base positioning inline
        classes("position-relative")
        attr("style", "position:relative;")
        ref { it ->
            containerEl = it
            onDispose { if (containerEl === it) containerEl = null }
        }
    }) {
        // Overlay: highlighted code
        org.jetbrains.compose.web.dom.Div({
            // Do not depend on any external class name like "editor-overlay"
            // Provide fully inline styling; classes left empty to avoid external deps
            attr(
                "style",
                buildString {
                    append("position:absolute; left:0; top:0; right:0; bottom:0;")
                    append("overflow:auto; box-sizing:border-box; white-space:pre-wrap; word-break:break-word; tab-size:")
                    append(tabSize)
                    append("; margin:0; pointer-events:none; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, \"Liberation Mono\", \"Courier New\", monospace;")
                }
            )
            ref { it ->
                overlayEl = it
                onDispose { if (overlayEl === it) overlayEl = null }
            }
        }) {}

        // Diagnostics overlay: transparent text with wavy underlines
        org.jetbrains.compose.web.dom.Div({
            attr(
                "style",
                buildString {
                    append("position:absolute; left:0; top:0; right:0; bottom:0;")
                    append("overflow:auto; box-sizing:border-box; white-space:pre-wrap; word-break:break-word; tab-size:")
                    append(tabSize)
                    append("; margin:0; pointer-events:none; color:transparent;")
                }
            )
            ref { it ->
                diagOverlayEl = it
                onDispose { if (diagOverlayEl === it) diagOverlayEl = null }
            }
        }) {}

        // Textarea: user input with transparent text
        org.jetbrains.compose.web.dom.TextArea(value = code, attrs = {
            ref { ta ->
                taEl = ta
                // Cache metrics and adjust size on first mount
                ensureMetrics(ta)
                adjustTextareaHeight()
                onDispose { if (taEl === ta) taEl = null }
            }
            // Avoid relying on external classes; still allow host app to override via CSS
            // Make typed text transparent (overlay renders the colored text), but keep caret visible
            attr(
                "style",
                buildString {
                    append("width:100%; background:transparent; position:relative; z-index:1; tab-size:")
                    append(tabSize)
                    append("; color:transparent; -webkit-text-fill-color:transparent; ")
                    // Make caret visible even though text color is transparent
                    append("caret-color: var(--bs-body-color, #212529);")
                    // Basic input look without relying on external CSS
                    append(" border: 1px solid var(--bs-border-color, #ced4da); border-radius: .375rem;")
                    append(" padding: .5rem .75rem; box-sizing: border-box;")
                    // Remove UA focus outline that may appear as a red border in some themes
                    append(" outline: none; box-shadow: none;")
                    // Typography and rendering
                    append(" font-variant-ligatures: none; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, \"Liberation Mono\", \"Courier New\", monospace;")
                    // Keep previous visual minimum for TryLyng unless overridden by rows logic
                    append(" min-height:220px;")
                }
            )
            // Disable browser corrections for a code editor
            attr("spellcheck", "false")
            attr("autocorrect", "off")
            attr("autocapitalize", "off")
            attr("autocomplete", "off")
            // Provide a baseline number of rows for browsers that use it
            attr("rows", minRows.toString())
            placeholder("Enter Lyng code here…")

            onInput { ev ->
                val v = (ev.target as HTMLTextAreaElement).value
                setCode(v)
                adjustTextareaHeight()
                updateCaretTooltip()
            }

            onKeyDown { ev ->
                // bubble to caller first so they may intercept shortcuts
                onKeyDown?.invoke(ev)
                if (ev.defaultPrevented) return@onKeyDown
                val ta = taEl ?: return@onKeyDown
                val key = ev.key
                val keyLower = key.lowercase()
                // If user pressed Ctrl/Cmd + Enter, treat it as a shortcut (e.g., Run)
                // and DO NOT insert a newline here. Let the host handler act.
                // Also prevent default so the textarea won't add a line.
                if ((ev.ctrlKey || ev.metaKey) && key == "Enter") {
                    ev.preventDefault()
                    return@onKeyDown
                }
                if (ev.ctrlKey || ev.metaKey) {
                    val offset = ta.selectionStart ?: 0
                    val text = ta.value
                    when {
                        (key == " " || keyLower == "space" || keyLower == "spacebar") && onCompletionRequested != null -> {
                            ev.preventDefault()
                            scope.launch {
                                val analysis = ensureAnalysis(text)
                                val items = LyngWebTools.completions(text, offset, analysis)
                                onCompletionRequested(offset, items)
                            }
                            return@onKeyDown
                        }
                        keyLower == "b" && onDefinitionResolved != null -> {
                            ev.preventDefault()
                            scope.launch {
                                val analysis = ensureAnalysis(text)
                                val target = LyngWebTools.definitionAt(analysis, offset)
                                onDefinitionResolved(offset, target)
                            }
                            return@onKeyDown
                        }
                        ev.shiftKey && keyLower == "u" && onUsagesResolved != null -> {
                            ev.preventDefault()
                            scope.launch {
                                val analysis = ensureAnalysis(text)
                                val ranges = LyngWebTools.usagesAt(analysis, offset, includeDeclaration = false)
                                onUsagesResolved(offset, ranges)
                            }
                            return@onKeyDown
                        }
                        keyLower == "q" && onDocRequested != null -> {
                            ev.preventDefault()
                            scope.launch {
                                val analysis = ensureAnalysis(text)
                                val info = LyngWebTools.docAt(analysis, offset)
                                onDocRequested(offset, info)
                            }
                            return@onKeyDown
                        }
                    }
                }
                if (key == "Tab" && ev.shiftKey) {
                    // Shift+Tab: outdent current line(s)
                    ev.preventDefault()
                    val current = ta.value
                    val selStart = ta.selectionStart ?: 0
                    val selEnd = ta.selectionEnd ?: selStart
                    val res = applyShiftTab(current, selStart, selEnd, tabSize)
                    setCode(res.text)
                    pendingSelStart = res.selStart
                    pendingSelEnd = res.selEnd
                } else if (key == "Tab") {
                    ev.preventDefault()
                    val start = ta.selectionStart ?: 0
                    val end = ta.selectionEnd ?: start
                    val current = ta.value
                    val res = applyTab(current, start, end, tabSize)
                    // Update code first
                    setCode(res.text)
                    // Apply selection synchronously to avoid race with next key events
                    try { ta.setSelectionRange(res.selStart, res.selEnd) } catch (_: Throwable) {}
                    // Keep pending selection as a fallback for compose recompose
                    pendingSelStart = res.selStart
                    pendingSelEnd = res.selEnd
                } else if (key == "Enter") {
                    // Smart indent / outdent around braces
                    ev.preventDefault()
                    val start = ta.selectionStart ?: 0
                    val endSel = ta.selectionEnd ?: start
                    val cur = ta.value
                    val res = applyEnter(cur, start, endSel, tabSize)
                    setCode(res.text)
                    // Apply selection synchronously to ensure caret is where logic expects
                    try { ta.setSelectionRange(res.selStart, res.selEnd) } catch (_: Throwable) {}
                    pendingSelStart = res.selStart
                    pendingSelEnd = res.selEnd
                } else if (key.length == 1 && !ev.ctrlKey && !ev.metaKey && !ev.altKey) {
                    // Handle single character input (like '}') for dedenting
                    // This is an alternative to onInput to have better control
                    val start = ta.selectionStart ?: 0
                    val end = ta.selectionEnd ?: start
                    val current = ta.value
                    val res = applyChar(current, start, end, key[0], tabSize)
                    if (res.text != (current.substring(0, start) + key + current.substring(end))) {
                        // Logic decided to change something else (e.g. dedent)
                        ev.preventDefault()
                        setCode(res.text)
                        try { ta.setSelectionRange(res.selStart, res.selEnd) } catch (_: Throwable) {}
                        pendingSelStart = res.selStart
                        pendingSelEnd = res.selEnd
                    }
                }
            }

            onKeyUp { _ ->
                updateCaretTooltip()
            }

            onMouseUp { _ ->
                updateCaretTooltip()
            }

            onMouseMove { ev ->
                updateHoverTooltip(ev.clientX.toDouble(), ev.clientY.toDouble())
            }

            onMouseLeave { _ ->
                tooltipText = null
            }

            onScroll { ev ->
                val src = ev.target as? HTMLTextAreaElement ?: return@onScroll
                overlayEl?.scrollTop = src.scrollTop
                overlayEl?.scrollLeft = src.scrollLeft
                diagOverlayEl?.scrollTop = src.scrollTop
                diagOverlayEl?.scrollLeft = src.scrollLeft
            }
        })

        if (tooltipText != null && tooltipX != null && tooltipY != null) {
            org.jetbrains.compose.web.dom.Div({
                attr(
                    "style",
                    buildString {
                        append("position:absolute; z-index:3; pointer-events:none;")
                        append("left:").append(tooltipX).append("px; top:").append(tooltipY).append("px;")
                        append("background:#212529; color:#f8f9fa; padding:4px 6px; border-radius:4px;")
                        append("font-size:12px; line-height:1.3; max-width:360px; white-space:pre-wrap;")
                        append("box-shadow:0 4px 10px rgba(0,0,0,.15);")
                    }
                )
            }) {
                org.jetbrains.compose.web.dom.Text(tooltipText!!)
            }
        }

        // No built-in action buttons: EditorWithOverlay is a pure editor now
    }

    // Ensure overlay typography and paddings mirror the textarea so characters line up 1:1
    LaunchedEffect(taEl, overlayEl, diagOverlayEl) {
        try {
            val ta = taEl ?: return@LaunchedEffect
            val ov = overlayEl ?: return@LaunchedEffect
            val diag = diagOverlayEl
            val cs = window.getComputedStyle(ta)

            // Best-effort concrete line-height
            val lineHeight = cs.lineHeight.takeIf { it.endsWith("px") } ?: cs.fontSize

            val style = buildString {
                append("position:absolute; inset:0; overflow:auto; pointer-events:none; box-sizing:border-box;")
                append(" white-space:pre-wrap; word-break:break-word; tab-size:")
                append(tabSize)
                append(";")
                append("font-family:").append(cs.fontFamily).append(';')
                append("font-size:").append(cs.fontSize).append(';')
                if (!lineHeight.isNullOrBlank()) append("line-height:").append(lineHeight).append(';')
                append("letter-spacing:").append(cs.letterSpacing).append(';')
                // keep visual rendering close to textarea
                append("font-variant-ligatures:none; -webkit-font-smoothing:antialiased; text-rendering:optimizeSpeed;")
                // mirror paddings
                append("padding-top:").append(cs.paddingTop).append(';')
                append("padding-right:").append(cs.paddingRight).append(';')
                append("padding-bottom:").append(cs.paddingBottom).append(';')
                append("padding-left:").append(cs.paddingLeft).append(';')
                // base color in case we render plain text fallback
                append("color: var(--bs-body-color);")
            }
            ov.setAttribute("style", style)
            diag?.setAttribute("style", style + "color:transparent;")
            // also enforce concrete line-height on textarea to stabilize caret metrics
            val existing = ta.getAttribute("style") ?: ""
            if (!existing.contains("line-height") && !lineHeight.isNullOrBlank()) {
                ta.setAttribute("style", existing + " line-height: " + lineHeight + ";")
            }
        } catch (_: Throwable) {
        }
    }

    // Apply pending selection when value updates
    LaunchedEffect(code, pendingSelStart, pendingSelEnd) {
        val s = pendingSelStart
        val e = pendingSelEnd
        if (s != null && e != null) {
            pendingSelStart = null
            pendingSelEnd = null
            window.setTimeout({ setSelection(s, e) }, 0)
        }
    }
}
