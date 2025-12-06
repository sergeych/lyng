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
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement

@Composable
fun App() {
    var route by remember { mutableStateOf(currentRoute()) }
    var html by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var toc by remember { mutableStateOf<List<TocItem>>(emptyList()) }
    var activeTocId by remember { mutableStateOf<String?>(null) }
    var contentEl by remember { mutableStateOf<HTMLElement?>(null) }
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
        val handler: (org.w3c.dom.events.Event) -> Unit = { updateNavbarOffsetVar() }
        window.addEventListener("resize", handler)
        onDispose { window.removeEventListener("resize", handler) }
    }

    DisposableEffect(Unit) {
        val listener: (org.w3c.dom.events.Event) -> Unit = { route = currentRoute() }
        window.addEventListener("hashchange", listener)
        onDispose { window.removeEventListener("hashchange", listener) }
    }

    PageTemplate(title = when {
        isDocsRoute -> null
        route.startsWith("reference") -> "Reference"
        route.isBlank() -> null
        else -> null
    }) {
        Div({ classes("row", "gy-4") }) {
            if (isDocsRoute) {
                Div({ classes("col-12", "col-lg-3") }) {
                    Nav({
                        classes("position-sticky")
                        attr("style", "top: calc(var(--navbar-offset) + 1rem)")
                    }) {
                        H2({ classes("h6", "text-uppercase", "text-muted") }) { Text("On this page") }
                        Ul({ classes("list-unstyled") }) {
                            toc.forEach { item ->
                                Li({ classes("mb-1") }) {
                                    val pad = when (item.level) { 1 -> "0"; 2 -> "0.75rem"; else -> "1.5rem" }
                                    val routeNoFrag = route.substringBefore('#')
                                    val tocHref = "#/$routeNoFrag#${item.id}"
                                    A(attrs = {
                                        attr("href", tocHref)
                                        attr("style", "padding-left: $pad")
                                        classes("link-body-emphasis", "text-decoration-none")
                                        if (activeTocId == item.id) {
                                            classes("fw-semibold", "text-primary")
                                            attr("aria-current", "true")
                                        }
                                        onClick {
                                            it.preventDefault()
                                            window.location.hash = tocHref
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

            Div({ classes("col-12", if (isDocsRoute) "col-lg-9" else "col-lg-12") }) {
                when {
                    route.isBlank() -> HomePage()
                    route == "tryling" -> TryLyngPage()
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
