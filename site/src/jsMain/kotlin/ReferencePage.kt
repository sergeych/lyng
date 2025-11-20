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
import org.jetbrains.compose.web.dom.*

@Composable
fun ReferencePage() {
    var docs by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Titles resolved from the first H1 of each markdown document
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

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

    // Once we have the docs list, fetch their titles (H1) progressively
    LaunchedEffect(docs) {
        val list = docs ?: return@LaunchedEffect
        // Reset titles when list changes
        titles = emptyMap()
        // Fetch sequentially to avoid flooding; fast enough for small/medium doc sets
        for (path in list) {
            try {
                val mdPath = if (path.startsWith("docs/")) path else "docs/$path"
                val url = "./" + encodeURI(mdPath)
                val resp = window.fetch(url).await()
                if (!resp.ok) continue
                val text = resp.text().await()
                val title = extractTitleFromMarkdown(text) ?: path.substringAfterLast('/')
                // Update state incrementally
                titles = titles + (path to title)
            } catch (_: Throwable) {
                // ignore individual failures; fallback will be filename
            }
        }
    }

    H2({ classes("h5", "mb-3") }) { Text("Reference") }
    P({ classes("text-muted") }) { Text("Browse all documentation pages included in this build.") }

    when {
        error != null -> Div({ classes("alert", "alert-danger") }) { Text(error!!) }
        docs == null -> P { Text("Loading index…") }
        docs!!.isEmpty() -> P { Text("No documents found.") }
        else -> {
            Ul({ classes("list-group") }) {
                docs!!.sorted().forEach { path ->
                    val displayTitle = titles[path] ?: path.substringAfterLast('/')
                    Li({ classes("list-group-item", "d-flex", "justify-content-between", "align-items-center") }) {
                        Div({}) {
                            A(attrs = {
                                classes("link-body-emphasis", "text-decoration-none")
                                attr("href", "#/$path")
                            }) { Text(displayTitle) }
                            Br()
                            Small({ classes("text-muted") }) { Text(path) }
                        }
                        I({ classes("bi", "bi-chevron-right") })
                    }
                }
            }
        }
    }
}
