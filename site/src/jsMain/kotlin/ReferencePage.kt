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
import kotlinx.coroutines.await
import net.sergeych.lyng.miniast.*
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

    // Built-in APIs (from registry)
    Hr()
    H2({ classes("h5", "mb-3", "mt-4") }) { Text("Built-in APIs") }
    val modules = remember { BuiltinDocRegistry.allModules().sorted() }
    if (modules.isEmpty()) {
        P({ classes("text-muted") }) { Text("No built-in modules registered.") }
    } else {
        modules.forEach { modName ->
            val decls = BuiltinDocRegistry.docsForModule(modName)
            if (decls.isEmpty()) return@forEach
            H3({ classes("h6", "mt-3") }) { Text(modName) }
            Ul({ classes("list-group", "mb-3") }) {
                decls.forEach { d ->
                    Li({ classes("list-group-item") }) {
                        when (d) {
                            is MiniFunDecl -> {
                                val sig = signatureOf(d)
                                Div { Text("fun ${d.name}$sig") }
                                d.doc?.summary?.let { Small({ classes("text-muted") }) { Text(it) } }
                            }
                            is MiniValDecl -> {
                                val kind = if (d.mutable) "var" else "val"
                                val t = typeOf(d.type)
                                Div { Text("$kind ${d.name}$t") }
                                d.doc?.summary?.let { Small({ classes("text-muted") }) { Text(it) } }
                            }
                            is MiniClassDecl -> {
                                Div { Text("class ${d.name}") }
                                d.doc?.summary?.let { Small({ classes("text-muted") }) { Text(it) } }
                                if (d.members.isNotEmpty()) {
                                    Ul({ classes("mt-2") }) {
                                        d.members.forEach { m ->
                                            when (m) {
                                                is MiniMemberFunDecl -> {
                                                    val params = m.params.joinToString(", ") { p ->
                                                        val ts = typeOf(p.type)
                                                        if (ts.isNotBlank()) "${p.name}${ts}" else p.name
                                                    }
                                                    val ret = typeOf(m.returnType)
                                                    val staticStr = if (m.isStatic) "static " else ""
                                                    Li { Text("${staticStr}method ${d.name}.${m.name}(${params})${ret}") }
                                                }
                                                is MiniInitDecl -> {
                                                    // we don't doc init {} blocks at all
                                                }
                                                is MiniMemberValDecl -> {
                                                    val ts = typeOf(m.type)
                                                    val kindM = if (m.mutable) "var" else "val"
                                                    val staticStr = if (m.isStatic) "static " else ""
                                                    Li { Text("${staticStr}${kindM} ${d.name}.${m.name}${ts}") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            is MiniEnumDecl -> {
                                Div { Text("enum ${d.name}") }
                                d.doc?.summary?.let { Small({ classes("text-muted") }) { Text(it) } }
                                if (d.entries.isNotEmpty()) {
                                    Ul({ classes("mt-2") }) {
                                        d.entries.forEach { entry ->
                                            Li { Text(entry) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- helpers (mirror IDE provider minimal renderers) ---
private fun typeOf(t: MiniTypeRef?): String = when (t) {
    is MiniTypeName -> ": " + t.segments.joinToString(".") { it.name } + if (t.nullable) "?" else ""
    is MiniGenericType -> {
        val base = typeOf(t.base).removePrefix(": ")
        val args = t.args.joinToString(", ") { typeOf(it).removePrefix(": ") }
        ": ${base}<${args}>" + if (t.nullable) "?" else ""
    }
    is MiniFunctionType -> ": (..) -> .." + if (t.nullable) "?" else ""
    is MiniTypeVar -> ": ${t.name}" + if (t.nullable) "?" else ""
    null -> ""
}

private fun signatureOf(fn: MiniFunDecl): String {
    val params = fn.params.joinToString(", ") { p ->
        val ts = typeOf(p.type)
        if (ts.isNotBlank()) "${p.name}${ts}" else p.name
    }
    val ret = typeOf(fn.returnType)
    return "(${params})${ret}"
}
