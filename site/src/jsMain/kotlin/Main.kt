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

import externals.marked
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.*

// --------------- Lightweight debug logging ---------------
// Disable debug logging by default
private var SEARCH_DEBUG: Boolean = false
fun dlog(tag: String, msg: String) {
    if (!SEARCH_DEBUG) return
    try {
        console.log("[LYNG][$tag] $msg")
    } catch (_: dynamic) { }
}

@Suppress("unused")
private fun exposeSearchDebugToggle() {
    try {
        val w = window.asDynamic()
        w.setLyngSearchDebug = { enabled: Boolean ->
            SEARCH_DEBUG = enabled
            dlog("debug", "SEARCH_DEBUG=$enabled")
        }
        // Extra runtime helpers to diagnose search at runtime
        w.lyngSearchForceReindex = {
            try {
                dlog("debug", "lyngSearchForceReindex() called")
                searchIndex = null
                searchBuilding = false
                MainScopeProvider.scope.launch {
                    buildSearchIndexOnce()
                    val count = searchIndex?.size ?: -1
                    dlog("search", "forceReindex complete: indexed=$count")
                    searchIndex?.take(5)?.forEachIndexed { i, rec ->
                        dlog("search", "sample[$i]: ${rec.path} | ${rec.title}")
                    }
                }
            } catch (_: dynamic) { }
        }
        w.lyngSearchQuery = { q: String ->
            try {
                dlog("debug", "lyngSearchQuery('$q') called")
                MainScopeProvider.scope.launch {
                    if (searchIndex == null) buildSearchIndexOnce()
                    val idxSize = searchIndex?.size ?: -1
                    val res = performSearch(q)
                    dlog("search", "query '$q' on idx=$idxSize -> ${res.size} hits")
                    res.take(5).forEachIndexed { i, r ->
                        dlog("search", "hit[$i]: score=${scoreQuery(q, r)} path=${r.path} title=${r.title}")
                    }
                }
            } catch (_: dynamic) { }
        }
        dlog("debug", "window.setLyngSearchDebug(Boolean) is available in console")
    } catch (_: dynamic) { }
}

// externals moved to Externals.kt

// Ensure global scroll offset styles and keep a CSS var with the real fixed-top navbar height.
// This guarantees that scrolling to anchors or search hits is not hidden underneath the top bar.
fun ensureScrollOffsetStyles() {
    try {
        val doc = window.document
        if (doc.getElementById("scroll-offset-style") == null) {
            val style = doc.createElement("style") as org.w3c.dom.HTMLStyleElement
            style.id = "scroll-offset-style"
            style.textContent = (
                """
                /* Keep a dynamic CSS variable with the measured navbar height */
                :root { --navbar-offset: 56px; }

                /* Make native hash jumps and programmatic scroll account for the fixed header */
                html, body { scroll-padding-top: calc(var(--navbar-offset) + 8px); }

                /* When scrolled into view, keep headings and any id-targeted element below the topbar */
                .markdown-body h1,
                .markdown-body h2,
                .markdown-body h3,
                .markdown-body h4,
                .markdown-body h5,
                .markdown-body h6,
                .markdown-body [id] { scroll-margin-top: calc(var(--navbar-offset) + 8px); }

                /* Also offset search highlights as they can be the initial scroll target */
                mark.search-hit { scroll-margin-top: calc(var(--navbar-offset) + 8px) !important; }
                """
                .trimIndent()
            )
            doc.head?.appendChild(style)
        }
    } catch (_: Throwable) {
        // Best-effort
    }
}

// Measure the current fixed-top navbar height and update the CSS variable
fun updateNavbarOffsetVar(): Int {
    return try {
        val doc = window.document
        val nav = doc.querySelector("nav.navbar.fixed-top") as? HTMLElement
        val px = if (nav != null) kotlin.math.round(nav.getBoundingClientRect().height).toInt() else 0
        doc.documentElement?.let { root ->
            root.asDynamic().style?.setProperty?.invoke(root.asDynamic().style, "--navbar-offset", "${px}px")
        }
        px
    } catch (_: Throwable) { 0 }
}

// Ensure global CSS for search highlights is present (bright, visible everywhere)
fun ensureSearchHighlightStyles() {
    try {
        val doc = window.document
        if (doc.getElementById("search-hit-style") == null) {
            val style = doc.createElement("style") as org.w3c.dom.HTMLStyleElement
            style.id = "search-hit-style"
            // Use strong colors and !important to outshine theme/code styles
            style.textContent = (
                """
                mark.search-hit { 
                  background: #ffeb3b !important; /* bright yellow */
                  color: #000 !important;
                  padding: 0 .1em; 
                  border-radius: 2px; 
                  box-shadow: 0 0 0 2px #ffeb3b inset !important;
                }
                code mark.search-hit, pre mark.search-hit {
                  background: #ffd54f !important; /* slightly deeper in code blocks */
                  color: #000 !important;
                  box-shadow: 0 0 0 2px #ffd54f inset !important;
                }
                """
                .trimIndent()
            )
            doc.head?.appendChild(style)
        }
    } catch (_: Throwable) {
        // Best-effort; if styles can't be injected we still proceed
    }
}

// Ensure docs layout tweaks (reduce markdown body top margin to align with TOC)
fun ensureDocsLayoutStyles() {
    try {
        val doc = window.document
        if (doc.getElementById("docs-layout-style") == null) {
            val style = doc.createElement("style") as org.w3c.dom.HTMLStyleElement
            style.id = "docs-layout-style"
            style.textContent = (
                """
                /* Align the markdown content top edge with the TOC */
                .markdown-body {
                  margin-top: 0 !important;      /* remove extra outer spacing */
                  padding-top: 0 !important;     /* override GitHub markdown default top padding */
                }
                /* Ensure the first element inside markdown body doesn't add extra space */
                .markdown-body > :first-child {
                  margin-top: 0 !important;
                }
                /* Some markdown renderers give H1 extra top margin; neutralize when first */
                .markdown-body h1:first-child {
                  margin-top: 0 !important;
                }
                """
                .trimIndent()
            )
            doc.head?.appendChild(style)
        }
    } catch (_: Throwable) {
        // Best-effort
    }
}

// App() moved to App.kt

// DocLink and UnsafeRawHtml moved to Components.kt

fun currentRoute(): String {
    val h = window.location.hash
    // Support both "#/path" and "#path" formats
    val noHash = if (h.startsWith("#")) h.substring(1) else h
    return noHash.removePrefix("/")
}

fun routeToPath(route: String): String {
    val noParams = stripQuery(stripFragment(route))
    return if (noParams.startsWith("docs/")) noParams else "docs/$noParams"
}

// Strip trailing fragment from a route like "docs/file.md#anchor" -> "docs/file.md"
fun stripFragment(route: String): String = route.substringBefore('#')

// Strip query from a route like "docs/file.md?q=term" -> "docs/file.md"
fun stripQuery(route: String): String = route.substringBefore('?')

// Extract lowercase search terms from route query string (?q=...)
fun extractSearchTerms(route: String): List<String> {
    val queryPart = route.substringAfter('?', "")
    if (queryPart.isEmpty()) return emptyList()
    val params = queryPart.split('&')
    val qParam = params.firstOrNull { it.startsWith("q=") }?.substringAfter('=') ?: return emptyList()
    val decoded = try { decodeURIComponent(qParam) } catch (_: dynamic) { qParam }
    return decoded.trim().split(Regex("\\s+"))
        .map { it.lowercase() }
        .filter { it.isNotEmpty() }
}

// Highlight words in root that start with any of the terms (case-insensitive). Returns count of hits.
fun highlightSearchHits(root: HTMLElement, terms: List<String>): Int {
    // Make sure CSS for highlighting is injected
    ensureSearchHighlightStyles()
    // Always remove previous highlights first so calling with empty terms clears them
    try {
        val prev = root.getElementsByClassName("search-hit")
        // Because HTMLCollection is live, copy to array first
        val arr = (0 until prev.length).mapNotNull { prev.item(it) as? HTMLElement }.toList()
        arr.forEach { mark ->
            val parent = mark.parentNode
            val textNode = root.ownerDocument!!.createTextNode(mark.textContent ?: "")
            parent?.replaceChild(textNode, mark)
        }
    } catch (_: Throwable) {}

    if (terms.isEmpty()) return 0

    // Allow highlighting even inside CODE and PRE per request; still skip scripts, styles, and keyboard samples
    val skipTags = setOf("SCRIPT", "STYLE", "KBD", "SAMP")
    var hits = 0

    fun processNode(node: org.w3c.dom.Node) {
        when (node.nodeType) {
            org.w3c.dom.Node.ELEMENT_NODE -> {
                val el = node.unsafeCast<HTMLElement>()
                if (skipTags.contains(el.tagName)) return
                if (el.classList.contains("no-search")) return
                // copy list as array, because modifying tree during iteration
                val children = mutableListOf<org.w3c.dom.Node>()
                val cn = el.childNodes
                for (i in 0 until cn.length) children.add(cn.item(i)!!)
                children.forEach { processNode(it) }
            }
            org.w3c.dom.Node.TEXT_NODE -> {
                val text = node.nodeValue ?: return
                if (text.isBlank()) return
                // Tokenize by words and rebuild
                val parent = node.parentNode ?: return
                val doc = root.ownerDocument!!
                val container = doc.createDocumentFragment()
                var pos = 0
                val wordRegex = Regex("[A-Za-z0-9_]+")
                for (m in wordRegex.findAll(text)) {
                    val start = m.range.first
                    val end = m.range.last + 1
                    if (start > pos) container.appendChild(doc.createTextNode(text.substring(pos, start)))
                    val token = m.value
                    val tokenLower = token.lowercase()
                    // choose the longest term that is a prefix of the token
                    var bestLen = 0
                    if (terms.isNotEmpty()) {
                        for (t in terms) {
                            if (t.isNotEmpty() && tokenLower.startsWith(t) && t.length > bestLen) bestLen = t.length
                        }
                    }
                    if (bestLen > 0 && bestLen <= token.length) {
                        val prefix = token.substring(0, bestLen)
                        val suffix = token.substring(bestLen)
                        val mark = doc.createElement("mark") as HTMLElement
                        mark.className = "search-hit"
                        mark.textContent = prefix
                        container.appendChild(mark)
                        if (suffix.isNotEmpty()) container.appendChild(doc.createTextNode(suffix))
                        hits++
                    } else {
                        container.appendChild(doc.createTextNode(token))
                    }
                    pos = end
                }
                if (pos < text.length) container.appendChild(doc.createTextNode(text.substring(pos)))
                parent.replaceChild(container, node)
            }
        }
    }
    processNode(root)
    return hits
}

fun renderMarkdown(src: String): String =
    net.sergeych.lyngweb.highlightLyngHtml(
        net.sergeych.lyngweb.ensureBootstrapCodeBlocks(
            ensureBootstrapTables(
                ensureDefinitionLists(
                    marked.parse(src)
                )
            )
        )
    )

// Suspend variant that uses the AST-backed highlighter for Lyng code blocks.
suspend fun renderMarkdownAsync(src: String): String =
    net.sergeych.lyngweb.highlightLyngHtmlAsync(
        net.sergeych.lyngweb.ensureBootstrapCodeBlocks(
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

// PageTemplate moved to Components.kt

// DocsPage moved to Pages.kt

fun extractTitleFromMarkdown(md: String): String? {
    val lines = md.lines()
    val h1 = lines.firstOrNull { it.trimStart().startsWith("# ") }
    return h1?.trimStart()?.removePrefix("# ")?.trim()
}

suspend fun initDocsDropdown() {
    try {
        dlog("docs-dd", "initDocsDropdown start")
        val menu = document.getElementById("docsDropdownMenu") ?: run {
            dlog("docs-dd", "#docsDropdownMenu not found")
            return
        }
        // Fetch docs index
        val resp = window.fetch("docs-index.json").await()
        if (!resp.ok) {
            dlog("docs-dd", "docs-index.json fetch failed: status=${resp.status}")
            return
        }
        val text = resp.text().await()
        val arr = JSON.parse(text) as Array<String>
        val all = arr.toList().sorted()
        dlog("docs-dd", "index entries=${all.size}")
        // Filter excluded by reading each markdown and looking for the marker, and collect titles
        val filtered = mutableListOf<String>()
        val titles = mutableMapOf<String, String>()
        var excluded = 0
        var failed = 0
        for (path in all) {
            try {
                val url = "./" + encodeURI(path)
                val r = window.fetch(url).await()
                if (!r.ok) {
                    failed++
                    dlog("docs-dd", "fetch fail ${r.status} for $url")
                    continue
                }
                val body = r.text().await()
                if (!body.contains("[//]: # (excludeFromIndex)") &&
                    body.contains("[//]: # (topMenu)") ) {
                    filtered.add(path)
                    // Reuse shared title extractor: first H1 or fallback to file name
                    val title = extractTitleFromMarkdown(body) ?: path.substringAfterLast('/')
                    titles[path] = title
                } else excluded++
            } catch (t: Throwable) {
                failed++
                dlog("docs-dd", "exception fetching $path : ${t.message}")
            }
        }
        dlog("docs-dd", "filtered=${filtered.size} excluded=$excluded failed=$failed")
        // Sort entries by display name (file name) case-insensitively
        val sortedFiltered = filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.substringAfterLast('/') })
        // Build items after the static first items (tutorial, optional static entries, divider)
        var appended = 0
        sortedFiltered.forEach { path ->
            val name = titles[path] ?: path.substringAfterLast('/')
            val li = document.createElement("li")
            val a = document.createElement("a") as HTMLAnchorElement
            a.className = "dropdown-item"
            a.href = "#/$path"
            a.setAttribute("data-route", "docs")
            a.textContent = name
            // Ensure SPA navigation and close navbar collapse on small screens
            a.onclick = { ev ->
                ev.preventDefault()
                dlog("nav", "docs dropdown -> navigate #/$path")
                window.location.hash = "#/$path"
                closeNavbarCollapse()
            }
            li.appendChild(a)
            menu.appendChild(li)
            appended++
        }
        dlog("docs-dd", "appended=$appended docs to dropdown")
    } catch (_: Throwable) {
        dlog("docs-dd", "exception during initDocsDropdown")
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

// ---------------- Site-wide search (client-side) ----------------

internal data class DocRecord(val path: String, val title: String, val text: String)

private var searchIndex: List<DocRecord>? = null
private var searchBuilding = false
private var searchInitDone = false

// ---- Search history (last 7 entries) ----
private const val SEARCH_HISTORY_KEY = "lyng.search.history"

private fun loadSearchHistory(): MutableList<String> {
    return try {
        val raw = window.localStorage.getItem(SEARCH_HISTORY_KEY) ?: return mutableListOf()
        // Stored as newline-separated values to avoid JSON pitfalls across browsers
        raw.split('\n').mapNotNull { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    } catch (_: Throwable) {
        mutableListOf()
    }
}

private fun saveSearchHistory(list: List<String>) {
    try {
        window.localStorage.setItem(SEARCH_HISTORY_KEY, list.joinToString("\n"))
    } catch (_: Throwable) { }
}

internal fun rememberSearchQuery(q: String) {
    val query = q.trim()
    if (query.isBlank()) return
    val list = loadSearchHistory()
    list.removeAll { it.equals(query, ignoreCase = true) }
    list.add(0, query)
    while (list.size > 7) list.removeAt(list.lastIndex)
    saveSearchHistory(list)
}

internal fun norm(s: String): String = s.lowercase()
    .replace("`", " ")
    .replace("*", " ")
    .replace("#", " ")
    .replace("[", " ")
    .replace("]", " ")
    .replace("(", " ")
    .replace(")", " ")
    .replace(Regex("\\n+"), " ")
    .replace(Regex("\\s+"), " ").trim()

internal fun plainFromMarkdown(md: String): String {
    // Construct Regex instances at call time inside try/catch to avoid module init crashes
    // in browsers that are strict about Unicode RegExp parsing (Safari/Chrome).
    // Use non-greedy dot-all equivalents ("[\n\r\s\S]") instead of character classes with ']' where possible.
    try {
        // Safer patterns (avoid unescaped ']' inside character classes):
        val reCodeBlocks = Regex("```[\\s\\S]*?```")
        val reInlineCode = Regex("`[^`]*`")
        val reBlockquote = Regex("^> +", setOf(RegexOption.MULTILINE))
        val reHeadings = Regex("^#+ +", setOf(RegexOption.MULTILINE))
        // Images: ![alt](url) — capture alt lazily with [\s\S]*? to avoid character class pitfalls
        val reImage = Regex("!\\[([\\s\\S]*?)]\\([^)]*\\)")
        // Links: [text](url) — same approach, keep the link text in group 1
        val reLink = Regex("\\[([\\s\\S]*?)]\\([^)]*\\)")

        var t = md
        // Triple-backtick code blocks across lines
        t = t.replace(reCodeBlocks, " ")
        // Inline code
        t = t.replace(reInlineCode, " ")
        // Strip blockquotes and headings markers
        t = t.replace(reBlockquote, "")
        t = t.replace(reHeadings, "")
        // Images and links
        t = t.replace(reImage, " ")
        // Keep link text (group 1). Kotlin string needs "$" escaped once to pass "$1"
        t = t.replace(reLink, "\$1")
        return norm(t)
    } catch (e: Throwable) {
        dlog("search", "plainFromMarkdown error: ${e.message}")
        // Minimal safe fallback: strip code blocks and inline code, then normalize
        var t = md
        t = t.replace(Regex("```[\\s\\S]*?```"), " ")
        t = t.replace(Regex("`[^`]*`"), " ")
        return norm(t)
    }
}

private suspend fun buildSearchIndexOnce() {
    if (searchIndex != null || searchBuilding) return
    searchBuilding = true
    dlog("search", "buildSearchIndexOnce: start")
    try {
        val resp = window.fetch("docs-index.json").await()
        if (!resp.ok) {
            dlog("search", "docs-index.json fetch failed: status=${resp.status}")
            return
        }
        val text = resp.text().await()
        val arr = JSON.parse(text) as Array<String>
        val all = arr.toList().sorted()
        dlog("search", "docs-index entries=${all.size}")
        val list = mutableListOf<DocRecord>()
        var excluded = 0
        var failed = 0
        var loggedFailures = 0
        for (path in all) {
            try {
                // Always fetch via a relative URL from site root and encode non-ASCII safely
                val url = "./" + encodeURI(path)
                val r = window.fetch(url).await()
                if (!r.ok) {
                    failed++
                    if (loggedFailures < 3) {
                        dlog("search", "fetch fail ${r.status} for $url")
                        loggedFailures++
                    }
                    continue
                }
                val body = r.text().await()
                if (body.contains("[//]: # (excludeFromIndex)")) { excluded++; continue }
                val title = extractTitleFromMarkdown(body) ?: path.substringAfterLast('/')
                val plain = plainFromMarkdown(body)
                list += DocRecord(path = path, title = title, text = plain)
            } catch (t: Throwable) {
                failed++
                if (loggedFailures < 3) {
                    dlog("search", "exception processing $path : ${t.message}")
                    loggedFailures++
                }
            }
        }
        searchIndex = list
        dlog("search", "buildSearchIndexOnce: done, indexed=${list.size} excluded=$excluded failed=$failed")
        list.take(5).forEachIndexed { i, rec ->
            dlog("search", "indexed[$i]: ${rec.path} | ${rec.title} (len=${rec.text.length})")
        }
    } catch (_: Throwable) {
        dlog("search", "buildSearchIndexOnce: exception")
    } finally {
        searchBuilding = false
    }
}

private fun scoreQuery(q: String, rec: DocRecord): Int {
    val terms = q.trim().split(Regex("\\s+")).map { it.lowercase() }.filter { it.isNotEmpty() }
    if (terms.isEmpty()) return 0
    var score = 0
    val title = norm(rec.title)
    val text = rec.text
    // Title bonuses: longer prefix matches get higher score
    for (t in terms) {
        if (title.startsWith(t)) score += 120 + t.length
        else if (title.split(Regex("[A-Za-z0-9_]+")).isEmpty()) { /* no-op */ }
        else {
            // title words prefix
            val wr = Regex("[A-Za-z0-9_]+")
            if (wr.findAll(title).any { it.value.startsWith(t) }) score += 70 + t.length
        }
    }
    // Body: count how many tokens start with any term, weight by term length (cap to avoid giant docs dominating)
    run {
        val wr = Regex("[A-Za-z0-9_]+")
        var matches = 0
        for (m in wr.findAll(text)) {
            val token = m.value
            val tl = token.lowercase()
            var best = 0
            for (t in terms) if (tl.startsWith(t) && t.length > best) best = t.length
            if (best > 0) {
                matches++
                score += 2 * best
                if (matches >= 50) break // cap
            }
        }
    }
    // Prefer shorter files a bit
    score += (200 - kotlin.math.min(200, text.length / 500))
    return score
}

private fun renderSearchHistoryDropdown(menu: HTMLDivElement) {
    val hist = loadSearchHistory()
    if (hist.isEmpty()) {
        menu.innerHTML = "<div class=\"dropdown-item disabled\">No recent searches</div>"
    } else {
        val items = buildString {
            hist.take(7).forEach { hq ->
                val safe = hq.replace("<", "&lt;").replace(">", "&gt;")
                append("<a href=\"#/search?q=" + encodeURIComponent(hq) + "\" class=\"dropdown-item\" data-q=\"")
                append(safe)
                append("\">")
                append(safe)
                append("</a>")
            }
        }
        menu.innerHTML = items
    }
    menu.classList.add("show")
}

private fun hideSearchResults(menu: HTMLDivElement) {
    dlog("search-ui", "hideSearchResults")
    menu.classList.remove("show")
    menu.innerHTML = ""
}

private suspend fun performSearch(q: String): List<DocRecord> {
    if (searchIndex == null) buildSearchIndexOnce()
    val idx = searchIndex ?: run {
        dlog("search", "performSearch: index is null after build attempt")
        return emptyList()
    }
    if (q.isBlank()) return emptyList()
    val terms = q.trim().split(Regex("\\s+")).map { it.lowercase() }.filter { it.isNotEmpty() }
    if (terms.isEmpty()) return emptyList()
    val wordRegex = Regex("[A-Za-z0-9_]+")
    fun hasPrefixWord(s: String): Boolean {
        for (m in wordRegex.findAll(s)) {
            val tl = m.value.lowercase()
            for (t in terms) if (tl.startsWith(t)) return true
        }
        return false
    }
    val res = idx.filter { rec ->
        hasPrefixWord(norm(rec.title)) || hasPrefixWord(rec.text)
    }
    dlog("search", "performSearch: q='$q' idx=${idx.size} -> ${res.size} results")
    return res
}

private fun debounce(scope: CoroutineScope, delayMs: Long, block: suspend () -> Unit): () -> Unit {
    var job: Job? = null
    return {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            block()
        }
    }
}

fun initTopSearch(attempt: Int = 0) {
    if (searchInitDone) return
    val input = document.getElementById("topSearch") as? HTMLInputElement
    val menu = document.getElementById("topSearchMenu") as? HTMLDivElement
    if (input == null || menu == null) {
        // Retry a few times in case DOM is not fully ready yet
        if (attempt < 10) {
            dlog("init", "initTopSearch: missing nodes (input=$input, menu=$menu) retry $attempt")
            window.setTimeout({ initTopSearch(attempt + 1) }, 50)
        }
        return
    }
    dlog("init", "initTopSearch: wiring handlers")
    val scope = MainScopeProvider.scope

    // Debounced dropdown history refresher
    val runSearch = debounce(scope, 120L) {
        renderSearchHistoryDropdown(menu)
    }

    // Keep the input focused when interacting with the dropdown so it doesn't blur/close
    menu.onmousedown = { ev ->
        ev.preventDefault()
        input.focus()
    }

    input.oninput = {
        dlog("event", "search oninput value='${input.value}'")
        runSearch()
    }
    input.onfocus = {
        dlog("event", "search onfocus: show history")
        renderSearchHistoryDropdown(menu)
    }
    input.onkeydown = { ev ->
        val key = ev.asDynamic().key as String
        dlog("event", "search onkeydown key='$key'")
        when (ev.asDynamic().key as String) {
            "Escape" -> {
                hideSearchResults(menu)
            }
            "Enter" -> {
                val q = input.value.trim()
                if (q.isNotBlank()) {
                    rememberSearchQuery(q)
                    val url = "#/search?q=" + encodeURIComponent(q)
                    dlog("nav", "Enter -> navigate $url")
                    window.location.hash = url
                    hideSearchResults(menu)
                    closeNavbarCollapse()
                }
            }
    }
    }
    // Hide on blur after a short delay to allow click
    input.onblur = {
        dlog("event", "search onblur -> hide after delay")
        window.setTimeout({ hideSearchResults(menu) }, 150)
    }
    searchInitDone = true
    dlog("init", "initTopSearch: done")
}

// Provide a global coroutine scope for utilities without introducing a framework
private object MainScopeProvider {
    val scope: CoroutineScope by lazy { kotlinx.coroutines.MainScope() }
}

// ReferencePage moved to ReferencePage.kt
// HomePage moved to HomePage.kt

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
fun ensureBootstrapCodeBlocks(html: String): String {
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

// moved highlighting utilities to :lyngweb

fun rewriteImages(root: HTMLElement, basePath: String) {
    val imgs = root.querySelectorAll("img")
    for (i in 0 until imgs.length) {
        val el = imgs.item(i) as? HTMLImageElement ?: continue
        val src = el.getAttribute("src") ?: continue
        if (src.startsWith("http") || src.startsWith("/") || src.startsWith("#")) continue
        el.setAttribute("src", normalizePath("$basePath/$src"))
    }
}

fun rewriteAnchors(
    root: HTMLElement,
    basePath: String,
    currentDocPath: String,
    navigate: (String) -> Unit
) {
    val asEl = root.querySelectorAll("a")
    for (i in 0 until asEl.length) {
        val a = asEl.item(i) as? HTMLAnchorElement ?: continue
        // Skip the inline Docs back button we inject before the first H1
        if (a.classList.contains("doc-back-btn") || a.getAttribute("data-no-spa") == "true") continue
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

fun buildToc(root: HTMLElement): List<TocItem> {
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
