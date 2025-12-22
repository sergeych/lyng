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
import kotlinx.coroutines.await
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLHeadingElement

@Composable
fun DocsPage(
    route: String,
    html: String?,
    error: String?,
    contentEl: HTMLElement?,
    onContentEl: (HTMLElement?) -> Unit,
    setError: (String?) -> Unit,
    setHtml: (String?) -> Unit,
    toc: List<TocItem>,
    setToc: (List<TocItem>) -> Unit,
    activeTocId: String?,
    setActiveTocId: (String?) -> Unit,
) {
    var title by remember { mutableStateOf<String?>(null) }

    val docKey = stripFragment(route)
    LaunchedEffect(docKey) {
        setError(null)
        setHtml(null)
        setToc(emptyList())
        setActiveTocId(null)

        val path = routeToPath(route)
        try {
            val url = "./" + encodeURI(path)
            val resp = window.fetch(url).await()
            if (!resp.ok) {
                setError("Not found: $path (${resp.status})")
            } else {
                val text = resp.text().await()
                title = extractTitleFromMarkdown(text) ?: path.substringAfterLast('/')
                // Use AST-backed highlighting for code blocks
                setHtml(renderMarkdownAsync(text))
            }
        } catch (t: Throwable) {
            setError("Failed to load: $path — ${t.message}")
        }
    }

    PageTemplate(title = null, showBack = false) {
        if (error != null) {
            Div({ classes("alert", "alert-danger") }) { Text(error) }
        } else if (html == null) {
            P { Text("Loading…") }
        } else {
            Div({
                classes("markdown-body")
                ref {
                    onContentEl(it)
                    onDispose { onContentEl(null) }
                }
            }) {
                UnsafeRawHtml(html)
            }
        }
    }

    LaunchedEffect(html, contentEl) {
        val el = contentEl ?: return@LaunchedEffect
        if (html == null) return@LaunchedEffect
        window.requestAnimationFrame {
            try {
                val firstH1 = el.querySelector("h1") as? HTMLElement
                if (firstH1 != null && firstH1.querySelector(".doc-back-btn") == null) {
                    val back = el.ownerDocument!!.createElement("div") as HTMLDivElement
                    back.className = "btn btn-outline btn-sm me-2 align-middle doc-back-btn "
                    back.setAttribute("aria-label","Back")
                    back.onclick = { ev ->
                        ev.preventDefault()
                        try {
                            if (window.history.length > 1) window.history.back() else window.location.hash = "#"
                        } catch (e: dynamic) {
                            window.location.hash = "#"
                        }
                        null
                    }
                    val icon = el.ownerDocument!!.createElement("i") as HTMLElement
                    icon.className = "bi bi-arrow-left"
                    back.appendChild(icon)
                    firstH1.insertBefore(back, firstH1.firstChild)
                }
            } catch (_: Throwable) { }

            try {
                // Use the current document directory as base for relative links and images
                val currentDoc = routeToPath(route) // e.g. "docs/tutorial.md"
                val basePath = currentDoc.substringBeforeLast('/', missingDelimiterValue = "")
                rewriteImages(el, basePath = basePath)
                rewriteAnchors(
                    el,
                    basePath = basePath,
                    currentDocPath = currentDoc
                ) { newRoute ->
                    window.location.hash = "#/$newRoute"
                }
            } catch (_: Throwable) { }

            try {
                val tocItems = buildToc(el)
                setToc(tocItems)
            } catch (_: Throwable) { setToc(emptyList()) }

            try {
                val terms = extractSearchTerms(route)
                val hits = highlightSearchHits(el, terms)
                dlog("search", "highlighted $hits hits for terms=$terms")
            } catch (_: Throwable) { }

            // After highlighting, if navigated via search (?q=...) and there is no fragment in the route,
            // scroll to the first search hit accounting for sticky navbar offset. Do this only once per load.
            try {
                val hasQueryHits = extractSearchTerms(route).isNotEmpty()
                val hasFragment = route.contains('#')
                val alreadyScrolled = (el.getAttribute("data-search-scrolled") == "1")
                if (hasQueryHits && !hasFragment && !alreadyScrolled) {
                    val firstHit = el.querySelector(".search-hit") as? HTMLElement
                    if (firstHit != null) {
                        el.setAttribute("data-search-scrolled", "1")
                        // compute top with offset
                        val rectTop = firstHit.getBoundingClientRect().top + window.scrollY
                        val offset = (updateNavbarOffsetVar() + 16).toDouble()
                        val targetY = rectTop - offset
                        try {
                            window.scrollTo(js("({top: targetY, behavior: 'instant'})").unsafeCast<dynamic>())
                        } catch (_: dynamic) {
                            window.scrollTo(0.0, targetY)
                        }
                        dlog("scroll", "auto-scrolled to first hit at y=$targetY")
                    }
                }
            } catch (_: Throwable) { }

            // MathJax typeset is triggered similarly to original code in Main.kt
            try {
                val ready = try { js("typeof MathJax !== 'undefined' && MathJax.typeset") as Boolean } catch (_: dynamic) { false }
                if (ready) {
                    try { MathJax.typeset(arrayOf(el)) } catch (_: dynamic) { }
                } else {
                    window.setTimeout({
                        try { MathJax.typeset(arrayOf(el)) } catch (_: dynamic) { }
                    }, 50)
                }
            } catch (_: dynamic) { }
        }
    }

    DisposableEffect(toc, contentEl) {
        val el = contentEl ?: return@DisposableEffect onDispose {}
        dlog("toc", "have ${toc.size} items, recomputing active")

        var scheduled = false
        fun computeActive() {
            scheduled = false
            try {
                val heads = toc.mapNotNull { id -> el.querySelector("#${id.id}") as? HTMLHeadingElement }
                val tops = heads.map { it.getBoundingClientRect().top }
                val offset = (updateNavbarOffsetVar() + 16).toDouble()
                val idx = activeIndexForTops(tops, offset)
                setActiveTocId(if (idx in toc.indices) toc[idx].id else null)
            } catch (_: Throwable) { }
        }

        val scrollListener: (org.w3c.dom.events.Event) -> Unit = {
            if (!scheduled) {
                scheduled = true
                window.requestAnimationFrame { computeActive() }
            }
        }
        val resizeListener = scrollListener

        computeActive()
        window.addEventListener("scroll", scrollListener)
        window.addEventListener("resize", resizeListener)

        onDispose {
            window.removeEventListener("scroll", scrollListener)
            window.removeEventListener("resize", resizeListener)
        }
    }
}
