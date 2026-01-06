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
import net.sergeych.lyngweb.ensureBootstrapCodeBlocks
import net.sergeych.lyngweb.highlightLyngHtml
import net.sergeych.lyngweb.htmlEscape
import org.jetbrains.compose.web.dom.*

@Composable
fun HomePage() {
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
                    "both FP and OOP",
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
                    classes("btn", "btn-outline-info", "btn-lg")
                    attr("href", "#/reference")
                }) {
                    I({ classes("bi", "bi-journal-text", "me-1") })
                    Text("Browse reference")
                }
                A(attrs = {
                    classes("btn", "btn-success", "btn-lg")
                    // Use the hash path requested by the user: "#tryling"
                    attr("href", "#tryling")
                }) {
                    I({ classes("bi", "bi-braces", "me-1") })
                    Text("Try Lyng")
                }
                // (Telegram button moved to the bottom of the page)
            }
        }
    }

    // Code sample
    val code = """
// Create, transform, and verify — the Lyng way
import lyng.stdlib

val data = 1..5 // or [1,2,3,4,5]
val evens2 = data.filter { it % 2 == 0 }.map { it * it }
assertEquals([4, 16], evens2)

// Map literal with identifier keys, shorthand, and spread
val base = { a: 1, b: 2 }
val patch = { b: 3, c: }
val m = { "a": 0, ...base, ...patch, d: 4 }
assertEquals(1, m["a"]) // base overwrites 0

// Object expressions: anonymous classes on the fly
val worker = object : Runnable {
    override fun run() = println("Working...")
}
worker.run()
>>> void
""".trimIndent()
    val mapHtml = "<pre><code>" + htmlEscape(code) + "</code></pre>"
    Div({ classes("markdown-body", "mt-3") }) {
        UnsafeRawHtml(highlightLyngHtml(ensureBootstrapCodeBlocks(mapHtml)))
    }

    // Short features list
    Div({ classes("row", "g-4", "mt-1") }) {
        listOf(
            Triple("Fast to learn", "Familiar constructs and readable patterns — be productive in minutes.", "lightning"),
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

    // Bottom section with a small Telegram button
    Div({ classes("text-center", "mt-5", "pb-4") }) {
        A(attrs = {
            classes("btn", "btn-outline-primary", "btn-sm")
            attr("href", "https://t.me/lynglang")
            attr("target", "_blank")
            attr("rel", "noopener noreferrer")
        }) {
            I({ classes("bi", "bi-telegram", "me-1") })
            Text("Join our Telegram channel")
        }
    }
}
