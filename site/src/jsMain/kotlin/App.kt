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

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement

private const val DESKTOP_TOC_BREAKPOINT_PX = 992

fun isDesktopTocLayout(viewportWidthPx: Int): Boolean = viewportWidthPx >= DESKTOP_TOC_BREAKPOINT_PX

@Composable
fun App() {
    var route by remember { mutableStateOf(currentRoute()) }
    var html by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var toc by remember { mutableStateOf<List<TocItem>>(emptyList()) }
    var activeTocId by remember { mutableStateOf<String?>(null) }
    var contentEl by remember { mutableStateOf<HTMLElement?>(null) }
    var navEl by remember { mutableStateOf<HTMLElement?>(null) }
    var mobileTocExpanded by remember { mutableStateOf(false) }
    var isDesktopToc by remember { mutableStateOf(isDesktopTocLayout(window.innerWidth)) }
    val isDocsRoute = route.startsWith("docs/")
    val docKey = stripFragment(route)

    LaunchedEffect(Unit) {
        dlog("init", "initDocsDropdown()")
        initDocsDropdown()
    }

    LaunchedEffect(Unit) {
        dlog("init", "initTopSearch()")
        initTopSearch()
    }

    LaunchedEffect(Unit) { ensureDocsLayoutStyles() }

    LaunchedEffect(Unit) {
        ensureScrollOffsetStyles()
        updateNavbarOffsetVar()
    }

    DisposableEffect(Unit) {
        val handler: (org.w3c.dom.events.Event) -> Unit = {
            updateNavbarOffsetVar()
            isDesktopToc = isDesktopTocLayout(window.innerWidth)
        }
        isDesktopToc = isDesktopTocLayout(window.innerWidth)
        window.addEventListener("resize", handler)
        onDispose { window.removeEventListener("resize", handler) }
    }

    DisposableEffect(Unit) {
        val listener: (org.w3c.dom.events.Event) -> Unit = { route = currentRoute() }
        window.addEventListener("hashchange", listener)
        onDispose { window.removeEventListener("hashchange", listener) }
    }

    LaunchedEffect(activeTocId, isDesktopToc) {
        if (!isDesktopToc) return@LaunchedEffect
        val activeId = activeTocId ?: return@LaunchedEffect
        val nav = navEl ?: return@LaunchedEffect
        val activeLink = nav.querySelector("a[data-toc-id=\"$activeId\"]") as? HTMLElement
        activeLink?.scrollIntoView(js("({block: 'nearest', behavior: 'smooth'})"))
    }

    LaunchedEffect(docKey) {
        mobileTocExpanded = false
    }

    PageTemplate(title = when {
        isDocsRoute -> null
        route.startsWith("authors") -> "Authors"
        route.startsWith("reference") -> "Reference"
        route.isBlank() -> null
        else -> null
    }) {
        Div({ classes("row", "gy-4") }) {
            if (isDocsRoute) {
                Div({ classes("col-12", "col-lg-3") }) {
                    if (toc.isNotEmpty() && !isDesktopToc) {
                        Button(attrs = {
                            classes("btn", "btn-outline-secondary", "w-100", "mb-3", "d-lg-none")
                            attr("type", "button")
                            attr("aria-expanded", mobileTocExpanded.toString())
                            attr("aria-controls", "docs-toc-nav")
                            onClick { mobileTocExpanded = !mobileTocExpanded }
                        }) {
                            Text(if (mobileTocExpanded) "Hide contents" else "Show contents")
                        }
                    }
                    if (toc.isNotEmpty() && (isDesktopToc || mobileTocExpanded)) {
                        TocNav(
                            toc = toc,
                            route = route,
                            activeTocId = activeTocId,
                            isDesktopToc = isDesktopToc,
                            contentEl = contentEl,
                            onNavigate = {
                                if (!isDesktopToc) mobileTocExpanded = false
                            },
                            onNavEl = { navEl = it }
                        )
                    }
                }
            }

            Div({ classes("col-12", if (isDocsRoute) "col-lg-9" else "col-lg-12") }) {
                when {
                    route.isBlank() -> HomePage()
                    route.startsWith("authors") -> AuthorsPage()
                    route.startsWith("tryling") -> TryLyngPage(route)
                    route.startsWith("search") -> SearchPage(route)
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
private fun TocNav(
    toc: List<TocItem>,
    route: String,
    activeTocId: String?,
    isDesktopToc: Boolean,
    contentEl: HTMLElement?,
    onNavigate: () -> Unit,
    onNavEl: (HTMLElement?) -> Unit,
) {
    Nav({
        id("docs-toc-nav")
        classes(if (isDesktopToc) "position-sticky" else "docs-mobile-toc", "mb-3", "mb-lg-0")
        attr(
            "style",
            if (isDesktopToc) {
                "top: calc(var(--navbar-offset) + 1rem); max-height: calc(100vh - var(--navbar-offset) - 2rem); overflow-y: auto;"
            } else {
                "max-height: min(50vh, 24rem); overflow-y: auto;"
            }
        )
        ref {
            onNavEl(it)
            onDispose { onNavEl(null) }
        }
    }) {
        H2({ classes("h6", "text-uppercase", "text-muted", "mb-2") }) { Text("On this page") }
        Ul({ classes("list-unstyled", "mb-0") }) {
            toc.forEach { item ->
                Li({ classes("mb-1") }) {
                    val pad = "${(item.level - 1) * 0.75}rem"
                    val routeNoFrag = route.substringBefore('#')
                    val tocHref = "#/$routeNoFrag#${item.id}"
                    A(attrs = {
                        attr("href", tocHref)
                        attr("data-toc-id", item.id)
                        attr("style", "display: block; padding-left: $pad")
                        classes("link-body-emphasis", "text-decoration-none")
                        if (activeTocId == item.id) {
                            classes("fw-semibold", "text-primary")
                            attr("aria-current", "true")
                        }
                        onClick {
                            it.preventDefault()
                            onNavigate()
                            window.location.hash = tocHref
                            contentEl?.ownerDocument?.getElementById(item.id)
                                ?.let { heading -> (heading as? HTMLElement)?.scrollIntoView() }
                        }
                    }) { Text(item.title) }
                }
            }
        }
    }
}
