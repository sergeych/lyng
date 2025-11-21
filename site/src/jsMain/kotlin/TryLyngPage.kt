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

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import net.sergeych.lyng.Scope
import net.sergeych.site.SiteHighlight
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement

@Composable
fun TryLyngPage() {
    val scope = rememberCoroutineScope()
    var code by remember {
        mutableStateOf(
            """
            // Welcome to Lyng! Edit and run.
            // Try changing the data and press Ctrl+Enter or click Run.
            import lyng.stdlib

            val data = 1..5
            val evens = data.filter { it % 2 == 0 }.map { it * it }
            evens
            """.trimIndent()
        )
    }
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runCode() {
        if (running) return
        running = true
        output = null
        error = null
        scope.launch {
            // keep this outside try so we can show partial prints if evaluation fails
            val printed = StringBuilder()
            try {
                // Create a fresh module scope each run so imports and vars are clean
                val s = Scope.new()
                // Capture printed output from Lyng `print`/`println` into the UI result window
                s.addVoidFn("print") {
                    for ((i, a) in args.withIndex()) {
                        if (i > 0) printed.append(' ')
                        printed.append(a.toString(this).value)
                    }
                }
                s.addVoidFn("println") {
                    for ((i, a) in args.withIndex()) {
                        if (i > 0) printed.append(' ')
                        printed.append(a.toString(this).value)
                    }
                    printed.append('\n')
                }
                val result = s.eval(code)
                // Render with inspect for nice, user-facing representation
                val text = try {
                    result.inspect(s)
                } catch (_: Throwable) {
                    // Fallback if some object lacks inspect override
                    result.toString()
                }
                val combined = buildString {
                    if (printed.isNotEmpty()) append(printed)
                    // Always show the final expression value, like a REPL
                    if (isNotEmpty()) append('\n')
                    append(">>> ")
                    append(text)
                }
                output = combined
            } catch (t: Throwable) {
                // Show error, but also keep anything that has been printed so far
                // Prefer detailed message including stack if available (K/JS)
                val errText = buildString {
                    append(t.toString())
                    try {
                        val st = t.asDynamic().stack as? String
                        if (!st.isNullOrBlank()) {
                            append("\n")
                            append(st)
                        }
                    } catch (_: Throwable) {}
                }
                if (printed.isNotEmpty()) {
                    output = printed.toString()
                }
                error = errText
            } finally {
                running = false
            }
        }
    }

    fun resetCode() {
        code = """
            // Welcome to Lyng! Edit and run.
            import lyng.stdlib
            [1,2,3].map { it * 10 }
        """.trimIndent()
        output = null
        error = null
    }

    PageTemplate(title = "Try Lyng", showBack = true) {
        // Intro
        P({ classes("lead", "text-muted", "mb-3") }) {
            Text("Type or paste Lyng code and run it right in your browser with embedded Lyng interpreter")
        }

        // Editor
        Div({ classes("mb-3") }) {
            Div({ classes("form-label", "fw-semibold") }) { Text("Code") }
            EditorWithOverlay(
                code = code,
                setCode = { code = it },
                onRun = { runCode() }
            )
        }

        // Actions
        Div({ classes("d-flex", "gap-2", "mb-3") }) {
            Button(attrs = {
                classes("btn", "btn-primary")
                if (running) attr("disabled", "disabled")
                onClick {
                    it.preventDefault()
                    runCode()
                }
            }) {
                I({ classes("bi", "bi-play-fill", "me-1") })
                Text(if (running) "Running…" else "Run")
            }

            Button(attrs = {
                classes("btn", "btn-outline-secondary")
                if (running) attr("disabled", "disabled")
                onClick {
                    it.preventDefault()
                    resetCode()
                }
            }) {
                I({ classes("bi", "bi-arrow-counterclockwise", "me-1") })
                Text("Reset")
            }
        }

        // Results
        if (error != null) {
            Div({ classes("alert", "alert-danger") }) {
                I({ classes("bi", "bi-exclamation-triangle-fill", "me-2") })
                Span({ classes("fw-semibold", "me-1") }) { Text("Error:") }
                // Show actual error text (previously printed the literal template)
                Span { Text(error!!) }
            }
        }

        if (output != null || error != null) {
            Div({ classes("card", "mb-3") }) {
                Div({ classes("card-header", "d-flex", "align-items-center", "gap-2") }) {
                    I({ classes("bi", "bi-terminal") })
                    Span({ classes("fw-semibold") }) { Text("Result") }
                }
                Div({ classes("card-body", "bg-body-tertiary") }) {
                    if (output != null) {
                        Pre({ classes("mb-0") }) { Code { Text(output!!) } }
                    }
                    if (error != null) {
                        if (output != null) Hr({})
                        Div({ classes("alert", "alert-danger", "mb-0") }) {
                            Pre({ classes("mb-0") }) { Code { Text(error!!) } }
                        }
                    }
                }
            }
        }

        // Tips
        P({ classes("text-muted", "small") }) {
            I({ classes("bi", "bi-info-circle", "me-1") })
            Text("Tip: press Ctrl+Enter (or ⌘+Enter on Mac) to run.")
        }
    }
}

@Composable
private fun EditorWithOverlay(
    code: String,
    setCode: (String) -> Unit,
    onRun: () -> Unit,
    tabSize: Int = 4,
) {
    var overlayEl by remember { mutableStateOf<HTMLElement?>(null) }
    var taEl by remember { mutableStateOf<HTMLTextAreaElement?>(null) }
    var lastGoodHtml by remember { mutableStateOf<String?>(null) }
    var lastGoodText by remember { mutableStateOf<String?>(null) }
    var pendingSelStart by remember { mutableStateOf<Int?>(null) }
    var pendingSelEnd by remember { mutableStateOf<Int?>(null) }
    var pendingScrollTop by remember { mutableStateOf<Double?>(null) }
    var pendingScrollLeft by remember { mutableStateOf<Double?>(null) }

    // Update overlay HTML whenever code changes
    LaunchedEffect(code) {
        // Insert highlighted spans directly without <pre><code> wrappers to avoid
        // external CSS (e.g., docs markdown styles) altering font-size/line-height
        // and causing caret drift.
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

        fun trimHtmlToTextPrefix(html: String, prefixChars: Int): String {
            if (prefixChars <= 0) return ""
            var i = 0
            var textCount = 0
            val n = html.length
            val out = StringBuilder(prefixChars + 64)
            // Track open span tags to close them at the end
            val stack = mutableListOf<String>() // holds closing tags like "</span>"
            while (i < n && textCount < prefixChars) {
                val ch = html[i]
                if (ch == '<') {
                    // Copy the whole tag
                    val close = html.indexOf('>', i)
                    if (close == -1) break
                    val tag = html.substring(i, close + 1)
                    out.append(tag)
                    // Track span openings/closings
                    val tagLower = tag.lowercase()
                    if (tagLower.startsWith("<span")) {
                        stack.add("</span>")
                    } else if (tagLower.startsWith("</span")) {
                        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    }
                    i = close + 1
                } else if (ch == '&') {
                    // entity counts as one text char
                    val semi = html.indexOf(';', i + 1).let { if (it == -1) n - 1 else it }
                    val entity = html.substring(i, semi + 1)
                    out.append(entity)
                    textCount += 1
                    i = semi + 1
                } else {
                    out.append(ch)
                    textCount += 1
                    i += 1
                }
            }
            // Close any open spans
            for (j in stack.size - 1 downTo 0) out.append(stack[j])
            return out.toString()
        }

        fun appendSentinel(html: String): String =
            // Append a zero-width space sentinel to keep the last line box from collapsing
            // in some browsers, which can otherwise cause caret size/position glitches
            // when the caret is at end-of-line.
            html + "<span data-sentinel=\"1\">&#8203;</span>"

        try {
            val html = SiteHighlight.renderHtml(code)
            overlayEl?.innerHTML = appendSentinel(html)
            lastGoodHtml = html
            lastGoodText = code
        } catch (_: Throwable) {
            // Highlighter failed (e.g., user is typing an unterminated string).
            // Preserve the last good highlighting for the common prefix, and render the rest as neutral text.
            val prevHtml = lastGoodHtml
            val prevText = lastGoodText
            if (prevHtml != null && prevText != null) {
                // Find common prefix length in characters between prevText and current code
                val max = minOf(prevText.length, code.length)
                var k = 0
                while (k < max && prevText[k] == code[k]) k++
                val prefixLen = k
                val trimmed = trimHtmlToTextPrefix(prevHtml, prefixLen)
                val tail = code.substring(prefixLen)
                val combined = trimmed + htmlEscape(tail)
                overlayEl?.innerHTML = appendSentinel(combined)
                // Do NOT update lastGoodHtml/Text here; wait for the next successful highlight
            } else {
                // No previous highlight available; show plain neutral text so it stays visible
                overlayEl?.innerHTML = appendSentinel(htmlEscape(code))
            }
        }

        // keep overlay scroll aligned with textarea after re-render
        val st = pendingScrollTop ?: (taEl?.scrollTop ?: 0.0)
        val sl = pendingScrollLeft ?: (taEl?.scrollLeft ?: 0.0)
        overlayEl?.scrollTop = st
        overlayEl?.scrollLeft = sl

        // If we have a pending selection update (from a key handler), apply it after the
        // value has been reconciled in the DOM. Double rAF to ensure paint is ready.
        val ps = pendingSelStart
        val pe = pendingSelEnd
        if (ps != null && pe != null) {
            window.requestAnimationFrame {
                window.requestAnimationFrame {
                    val ta = taEl
                    if (ta != null) {
                        val s = ps.coerceIn(0, ta.value.length)
                        val e = pe.coerceIn(0, ta.value.length)
                        ta.selectionStart = s
                        ta.selectionEnd = e
                        // restore scroll as well
                        if (pendingScrollTop != null) ta.scrollTop = pendingScrollTop!!
                        if (pendingScrollLeft != null) ta.scrollLeft = pendingScrollLeft!!
                    }
                    pendingSelStart = null
                    pendingSelEnd = null
                    pendingScrollTop = null
                    pendingScrollLeft = null
                }
            }
        }
    }

    // helper: set caret/selection safely
    fun setSelection(start: Int, end: Int = start) {
        val ta = taEl ?: return
        val s = start.coerceIn(0, (ta.value.length))
        val e = end.coerceIn(0, (ta.value.length))
        // Defer to next animation frame to avoid Compose re-render race
        window.requestAnimationFrame {
            ta.selectionStart = s
            ta.selectionEnd = e
        }
    }

    // Ensure overlay typography matches textarea to avoid shifted copies
    LaunchedEffect(taEl, overlayEl) {
        try {
            val ta = taEl ?: return@LaunchedEffect
            val ov = overlayEl ?: return@LaunchedEffect
            val cs = window.getComputedStyle(ta)
            // Resolve a concrete pixel line-height; some browsers return "normal" or unitless
            fun ensurePxLineHeight(): String {
                val lh = cs.lineHeight ?: ""
                if (lh.endsWith("px")) return lh
                // Measure using an off-screen probe with identical typography
                val doc = ta.ownerDocument
                val probe = doc?.createElement("span")?.unsafeCast<HTMLElement>()
                if (probe != null) {
                    probe.textContent = "M"
                    val fw = try {
                        (cs.asDynamic().fontWeight as? String) ?: cs.getPropertyValue("font-weight")
                    } catch (_: Throwable) { null }
                    val fs = try {
                        (cs.asDynamic().fontStyle as? String) ?: cs.getPropertyValue("font-style")
                    } catch (_: Throwable) { null }
                    probe.setAttribute(
                        "style",
                        buildString {
                            append("position:absolute; visibility:hidden; white-space:nowrap;")
                            append(" font-family:").append(cs.fontFamily).append(';')
                            append(" font-size:").append(cs.fontSize).append(';')
                            if (!fw.isNullOrBlank()) append(" font-weight:").append(fw).append(';')
                            if (!fs.isNullOrBlank()) append(" font-style:").append(fs).append(';')
                            append(" line-height: normal;")
                        }
                    )
                    doc.body?.appendChild(probe)
                    val h = probe.getBoundingClientRect().height
                    doc.body?.removeChild(probe)
                    if (h > 0) return "${'$'}hpx"
                }
                // Fallback heuristic: 1.2 * font-size
                val fsPx = cs.fontSize.takeIf { it.endsWith("px") }?.removeSuffix("px")?.toDoubleOrNull()
                val approx = if (fsPx != null) fsPx * 1.2 else 16.0 * 1.2
                return "${'$'}{approx}px"
            }
            val lineHeightPx = ensurePxLineHeight()
            // copy key properties
            val style = buildString {
                append("position:absolute; inset:0; overflow:auto; pointer-events:none;")
                append(" box-sizing:border-box; white-space: pre-wrap; word-wrap: break-word; tab-size:")
                append(tabSize)
                append(";")
                // Typography
                append("font-family:").append(cs.fontFamily).append(";")
                append("font-size:").append(cs.fontSize).append(";")
                append("line-height:").append(lineHeightPx).append(";")
                append("letter-spacing:").append(cs.letterSpacing).append(";")
                // Try to mirror weight and style to eliminate metric differences
                val fw = try {
                    (cs.asDynamic().fontWeight as? String) ?: cs.getPropertyValue("font-weight")
                } catch (_: Throwable) { null }
                if (!fw.isNullOrBlank()) append("font-weight:").append(fw).append(";")
                val fs = try {
                    (cs.asDynamic().fontStyle as? String) ?: cs.getPropertyValue("font-style")
                } catch (_: Throwable) { null }
                if (!fs.isNullOrBlank()) append("font-style:").append(fs).append(";")
                // Disable ligatures in overlay to keep glyph advances identical to textarea
                append("font-variant-ligatures:none;")
                append("-webkit-font-smoothing:antialiased;")
                append("text-rendering:optimizeSpeed;")
                // Ensure overlay text is visible even when we render plain text (fallback)
                append("color: var(--bs-body-color);")
                // Padding to match form-control
                append("padding-top:").append(cs.paddingTop).append(";")
                append("padding-right:").append(cs.paddingRight).append(";")
                append("padding-bottom:").append(cs.paddingBottom).append(";")
                append("padding-left:").append(cs.paddingLeft).append(";")
            }
            ov.setAttribute("style", style)
            // Also enforce the same concrete line-height on the textarea to keep caret metrics stable
            try {
                val existing = ta.getAttribute("style") ?: ""
                if (!existing.contains("line-height")) {
                    ta.setAttribute("style", existing + " line-height: " + lineHeightPx + ";")
                }
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    // container
    Div({
        attr("style", "position: relative;")
    }) {
        // Highlight overlay below textarea
        Div({
            classes("font-monospace")
            // Basic defaults; refined with computed textarea styles in LaunchedEffect above
            attr(
                "style",
                buildString {
                    append("position:absolute; inset:0; overflow:auto; pointer-events:none; box-sizing:border-box;")
                    append(" white-space: pre-wrap; word-wrap: break-word; tab-size:")
                    append(tabSize)
                    append("; margin:0; font-variant-ligatures:none;")
                }
            )
            ref {
                overlayEl = it
                onDispose { if (overlayEl === it) overlayEl = null }
            }
        }) {}

        // Textarea on top
        TextArea(value = code, attrs = {
            classes("form-control", "font-monospace")
            attr(
                "style",
                // Make text transparent to avoid double rendering, keep caret visible
                "min-height: 220px; background: transparent; position: relative; z-index: 1; tab-size:${tabSize}; color: transparent; -webkit-text-fill-color: transparent; caret-color: var(--bs-body-color); font-variant-ligatures: none;"
            )
            // Turn off spellcheck and auto-correct features
            attr("spellcheck", "false")
            attr("autocorrect", "off")
            attr("autocapitalize", "off")
            attr("autocomplete", "off")
            placeholder("Write some Lyng code…")
            ref {
                taEl = it
                onDispose { if (taEl === it) taEl = null }
            }
            onMouseDown {
                // Clear any pending programmatic selection; rely on the browser's placement
                pendingSelStart = null
                pendingSelEnd = null
                pendingScrollTop = null
                pendingScrollLeft = null
            }
            onClick {
                // Keep overlay scroll in sync after pointer placement
                val st = taEl?.scrollTop ?: 0.0
                val sl = taEl?.scrollLeft ?: 0.0
                overlayEl?.scrollTop = st
                overlayEl?.scrollLeft = sl
            }
            onScroll {
                // mirror scroll positions
                val st = taEl?.scrollTop ?: 0.0
                val sl = taEl?.scrollLeft ?: 0.0
                overlayEl?.scrollTop = st
                overlayEl?.scrollLeft = sl
            }
            onInput { ev -> setCode(ev.value) }
            onKeyDown { ev ->
                val ctrlEnter = (ev.ctrlKey || ev.metaKey) && ev.key == "Enter"
                if (ctrlEnter) {
                    ev.preventDefault()
                    onRun()
                    return@onKeyDown
                }

                val ta = ev.target.unsafeCast<HTMLTextAreaElement>()
                val start = ta.selectionStart ?: 0
                val end = ta.selectionEnd ?: 0
                val savedScrollTop = ta.scrollTop
                val savedScrollLeft = ta.scrollLeft

                fun currentLineStartIndex(text: String, i: Int): Int {
                    val nl = text.lastIndexOf('\n', startIndex = (i - 1).coerceAtLeast(0))
                    return if (nl == -1) 0 else nl + 1
                }

                when (ev.key) {
                    "Tab" -> {
                        ev.preventDefault()
                        // Shift+Tab -> outdent
                        if (ev.shiftKey) {
                            val text = code
                            val regionStart = currentLineStartIndex(text, start)
                            val regionEnd = if (start == end) {
                                text.indexOf('\n', startIndex = start).let { if (it == -1) text.length else it }
                            } else {
                                text.indexOf('\n', startIndex = end).let { if (it == -1) text.length else it }
                            }
                            val region = text.substring(regionStart, regionEnd)
                            val lines = region.split("\n")
                            var removedFirst = 0
                            var totalRemoved = 0
                            val outdented = lines.mapIndexed { idx, line ->
                                val toRemove = when {
                                    line.startsWith("\t") -> 1
                                    else -> line.take(tabSize).takeWhile { it == ' ' }.length
                                }
                                if (idx == 0) removedFirst = toRemove
                                totalRemoved += toRemove
                                line.drop(toRemove)
                            }.joinToString("\n")
                            val newCode = text.substring(0, regionStart) + outdented + text.substring(regionEnd)
                            val newStart = (start - removedFirst).coerceAtLeast(regionStart)
                            val newEnd = (end - totalRemoved).coerceAtLeast(newStart)
                            pendingSelStart = newStart
                            pendingSelEnd = newEnd
                            pendingScrollTop = savedScrollTop
                            pendingScrollLeft = savedScrollLeft
                            setCode(newCode)
                        } else {
                            val before = code.substring(0, start)
                            val after = code.substring(end)
                            if (start != end) {
                                // Indent selected lines
                                val regionStart = currentLineStartIndex(code, start)
                                val regionEnd = code.indexOf('\n', startIndex = end).let { if (it == -1) code.length else it }
                                val region = code.substring(regionStart, regionEnd)
                                val lines = region.split("\n")
                                val indentStr = " ".repeat(tabSize)
                                val indented = lines.joinToString("\n") { line -> indentStr + line }
                                val newCode = code.substring(0, regionStart) + indented + code.substring(regionEnd)
                                val delta = tabSize * lines.size
                                val newStart = start + tabSize
                                val newEnd = end + delta
                                pendingSelStart = newStart
                                pendingSelEnd = newEnd
                                pendingScrollTop = savedScrollTop
                                pendingScrollLeft = savedScrollLeft
                                setCode(newCode)
                            } else {
                                // Insert spaces to next tab stop
                                val col = run {
                                    val lastNl = before.lastIndexOf('\n')
                                    val lineStart = if (lastNl == -1) 0 else lastNl + 1
                                    start - lineStart
                                }
                                val toAdd = tabSize - (col % tabSize)
                                val spaces = " ".repeat(toAdd)
                                val newCode = before + spaces + after
                                val newPos = start + spaces.length
                                pendingSelStart = newPos
                                pendingSelEnd = newPos
                                pendingScrollTop = savedScrollTop
                                pendingScrollLeft = savedScrollLeft
                                setCode(newCode)
                            }
                        }
                    }
                    "Enter" -> {
                        ev.preventDefault()
                        val before = code.substring(0, start)
                        val after = code.substring(end)
                        val lineStart = currentLineStartIndex(code, start)
                        val currentLine = code.substring(lineStart, start)
                        val indent = currentLine.takeWhile { it == ' ' || it == '\t' }
                        // simple brace-aware heuristic: add extra indent if previous non-space ends with '{'
                        val trimmed = currentLine.trimEnd()
                        val extra = if (trimmed.endsWith("{")) " ".repeat(tabSize) else ""
                        val insertion = "\n" + indent + extra
                        val newCode = before + insertion + after
                        val newPos = start + insertion.length
                        pendingSelStart = newPos
                        pendingSelEnd = newPos
                        pendingScrollTop = savedScrollTop
                        pendingScrollLeft = savedScrollLeft
                        setCode(newCode)
                    }
                    "}" -> {
                        // If the current line contains only indentation up to the caret,
                        // outdent by one indent level (tab or up to tabSize spaces) before inserting '}'.
                        val text = code
                        val lineStart = currentLineStartIndex(text, start)
                        val beforeCaret = text.substring(lineStart, start)
                        val onlyIndentBeforeCaret = beforeCaret.all { it == ' ' || it == '\t' }
                        if (onlyIndentBeforeCaret) {
                            ev.preventDefault()
                            val removeCount = when {
                                beforeCaret.endsWith("\t") -> 1
                                else -> beforeCaret.takeLast(tabSize).takeWhile { it == ' ' }.length
                            }
                            val newLinePrefix = if (removeCount > 0) beforeCaret.dropLast(removeCount) else beforeCaret
                            val after = code.substring(end)
                            val newCode = code.substring(0, lineStart) + newLinePrefix + "}" + after
                            val newPos = lineStart + newLinePrefix.length + 1
                            pendingSelStart = newPos
                            pendingSelEnd = newPos
                            pendingScrollTop = savedScrollTop
                            pendingScrollLeft = savedScrollLeft
                            setCode(newCode)
                        }
                    }
                }
            }
        })
    }
}
