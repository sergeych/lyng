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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable

fun main() {
    renderComposable(rootElementId = "root") {
        // Minimal SPA shell
        Div({ classes("container", "py-4") }) {
            H1({ classes("display-6", "mb-3") }) { Text("Compose HTML SPA") }
            P({ classes("lead") }) {
                Text("This static site is powered by Compose for Web (JS-only) and Bootstrap 5.3.")
            }

            Hr()

            // Example of interactive state to show SPA behavior
            var count by remember { mutableStateOf(0) }
            Div({ classes("d-flex", "gap-2", "align-items-center") }) {
                Button(attrs = {
                    classes("btn", "btn-primary")
                    onClick { count++ }
                }) { Text("Increment") }
                Span({ classes("fw-bold") }) { Text("Count: $count") }
            }
        }
    }
}
