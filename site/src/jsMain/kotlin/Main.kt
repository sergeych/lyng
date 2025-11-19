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
import externals.marked
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLHeadingElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLLinkElement

data class TocItem(val level: Int, val id: String, val title: String)

// MathJax v3 global API (loaded via CDN in index.html)
external object MathJax {
    fun typesetPromise(elements: Array<dynamic> = definedExternally): dynamic
    fun typeset(elements: Array<dynamic> = definedExternally)
}

@Composable
fun App() {
    var route by remember { mutableStateOf(currentRoute()) }
    var html by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var toc by remember { mutableStateOf<List<TocItem>>(emptyList()) }
    var activeTocId by remember { mutableStateOf<String?>(null) }
    var contentEl by remember { mutableStateOf<HTMLElement?>(null) }
    val isDocsRoute = route.startsWith("docs/")
    // A stable key for the current document path (without fragment). Used by DocsPage.
    val docKey = stripFragment(route)

    // Initialize dynamic Documentation dropdown once
    LaunchedEffect(Unit) {
        initDocsDropdown()
    }

    // Listen to hash changes (routing)
    DisposableEffect(Unit) {
        val listener: (org.w3c.dom.events.Event) -> Unit = {
            route = currentRoute()
        }
        window.addEventListener("hashchange", listener)
        onDispose { window.removeEventListener("hashchange", listener) }
    }

    // Docs-specific fetching and effects are handled inside DocsPage now

    // Layout
    PageTemplate(title = when {
        isDocsRoute -> null // title will be shown inside DocsPage from MD H1
        route.startsWith("reference") -> "Reference"
        route.isBlank() -> null // Home has its own big title/hero
        else -> null
    }) {
        Div({ classes("row", "gy-4") }) {
            // Sidebar TOC: show only on docs pages
            if (isDocsRoute) {
                Div({ classes("col-12", "col-lg-3") }) {
                    // Keep the TOC nav sticky below the fixed navbar. Use the same CSS var that
                    // drives body padding so the offsets always match the real navbar height.
                    Nav({
                        classes("position-sticky")
                        attr("style", "top: calc(var(--navbar-offset) + 1rem)")
                    }) {
                        H2({ classes("h6", "text-uppercase", "text-muted") }) { Text("On this page") }
                        Ul({ classes("list-unstyled") }) {
                            toc.forEach { item ->
                                Li({ classes("mb-1") }) {
                                    val pad = when (item.level) {
                                        1 -> "0"
                                        2 -> "0.75rem"
                                        else -> "1.5rem"
                                    }
                                    val routeNoFrag = route.substringBefore('#')
                                    val tocHref = "#/$routeNoFrag#${item.id}"
                                    A(attrs = {
                                        attr("href", tocHref)
                                        attr("style", "padding-left: $pad")
                                        classes("link-body-emphasis", "text-decoration-none")
                                        // Highlight active item
                                        if (activeTocId == item.id) {
                                            classes("fw-semibold", "text-primary")
                                            attr("aria-current", "true")
                                        }
                                        onClick {
                                            it.preventDefault()
                                            // Update location hash to include the document route and section id
                                            window.location.hash = tocHref
                                            // Perform immediate scroll for snappier UX (effects will also handle it)
                                            contentEl?.ownerDocument?.getElementById(item.id)
                                                ?.let { (it as? HTMLElement)?.scrollIntoView() }
                                        }
                                    }) { Text(item.title) }
                                }
                            }
                        }
                    }
                }
            }

            // Main content
            Div({ classes("col-12", if (isDocsRoute) "col-lg-9" else "col-lg-12") }) {
                when {
                    route.isBlank() -> HomePage()
                    !isDocsRoute -> ReferencePage()
                    else -> DocsPage(
                        route = route,
                        html = html,
                        error = error,
                        contentEl = contentEl,
                        onContentEl = { contentEl = it },
                        setError = { error = it },
                        setHtml = { html = it },
                        toc = toc,
                        setToc = { toc = it },
                        activeTocId = activeTocId,
                        setActiveTocId = { activeTocId = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun DocLink(name: String) {
    A(attrs = {
        classes("btn", "btn-sm", "btn-outline-secondary")
        onClick {
            window.location.hash = "#/docs/$name"
            it.preventDefault()
        }
        attr("href", "#/docs/$name")
    }) { Text(name) }
}

@Composable
private fun UnsafeRawHtml(html: String) {
    // Compose HTML lacks a direct element for raw insertion; set innerHTML via ref
    // Use a <div> and set its innerHTML via side effect
    val holder = remember { mutableStateOf<HTMLElement?>(null) }
    LaunchedEffect(html) {
        holder.value?.innerHTML = html
    }
    Div({
        ref {
            holder.value = it
            onDispose {
                if (holder.value === it) holder.value = null
            }
        }
    }) {}
}

fun currentRoute(): String = window.location.hash.removePrefix("#/")

fun routeToPath(route: String): String {
    val noFrag = stripFragment(route)
    return if (noFrag.startsWith("docs/")) noFrag else "docs/$noFrag"
}

// Strip trailing fragment from a route like "docs/file.md#anchor" -> "docs/file.md"
fun stripFragment(route: String): String = route.substringBefore('#')

fun renderMarkdown(src: String): String =
    highlightLyngHtml(
        ensureBootstrapCodeBlocks(
            ensureBootstrapTables(
                ensureDefinitionLists(
                    marked.parse(src)
                )
            )
        )
    )

// Pure function to render the Reference list HTML from a list of doc paths.
// Returns a Bootstrap-styled <ul> list with links to the docs routes.
fun renderReferenceListHtml(docs: List<String>): String {
    if (docs.isEmpty()) return "<p>No documents found.</p>"
    val items = docs.sorted().joinToString(separator = "") { path ->
        val name = path.substringAfterLast('/')
        val dir = path.substringBeforeLast('/', "")
        buildString {
            append("<li class=\"list-group-item d-flex justify-content-between align-items-center\">")
            append("<div>")
            append("<a href=\"#/$path\" class=\"link-body-emphasis text-decoration-none\">")
            append(name)
            append("</a>")
            if (dir.isNotEmpty()) {
                append("<br><small class=\"text-muted\">")
                append(dir)
                append("</small>")
            }
            append("</div>")
            append("<i class=\"bi bi-chevron-right\"></i>")
            append("</li>")
        }
    }
    return "<ul class=\"list-group\">$items</ul>"
}

// --------------- New Composables: PageTemplate and DocsPage ---------------

@Composable
private fun PageTemplate(title: String?, showBack: Boolean = false, content: @Composable () -> Unit) {
    Div({ classes("container", "py-4") }) {
        // Render header row only when we have a title, to avoid a floating back icon before data loads
        if (!title.isNullOrBlank()) {
            Div({ classes("d-flex", "align-items-center", "gap-2", "mb-3") }) {
                if (showBack) {
                    A(attrs = {
                        classes("btn", "btn-outline", "btn-sm")
                        attr("href", "#")
                        attr("aria-label", "Back")
                        onClick {
                            it.preventDefault()
                            try {
                                if (window.history.length > 1) window.history.back()
                                else window.location.hash = "#"
                            } catch (e: dynamic) {
                                window.location.hash = "#"
                            }
                        }
                    }) {
                        I({ classes("bi", "bi-arrow-left") })
                    }
                }
                if (!title.isNullOrBlank()) {
                    H1({ classes("h4", "mb-0") }) { Text(title) }
                }
            }
        }

        content()
    }
}

@Composable
private fun DocsPage(
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
    // Title is extracted from the first H1 in markdown
    var title by remember { mutableStateOf<String?>(null) }

    // Fetch markdown and compute title
    val docKey = stripFragment(route)
    LaunchedEffect(docKey) {
        // Reset page-specific state early to avoid stale UI (e.g., empty TOC persisting)
        setError(null)
        setHtml(null)
        setToc(emptyList())
        setActiveTocId(null)

        val path = routeToPath(route)
        try {
            val resp = window.fetch(path).await()
            if (!resp.ok) {
                setError("Not found: $path (${resp.status})")
            } else {
                val text = resp.text().await()
                title = extractTitleFromMarkdown(text) ?: path.substringAfterLast('/')
                setHtml(renderMarkdown(text))
            }
        } catch (t: Throwable) {
            setError("Failed to load: $path — ${t.message}")
        }
    }

    PageTemplate(title = title, showBack = true) {
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

    // Post-process links, images and build TOC after html injection
    // Run when both html is present and content element is mounted to avoid races (e.g., Home → Tutorial)
    LaunchedEffect(html, contentEl) {
        val el = contentEl ?: return@LaunchedEffect
        if (html == null) return@LaunchedEffect
        window.requestAnimationFrame {
            val currentPath = routeToPath(route) // without fragment
            val basePath = currentPath.substringBeforeLast('/', "docs")
            rewriteImages(el, basePath)
            rewriteAnchors(el, basePath, currentPath) { newRoute ->
                window.location.hash = "#/$newRoute"
            }
            // Render math using MathJax v3 when available; retry shortly if still initializing.
            val tryTypeset: () -> Unit = {
                try {
                    val ready = try { js("typeof MathJax !== 'undefined' && MathJax.typeset") as Boolean } catch (_: dynamic) { false }
                    if (ready) {
                        try { MathJax.typeset(arrayOf(el)) } catch (_: dynamic) { /* ignore */ }
                    } else {
                        // retry once after a short delay
                        window.setTimeout({
                            try { MathJax.typeset(arrayOf(el)) } catch (_: dynamic) { /* ignore */ }
                        }, 50)
                    }
                } catch (_: dynamic) { /* ignore */ }
            }
            tryTypeset()
            val newToc = buildToc(el)
            setToc(newToc)
            // Set initial active section: prefer fragment if present, else first heading
            val frag = anchorFromHash(window.location.hash)
            val initialId = frag ?: newToc.firstOrNull()?.id
            setActiveTocId(initialId)

            if (!frag.isNullOrBlank()) {
                val target = el.ownerDocument?.getElementById(frag)
                (target as? HTMLElement)?.scrollIntoView()
            }
        }
    }

    // When only the fragment changes on the same document, scroll to the target without re-fetching
    LaunchedEffect(route) {
        val el = contentEl ?: return@LaunchedEffect
        window.setTimeout({
            val frag = anchorFromHash(window.location.hash)
            if (!frag.isNullOrBlank()) {
                val target = el.ownerDocument?.getElementById(frag)
                (target as? HTMLElement)?.scrollIntoView()
            }
        }, 0)
    }

    // Scrollspy: highlight active heading in TOC while scrolling (reuse existing logic)
    DisposableEffect(toc, contentEl) {
        if (toc.isEmpty() || contentEl == null) return@DisposableEffect onDispose {}
        var scheduled = false
        fun computeActive() {
            scheduled = false
            val tops = toc.mapNotNull { item ->
                contentEl.ownerDocument?.getElementById(item.id)
                    ?.let { (it as? HTMLElement)?.getBoundingClientRect()?.top?.toDouble() }
            }
            if (tops.isEmpty()) return
            val idx = activeIndexForTops(tops, offsetPx = 80.0)
            val newId = toc.getOrNull(idx)?.id
            if (newId != null && newId != activeTocId) {
                setActiveTocId(newId)
            }
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

private fun extractTitleFromMarkdown(md: String): String? {
    val lines = md.lines()
    val h1 = lines.firstOrNull { it.trimStart().startsWith("# ") }
    return h1?.trimStart()?.removePrefix("# ")?.trim()
}

private suspend fun initDocsDropdown() {
    try {
        val menu = document.getElementById("docsDropdownMenu") ?: return
        // Fetch docs index
        val resp = window.fetch("docs-index.json").await()
        if (!resp.ok) return
        val text = resp.text().await()
        val arr = js("JSON.parse(text)") as Array<String>
        val all = arr.toList().sorted()
        // Filter excluded by reading each markdown and looking for the marker
        val filtered = mutableListOf<String>()
        for (path in all) {
            try {
                val r = window.fetch(path).await()
                if (!r.ok) continue
                val body = r.text().await()
                if (!body.contains("[//]: # (excludeFromIndex)")) {
                    filtered.add(path)
                }
            } catch (_: Throwable) {}
        }
        // Sort entries by display name (file name) case-insensitively
        val sortedFiltered = filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.substringAfterLast('/') })
        // Build items after the static first two existing children (tutorial + divider)
        sortedFiltered.forEach { path ->
            val name = path.substringAfterLast('/')
            val li = document.createElement("li")
            val a = document.createElement("a") as HTMLAnchorElement
            a.className = "dropdown-item"
            a.href = "#/$path"
            a.setAttribute("data-route", "docs")
            a.textContent = name
            // Ensure SPA navigation and close navbar collapse on small screens
            a.onclick = { ev ->
                ev.preventDefault()
                window.location.hash = "#/$path"
                closeNavbarCollapse()
            }
            li.appendChild(a)
            menu.appendChild(li)
        }
    } catch (_: Throwable) {
    }
}

private fun closeNavbarCollapse() {
    val collapse = document.getElementById("topbarNav") as? HTMLElement
    collapse?.classList?.remove("show")
    // update toggler aria-expanded if present
    val togglers = document.getElementsByClassName("navbar-toggler")
    if (togglers.length > 0) {
        val t = togglers.item(0) as? HTMLElement
        t?.setAttribute("aria-expanded", "false")
    }
}

@Composable
private fun ReferencePage() {
    var docs by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load docs index once
    LaunchedEffect(Unit) {
        try {
            val resp = window.fetch("docs-index.json").await()
            if (!resp.ok) {
                error = "Failed to load docs index (${resp.status})"
            } else {
                val text = resp.text().await()
                // Simple JSON parse into dynamic array of strings
                val arr = js("JSON.parse(text)") as Array<String>
                docs = arr.toList()
            }
        } catch (t: Throwable) {
            error = t.message
        }
    }

    H2({ classes("h5", "mb-3") }) { Text("Reference") }
    P({ classes("text-muted") }) { Text("Browse all documentation pages included in this build.") }

    when {
        error != null -> Div({ classes("alert", "alert-danger") }) { Text(error!!) }
        docs == null -> P { Text("Loading index…") }
        docs!!.isEmpty() -> P { Text("No documents found.") }
        else -> UnsafeRawHtml(renderReferenceListHtml(docs!!))
    }
}

@Composable
private fun HomePage() {
    // Hero section
    Section({ classes("py-4", "py-lg-5") }) {
        Div({ classes("text-center") }) {
            H1({ classes("display-5", "fw-bold", "mb-3") }) { Text("Welcome to Lyng") }
            P({ classes("lead", "text-muted", "mb-4") }) {
                Text("A lightweight, expressive scripting language designed for clarity, composability, and fun. ")
                Br()
                Text("Run it anywhere Kotlin runs — share logic across JS, JVM, and more.")
            }
            Div({ classes("d-flex", "justify-content-center", "gap-2", "flex-wrap", "mb-4") }) {
                // Benefits pills
                listOf(
                    "Clean, familiar syntax",
                    "Immutable-first collections",
                    "Batteries-included standard library",
                    "Embeddable and testable"
                ).forEach { b ->
                    Span({ classes("badge", "text-bg-secondary", "rounded-pill") }) { Text(b) }
                }
            }
            // CTA buttons
            Div({ classes("d-flex", "justify-content-center", "gap-2", "mb-4") }) {
                A(attrs = {
                    classes("btn", "btn-primary", "btn-lg")
                    attr("href", "#/docs/tutorial.md")
                }) {
                    I({ classes("bi", "bi-play-fill", "me-1") })
                    Text("Start the tutorial")
                }
                A(attrs = {
                    classes("btn", "btn-outline-secondary", "btn-lg")
                    attr("href", "#/reference")
                }) {
                    I({ classes("bi", "bi-journal-text", "me-1") })
                    Text("Browse reference")
                }
            }
        }
    }

    // Code sample
    val code = """
// Create, transform, and verify — the Lyng way
val data = [1, 2, 3, 4, 5]
val evens = data.filter { it % 2 == 0 }.map { it * it }
assertEquals([4, 16], evens)
>>> void
""".trimIndent()

    val codeHtml = "<pre><code>" + htmlEscape(code) + "</code></pre>"
    Div({ classes("markdown-body") }) {
        UnsafeRawHtml(highlightLyngHtml(ensureBootstrapCodeBlocks(codeHtml)))
    }

    // Short features list
    Div({ classes("row", "g-4", "mt-1") }) {
        listOf(
            Triple("Fast to learn", "Familiar constructs and readable patterns — be productive in minutes.", "bolt"),
            Triple("Portable", "Runs wherever Kotlin runs: reuse logic across platforms.", "globe2"),
            Triple("Pragmatic", "A standard library that solves real problems without ceremony.", "gear-fill")
        ).forEach { (title, text, icon) ->
            Div({ classes("col-12", "col-md-4") }) {
                Div({ classes("h-100", "p-3", "border", "rounded-3", "bg-body-tertiary") }) {
                    Div({ classes("d-flex", "align-items-center", "mb-2", "fs-4") }) {
                        I({ classes("bi", "bi-$icon", "me-2") })
                        Span({ classes("fw-semibold") }) { Text(title) }
                    }
                    P({ classes("mb-0", "text-muted") }) { Text(text) }
                }
            }
        }
    }
}

// ---- Theme handling: follow system theme automatically ----

private fun applyTheme(isDark: Boolean) {
    // Toggle Bootstrap theme attribute
    document.body?.setAttribute("data-bs-theme", if (isDark) "dark" else "light")
    // Toggle GitHub Markdown CSS light/dark
    val light = document.getElementById("md-light") as? HTMLLinkElement
    val dark = document.getElementById("md-dark") as? HTMLLinkElement
    if (isDark) {
        light?.setAttribute("disabled", "")
        dark?.removeAttribute("disabled")
    } else {
        dark?.setAttribute("disabled", "")
        light?.removeAttribute("disabled")
    }
}

private fun initAutoTheme() {
    val mql = try { window.matchMedia("(prefers-color-scheme: dark)") } catch (_: Throwable) { null }
    if (mql == null) {
        applyTheme(false)
        return
    }
    // Set initial
    applyTheme(mql.matches)
    // React to changes (modern browsers)
    try {
        mql.addEventListener("change", { ev ->
            val isDark = try { (ev.asDynamic().matches as Boolean) } catch (_: Throwable) { mql.matches }
            applyTheme(isDark)
        })
    } catch (_: Throwable) {
        // Legacy API fallback
        try {
            (mql.asDynamic()).addListener { mq: dynamic ->
                val isDark = try { mq.matches as Boolean } catch (_: Throwable) { false }
                applyTheme(isDark)
            }
        } catch (_: Throwable) {}
    }
}

// Convert pseudo Markdown definition lists rendered by marked as paragraphs into proper <dl><dt><dd> structures.
// Pattern supported (common in many MD flavors):
// Term\n
// : Definition paragraph 1\n
// : Definition paragraph 2 ...
// After marked parses it, it becomes:
// <p>Term</p>\n<p>: Definition paragraph 1</p>\n<p>: Definition paragraph 2</p>
// We transform such consecutive blocks into:
// <dl><dt>Term</dt><dd>Definition paragraph 1</dd><dd>Definition paragraph 2</dd></dl>
private fun ensureDefinitionLists(html: String): String {
    // We operate per <p> block, and if its inner HTML contains newline-separated lines
    // in the form:
    //   Term\n: Def1\n: Def2
    // we convert this <p> into a <dl>...</dl>
    val pBlock = Regex("""<p>([\s\S]*?)</p>""", setOf(RegexOption.IGNORE_CASE))

    return pBlock.replace(html) { match ->
        val inner = match.groupValues[1]
        val lines = inner.split(Regex("\r?\n"))
        if (lines.isEmpty()) return@replace match.value

        val term = lines.first().trim()
        if (term.startsWith(":")) return@replace match.value // cannot start with ':'

        val defs = lines.drop(1)
            .map { it.trim() }
            .filter { it.startsWith(":") }
            .map { s ->
                // remove leading ':' and optional single leading space
                val t = s.removePrefix(":")
                if (t.startsWith(' ')) t.substring(1) else t
            }

        if (defs.isEmpty()) return@replace match.value

        buildString {
            append("<dl><dt>")
            append(term)
            append("</dt>")
            defs.forEach { d ->
                append("<dd>")
                append(d)
                append("</dd>")
            }
            append("</dl>")
        }
    }
}

// Ensure all markdown-rendered tables use Bootstrap styling
private fun ensureBootstrapTables(html: String): String {
    // Match <table ...> opening tags (case-insensitive)
    val tableTagRegex = Regex("<table(\\s+[^>]*)?>", RegexOption.IGNORE_CASE)
    val classAttrRegex = Regex("\\bclass\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)

    return tableTagRegex.replace(html) { match ->
        val attrs = match.groups[1]?.value ?: ""
        if (attrs.isBlank()) return@replace "<table class=\"table\">"

        // If class attribute exists, append 'table' if not already present
        var newAttrs = attrs
        val m = classAttrRegex.find(attrs)
        if (m != null) {
            val quote = m.groupValues[1]
            val classes = m.groupValues[2]
            val hasTable = classes.split("\\s+".toRegex()).any { it.equals("table", ignoreCase = false) }
            if (!hasTable) {
                val updated = "class=" + quote + (classes.trim().let { if (it.isEmpty()) "table" else "$it table" }) + quote
                newAttrs = attrs.replaceRange(m.range, updated)
            }
        } else {
            // No class attribute, insert one at the beginning
            newAttrs = " class=\"table\"" + attrs
        }
        "<table$newAttrs>"
    }
}

// Ensure all markdown-rendered code blocks (<pre>...</pre>) have Bootstrap-like `.code` class
private fun ensureBootstrapCodeBlocks(html: String): String {
    // Target opening <pre ...> tags (case-insensitive)
    val preTagRegex = Regex("<pre(\\s+[^>]*)?>", RegexOption.IGNORE_CASE)
    val classAttrRegex = Regex("\\bclass\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)

    return preTagRegex.replace(html) { match ->
        val attrs = match.groups[1]?.value ?: ""
        if (attrs.isBlank()) return@replace "<pre class=\"code\">"

        var newAttrs = attrs
        val m = classAttrRegex.find(attrs)
        if (m != null) {
            val quote = m.groupValues[1]
            val classes = m.groupValues[2]
            val hasCode = classes.split("\\s+".toRegex()).any { it.equals("code", ignoreCase = false) }
            if (!hasCode) {
                val updated = "class=" + quote + (classes.trim().let { if (it.isEmpty()) "code" else "$it code" }) + quote
                newAttrs = attrs.replaceRange(m.range, updated)
            }
        } else {
            // No class attribute, insert one at the beginning
            newAttrs = " class=\"code\"" + attrs
        }
        "<pre$newAttrs>"
    }
}

// ---- Lyng syntax highlighting over rendered HTML ----
// This post-processor finds <pre><code class="language-lyng">…</code></pre> blocks and replaces the
// inner code HTML with token-wrapped spans using the common Lyng highlighter.
// It performs a minimal HTML entity decode on the code content to obtain the original text,
// runs the highlighter, then escapes segments back and wraps with <span class="hl-…">.
internal fun highlightLyngHtml(html: String): String {
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
        // If not explicitly Lyng, check if the <code> has any language class; if none, treat as Lyng by default
        val hasAnyLanguage = run {
            val cls = classAttrRegex.find(codeAttrs)?.groupValues?.getOrNull(2) ?: ""
            cls.split("\\s+".toRegex()).any { it.startsWith("language-", ignoreCase = true) }
        }

        val treatAsLyng = codeHasLyng || !hasAnyLanguage
        if (!treatAsLyng) return@replace m.value // leave untouched for non-Lyng languages

        val text = htmlUnescape(codeHtml)

        // If block has no explicit language (unfenced/indented), support doctest tail (trailing lines starting with ">>>")
        val (headText, tailTextOrNull) = if (!codeHasLyng && !hasAnyLanguage) splitDoctestTail(text) else text to null

        val headHighlighted = try {
            applyLyngHighlightToText(headText)
        } catch (_: Throwable) {
            return@replace m.value
        }
        val tailHighlighted = tailTextOrNull?.let { renderDoctestTailAsComments(it) } ?: ""

        val highlighted = headHighlighted + tailHighlighted

        // Preserve original attrs; ensure <pre> has existing attrs (Bootstrap '.code' was handled earlier)
        "<pre$preAttrs><code$codeAttrs>$highlighted</code></pre>"
    }
}

// Split trailing doctest tail: consecutive lines at the end whose trimmedStart starts with ">>>".
// Returns Pair(head, tail) where tail is null if no doctest lines found.
private fun splitDoctestTail(text: String): Pair<String, String?> {
    if (text.isEmpty()) return "" to null
    // Normalize to \n for splitting; remember if original ended with newline
    val hasTrailingNewline = text.endsWith("\n")
    val lines = text.split("\n")
    var i = lines.size - 1
    // Skip trailing completely empty lines before looking for doctest markers
    while (i >= 0 && lines[i].isEmpty()) i--
    var count = 0
    while (i >= 0) {
        val line = lines[i]
        // If last line is empty due to trailing newline, include it into tail only if there are already doctest lines
        val trimmed = line.trimStart()
        if (trimmed.isNotEmpty() && !trimmed.startsWith(">>>")) break
        // Accept empty line only if it follows some doctest lines (keeps spacing), else stop
        if (trimmed.isEmpty()) {
            if (count == 0) break else { count++; i--; continue }
        }
        // doctest line
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

// Render the doctest tail as comment-highlighted lines. Expects the original textual tail including newlines.
private fun renderDoctestTailAsComments(tail: String): String {
    if (tail.isEmpty()) return ""
    val sb = StringBuilder(tail.length + 32)
    var start = 0
    while (start <= tail.lastIndex) {
        val nl = tail.indexOf('\n', start)
        val line = if (nl >= 0) tail.substring(start, nl) else tail.substring(start)
        // Wrap the whole line in comment styling
        sb.append("<span class=\"hl-cmt\">")
        sb.append(htmlEscape(line))
        sb.append("</span>")
        if (nl >= 0) sb.append('\n')
        if (nl < 0) break else start = nl + 1
    }
    return sb.toString()
}

// Apply Lyng highlighter to raw code text, producing HTML with span classes.
internal fun applyLyngHighlightToText(text: String): String {
    val highlighter = net.sergeych.lyng.highlight.SimpleLyngHighlighter()
    // Use spans as produced by the fixed lynglib highlighter (comments already extend to EOL there)
    val spans = highlighter.highlight(text)
    if (spans.isEmpty()) return htmlEscape(text)
    val sb = StringBuilder(text.length + spans.size * 16)
    var pos = 0
    for (s in spans) {
        if (s.range.start > pos) {
            sb.append(htmlEscape(text.substring(pos, s.range.start)))
        }
        val cls = cssClassForKind(s.kind)
        sb.append('<').append("span class=\"").append(cls).append('\"').append('>')
        sb.append(htmlEscape(text.substring(s.range.start, s.range.endExclusive)))
        sb.append("</span>")
        pos = s.range.endExclusive
    }
    if (pos < text.length) sb.append(htmlEscape(text.substring(pos)))
    return sb.toString()
}

// Note: No site-side span post-processing — we rely on lynglib's SimpleLyngHighlighter for correctness.

private fun cssClassForKind(kind: net.sergeych.lyng.highlight.HighlightKind): String = when (kind) {
    net.sergeych.lyng.highlight.HighlightKind.Keyword -> "hl-kw"
    net.sergeych.lyng.highlight.HighlightKind.TypeName -> "hl-ty"
    net.sergeych.lyng.highlight.HighlightKind.Identifier -> "hl-id"
    net.sergeych.lyng.highlight.HighlightKind.Number -> "hl-num"
    net.sergeych.lyng.highlight.HighlightKind.String -> "hl-str"
    net.sergeych.lyng.highlight.HighlightKind.Char -> "hl-ch"
    net.sergeych.lyng.highlight.HighlightKind.Regex -> "hl-rx"
    net.sergeych.lyng.highlight.HighlightKind.Comment -> "hl-cmt"
    net.sergeych.lyng.highlight.HighlightKind.Operator -> "hl-op"
    net.sergeych.lyng.highlight.HighlightKind.Punctuation -> "hl-punc"
    net.sergeych.lyng.highlight.HighlightKind.Label -> "hl-lbl"
    net.sergeych.lyng.highlight.HighlightKind.Directive -> "hl-dir"
    net.sergeych.lyng.highlight.HighlightKind.Error -> "hl-err"
}

// Minimal HTML escaping for text nodes
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

// Minimal unescape for code inner HTML produced by marked
private fun htmlUnescape(s: String): String {
    // handle common entities only
    return s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}

private fun rewriteImages(root: HTMLElement, basePath: String) {
    val imgs = root.querySelectorAll("img")
    for (i in 0 until imgs.length) {
        val el = imgs.item(i) as? HTMLImageElement ?: continue
        val src = el.getAttribute("src") ?: continue
        if (src.startsWith("http") || src.startsWith("/") || src.startsWith("#")) continue
        el.setAttribute("src", normalizePath("$basePath/$src"))
    }
}

internal fun rewriteAnchors(
    root: HTMLElement,
    basePath: String,
    currentDocPath: String,
    navigate: (String) -> Unit
) {
    val asEl = root.querySelectorAll("a")
    for (i in 0 until asEl.length) {
        val a = asEl.item(i) as? HTMLAnchorElement ?: continue
        val href = a.getAttribute("href") ?: continue
        // Skip external and already-SPA hashes
        if (
            href.startsWith("http:") || href.startsWith("https:") ||
            href.startsWith("mailto:") || href.startsWith("tel:") ||
            href.startsWith("javascript:") || href.startsWith("/") ||
            href.startsWith("#/")
        ) continue
        if (href.startsWith("#")) {
            // Intra-page link: convert to SPA hash including current document route
            val frag = href.removePrefix("#")
            val route = "$currentDocPath#$frag"
            a.setAttribute("href", "#/$route")
            a.onclick = { ev ->
                ev.preventDefault()
                navigate(route)
            }
            continue
        }
        if (href.contains(".md")) {
            val parts = href.split('#', limit = 2)
            val mdPath = parts[0]
            val frag = if (parts.size > 1) parts[1] else null
            val target = if (mdPath.startsWith("docs/")) {
                normalizePath(mdPath)
            } else {
                normalizePath("$basePath/$mdPath")
            }
            val route = if (frag.isNullOrBlank()) {
                target
            } else {
                "$target#$frag"
            }
            a.setAttribute("href", "#/$route")
            a.onclick = { ev ->
                ev.preventDefault()
                navigate(route)
            }
        } else {
            // Non-md relative link: make it relative to the md file location
            a.setAttribute("href", normalizePath("$basePath/$href"))
        }
    }
}

internal fun buildToc(root: HTMLElement): List<TocItem> {
    val out = mutableListOf<TocItem>()
    val used = hashSetOf<String>()
    val hs = root.querySelectorAll("h1, h2, h3")
    for (i in 0 until hs.length) {
        val h = hs.item(i) as? HTMLHeadingElement ?: continue
        val level = when (h.tagName.uppercase()) {
            "H1" -> 1
            "H2" -> 2
            else -> 3
        }
        var id = h.id.ifBlank { slugify(h.textContent ?: "") }
        if (id.isBlank()) id = "section-${i + 1}"
        var unique = id
        var n = 2
        while (!used.add(unique)) {
            unique = "$id-$n"
            n++
        }
        h.id = unique
        out += TocItem(level, unique, h.textContent ?: "")
    }
    return out
}

internal fun slugify(s: String): String = s.lowercase()
    .replace("[^a-z0-9 _-]".toRegex(), "")
    .trim()
    .replace("[\n\r\t ]+".toRegex(), "-")

internal fun normalizePath(path: String): String {
    val parts = mutableListOf<String>()
    val raw = path.split('/')
    for (p in raw) {
        when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += p
        }
    }
    return parts.joinToString("/")
}

// ---- Scrollspy helpers ----
// Given a list of heading top positions relative to viewport (in px),
// returns the index of the active section using an offset. The active section
// is the last heading whose top is above or at the offset line.
// If none are above the offset, returns 0. If list is empty, returns 0.
fun activeIndexForTops(tops: List<Double>, offsetPx: Double): Int {
    if (tops.isEmpty()) return 0
    for (i in tops.indices) {
        if (tops[i] - offsetPx > 0.0) return i
    }
    // If all headings are above the offset, select the last one
    return tops.size - 1
}

fun main() {
    // Initialize automatic system theme before rendering UI
    initAutoTheme()
    renderComposable(rootElementId = "root") { App() }
}

// Extract anchor fragment from a window location hash of the form
// "#/docs/path.md#anchor" -> "anchor"; returns null if none
fun anchorFromHash(hash: String): String? {
    if (!hash.startsWith("#/")) return null
    val idx = hash.indexOf('#', startIndex = 2) // look for second '#'
    return if (idx >= 0 && idx + 1 < hash.length) hash.substring(idx + 1) else null
}
