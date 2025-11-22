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

package net.sergeych.lyngweb

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.events.SyntheticKeyboardEvent
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
    // New sizing controls
    minRows: Int = 6,
    maxRows: Int? = null,
    autoGrow: Boolean = false,
) {
    var overlayEl by remember { mutableStateOf<HTMLElement?>(null) }
    var taEl by remember { mutableStateOf<HTMLTextAreaElement?>(null) }
    var lastGoodHtml by remember { mutableStateOf<String?>(null) }
    var lastGoodText by remember { mutableStateOf<String?>(null) }
    var pendingSelStart by remember { mutableStateOf<Int?>(null) }
    var pendingSelEnd by remember { mutableStateOf<Int?>(null) }
    var pendingScrollTop by remember { mutableStateOf<Double?>(null) }
    var pendingScrollLeft by remember { mutableStateOf<Double?>(null) }
    var cachedLineHeight by remember { mutableStateOf<Double?>(null) }
    var cachedVInsets by remember { mutableStateOf<Double?>(null) }

    fun ensureMetrics(ta: HTMLTextAreaElement) {
        if (cachedLineHeight == null || cachedVInsets == null) {
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

    // Update overlay HTML whenever code changes
    LaunchedEffect(code) {
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
                    val close = html.indexOf('>', i)
                    if (close == -1) break
                    val tag = html.substring(i, close + 1)
                    out.append(tag)
                    val tagLower = tag.lowercase()
                    if (tagLower.startsWith("<span")) {
                        stack.add("</span>")
                    } else if (tagLower.startsWith("</span")) {
                        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    }
                    i = close + 1
                } else if (ch == '&') {
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
            for (j in stack.size - 1 downTo 0) out.append(stack[j])
            return out.toString()
        }

        fun appendSentinel(html: String): String =
            html + "<span data-sentinel=\"1\">&#8203;</span>"

        try {
            val html = SiteHighlight.renderHtml(code)
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
        pendingScrollTop = null
        pendingScrollLeft = null
        // If text changed and autoGrow enabled, adjust height
        adjustTextareaHeight()
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
            }

            onKeyDown { ev ->
                // bubble to caller first so they may intercept shortcuts
                onKeyDown?.invoke(ev)
                val ta = taEl ?: return@onKeyDown
                val key = ev.key
                if (key == "Tab") {
                    ev.preventDefault()
                    val start = ta.selectionStart ?: 0
                    val end = ta.selectionEnd ?: start
                    val current = ta.value
                    val before = current.substring(0, start)
                    val after = current.substring(end)
                    val spaces = " ".repeat(tabSize)
                    val updated = before + spaces + after
                    pendingSelStart = start + spaces.length
                    pendingSelEnd = pendingSelStart
                    setCode(updated)
                } else if (key == "Enter") {
                    // Smart indent: copy leading spaces from current line
                    val start = ta.selectionStart ?: 0
                    val cur = ta.value
                    val lineStart = run {
                        var i = start - 1
                        while (i >= 0 && cur[i] != '\n') i--
                        i + 1
                    }
                    var indent = 0
                    while (lineStart + indent < cur.length && cur[lineStart + indent] == ' ') indent++
                    val before = cur.substring(0, start)
                    val after = cur.substring(start)
                    val insertion = "\n" + " ".repeat(indent)
                    pendingSelStart = start + insertion.length
                    pendingSelEnd = pendingSelStart
                    setCode(before + insertion + after)
                    ev.preventDefault()
                }
            }

            onScroll { ev ->
                val src = ev.target as? HTMLTextAreaElement ?: return@onScroll
                overlayEl?.scrollTop = src.scrollTop
                overlayEl?.scrollLeft = src.scrollLeft
            }
        })

        // No built-in action buttons: EditorWithOverlay is a pure editor now
    }

    // Ensure overlay typography and paddings mirror the textarea so characters line up 1:1
    LaunchedEffect(taEl, overlayEl) {
        try {
            val ta = taEl ?: return@LaunchedEffect
            val ov = overlayEl ?: return@LaunchedEffect
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
