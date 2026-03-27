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

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.*

@Composable
fun AuthorsPage() {
    Div({ classes("text-center", "mb-4") }) {
        P({
            classes("lead", "text-muted", "mb-0", "mx-auto")
            attr("style", "max-width: 42rem;")
        }) {
            Text("Designed and built with care. Special thanks to everyone around Lyng who helped shape the language, the tools, and the site into what it is now.")
        }
    }

    Div({ classes("row", "g-4", "justify-content-center") }) {
        listOf(
            Triple(
                "Sergey Chernov",
                "Initial idea and architecture, language concept, design, implementation.",
                Pair("@sergeych", "https://www.gravatar.com/avatar/7e3a56ff8a090fc9ffbd1909dea94904?s=128&d=identicon")
            ),
            Triple(
                "Yulia Nezhinskaya",
                "System analysis, math and feature design.",
                Pair("@AlterEgoJuliaN", "https://www.gravatar.com/avatar/53a90bca30c85a81db8f0c0d8dea43a1?s=128&d=identicon")
            )
        ).forEach { (name, bio, profile) ->
            Div({ classes("col-12", "col-lg-5") }) {
                Div({ classes("h-100", "p-4", "border", "rounded-4", "bg-body-tertiary", "text-center") }) {
                    Img(
                        src = profile.second,
                        alt = name,
                        attrs = {
                            classes("rounded-circle", "mb-3")
                            attr("width", "88")
                            attr("height", "88")
                        }
                    )
                    H2({ classes("h4", "mb-1") }) { Text(name) }
                    P({ classes("text-primary", "small", "mb-2") }) { Text(profile.first) }
                    P({ classes("text-muted", "mb-0") }) { Text(bio) }
                }
            }
        }
    }
}
