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
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLHeadingElement
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLImageElement
import externals.marked

data class TocItem(val level: Int, val id: String, val title: String)

@Composable
fun App() {
    var route by remember { mutableStateOf(currentRoute()) }
    var html by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var toc by remember { mutableStateOf<List<TocItem>>(emptyList()) }
    var contentEl by remember { mutableStateOf<HTMLElement?>(null) }
    val isDocsRoute = route.startsWith("docs/")
    // A stable key for the current document path (without fragment). Used to avoid
    // re-fetching when only the in-page anchor changes.
    val docKey = stripFragment(route)

    // Listen to hash changes (routing)
    DisposableEffect(Unit) {
        val listener: (org.w3c.dom.events.Event) -> Unit = {
            route = currentRoute()
        }
        window.addEventListener("hashchange", listener)
        onDispose { window.removeEventListener("hashchange", listener) }
    }

    // Fetch and render markdown whenever the document path changes (ignore fragment-only changes)
    LaunchedEffect(docKey) {
        error = null
        html = null
        if (!isDocsRoute) return@LaunchedEffect
        val path = routeToPath(route)
        try {
            val resp = window.fetch(path).await()
            if (!resp.ok) {
                error = "Not found: $path (${resp.status})"
            } else {
                val text = resp.text().await()
                html = renderMarkdown(text)
            }
        } catch (t: Throwable) {
            error = "Failed to load: $path — ${t.message}"
        }
    }

    // Post-process links, images and build TOC after html injection
    LaunchedEffect(html) {
        if (!isDocsRoute) return@LaunchedEffect
        val el = contentEl ?: return@LaunchedEffect
        // Wait next tick so DOM has the HTML
        window.setTimeout({
            val basePath = routeToPath(route).substringBeforeLast('/', "docs")
            rewriteImages(el, basePath)
            rewriteAnchors(el, basePath) { newRoute ->
                // Preserve potential anchor contained in newRoute and set SPA hash
                window.location.hash = "#/$newRoute"
            }
            toc = buildToc(el)

            // If the current hash includes an anchor (e.g., #/docs/file.md#section), scroll to it
            val frag = anchorFromHash(window.location.hash)
            if (!frag.isNullOrBlank()) {
                val target = el.ownerDocument?.getElementById(frag)
                (target as? HTMLElement)?.scrollIntoView()
            }
        }, 0)
    }

    // When only the fragment changes on the same document, scroll to the target without re-fetching
    LaunchedEffect(route) {
        if (!isDocsRoute) return@LaunchedEffect
        val el = contentEl ?: return@LaunchedEffect
        window.setTimeout({
            val frag = anchorFromHash(window.location.hash)
            if (!frag.isNullOrBlank()) {
                val target = el.ownerDocument?.getElementById(frag)
                (target as? HTMLElement)?.scrollIntoView()
            }
        }, 0)
    }

    // Layout
    Div({ classes("container", "py-4") }) {
        H1({ classes("display-6", "mb-3") }) { Text("Ling Lib Docs") }

        Div({ classes("row", "gy-4") }) {
            // Sidebar TOC
            Div({ classes("col-12", "col-lg-3") }) {
                Nav({ classes("position-sticky"); attr("style", "top: 1rem") }) {
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

            // Main content
            Div({ classes("col-12", "col-lg-9") }) {
                // Top actions
                Div({ classes("mb-3", "d-flex", "gap-2", "flex-wrap", "align-items-center") }) {
                    // Reference page link
                    A(attrs = {
                        classes("btn", "btn-sm", "btn-primary")
                        attr("href", "#/reference")
                        onClick { it.preventDefault(); window.location.hash = "#/reference" }
                    }) { Text("Reference") }

                    // Sample quick links
                    DocLink("Iterable.md")
                    DocLink("Iterator.md")
                    DocLink("perf_guide.md")
                }

                if (!isDocsRoute) {
                    ReferencePage()
                } else if (error != null) {
                    Div({ classes("alert", "alert-danger") }) { Text(error!!) }
                } else if (html == null) {
                    P { Text("Loading…") }
                } else {
                    // Back button
                    Div({ classes("mb-3") }) {
                        A(attrs = {
                            classes("btn", "btn-outline-secondary", "btn-sm")
                            onClick {
                                it.preventDefault()
                                // Try browser history back; if not possible, go to reference
                                try {
                                    if (window.history.length > 1) window.history.back()
                                    else window.location.hash = "#/reference"
                                } catch (e: dynamic) {
                                    window.location.hash = "#/reference"
                                }
                            }
                            attr("href", "#/reference")
                        }) {
                            I({ classes("bi", "bi-arrow-left", "me-1") })
                            Text("Back")
                        }
                    }
                    // Inject rendered HTML
                    Div({
                        classes("markdown-body")
                        ref {
                            contentEl = it
                            onDispose {
                                if (contentEl === it) contentEl = null
                            }
                        }
                    }) {
                        // Unsafe raw HTML is needed to render markdown output
                        // Compose for Web allows raw HTML injection via Text API in unsafe context
                        // but the simpler way is to use the deprecated attribute; instead use raw
                        UnsafeRawHtml(html!!)
                    }
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

fun currentRoute(): String = window.location.hash.removePrefix("#/").ifBlank { "docs/Iterator.md" }

fun routeToPath(route: String): String {
    val noFrag = stripFragment(route)
    return if (noFrag.startsWith("docs/")) noFrag else "docs/$noFrag"
}

// Strip trailing fragment from a route like "docs/file.md#anchor" -> "docs/file.md"
fun stripFragment(route: String): String = route.substringBefore('#')

fun renderMarkdown(src: String): String =
    ensureBootstrapCodeBlocks(
        ensureBootstrapTables(
            ensureDefinitionLists(
                marked.parse(src)
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

private fun rewriteImages(root: HTMLElement, basePath: String) {
    val imgs = root.querySelectorAll("img")
    for (i in 0 until imgs.length) {
        val el = imgs.item(i) as? HTMLImageElement ?: continue
        val src = el.getAttribute("src") ?: continue
        if (src.startsWith("http") || src.startsWith("/") || src.startsWith("#")) continue
        el.setAttribute("src", normalizePath("$basePath/$src"))
    }
}

private fun rewriteAnchors(root: HTMLElement, basePath: String, navigate: (String) -> Unit) {
    val asEl = root.querySelectorAll("a")
    for (i in 0 until asEl.length) {
        val a = asEl.item(i) as? HTMLAnchorElement ?: continue
        val href = a.getAttribute("href") ?: continue
        if (href.startsWith("http") || href.startsWith("/")) continue
        if (href.startsWith("#")) continue // intra-page
        if (href.contains(".md")) {
            val parts = href.split('#', limit = 2)
            val mdPath = parts[0]
            val frag = if (parts.size > 1) parts[1] else null
            val target = normalizePath("$basePath/$mdPath")
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

private fun buildToc(root: HTMLElement): List<TocItem> {
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

private fun slugify(s: String): String = s.lowercase()
    .replace("[^a-z0-9 _-]".toRegex(), "")
    .trim()
    .replace("[\n\r\t ]+".toRegex(), "-")

private fun normalizePath(path: String): String {
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

fun main() {
    renderComposable(rootElementId = "root") { App() }
}

// Extract anchor fragment from a window location hash of the form
// "#/docs/path.md#anchor" -> "anchor"; returns null if none
fun anchorFromHash(hash: String): String? {
    if (!hash.startsWith("#/")) return null
    val idx = hash.indexOf('#', startIndex = 2) // look for second '#'
    return if (idx >= 0 && idx + 1 < hash.length) hash.substring(idx + 1) else null
}
