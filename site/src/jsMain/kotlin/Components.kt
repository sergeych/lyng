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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement

@Composable
fun DocLink(name: String) {
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
fun UnsafeRawHtml(html: String) {
    val holder = remember { mutableStateOf<HTMLElement?>(null) }
    LaunchedEffect(html) { holder.value?.innerHTML = html }
    Div({
        ref {
            holder.value = it
            onDispose { if (holder.value === it) holder.value = null }
        }
    }) {}
}

@Composable
fun PageTemplate(title: String?, showBack: Boolean = false, content: @Composable () -> Unit) {
    Div({ classes("container", "py-4") }) {
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
                    }) { I({ classes("bi", "bi-arrow-left") }) }
                }
                if (!title.isNullOrBlank()) {
                    H1({ classes("h4", "mb-0") }) { Text(title) }
                }
            }
        }
        content()
    }
}
