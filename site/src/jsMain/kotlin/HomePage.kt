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
import kotlinx.coroutines.delay
import net.sergeych.lyngweb.ensureBootstrapCodeBlocks
import net.sergeych.lyngweb.highlightLyngHtml
import net.sergeych.lyngweb.htmlEscape
import org.jetbrains.compose.web.dom.*

@Composable
fun HomePage() {
    val samples = remember {
        listOf(
            """
            // Everything is an expression
            val x = 10
            val status = if (x > 0) "Positive" else "Zero or Negative"

            // Even loops return values!
            val result = for (i in 1..5) {
                if (i == 3) break "Found 3!"
            } else "Not found"

            println("Result: " + result)
            """.trimIndent(),
            """
            // Functional power with ranges and collections
            val squares = (1..10)
                .filter { it % 2 == 0 }
                .map { it * it }

            println("Even squares: " + squares)
            // Output: [4, 16, 36, 64, 100]
            """.trimIndent(),
            """
            // Flexible map literals and shorthands
            val id = 101
            val name = "Lyng"
            val base = { id:, name: } // Shorthand for id: id, name: name

            val full = { ...base, version: "1.0", status: "active" }
            println(full)
            """.trimIndent(),
            """
            // Modern null safety
            var config = null
            config ?= { timeout: 30 } // Assign only if null

            val timeout = config?.timeout ?: 60
            println("Timeout is: " + timeout)
            """.trimIndent(),
            """
            // Destructuring with splat operator
            val [first, middle..., last] = [1, 2, 3, 4, 5, 6]

            println("First: " + first)
            println("Middle: " + middle)
            println("Last: " + last)
            """.trimIndent(),
            """
            // Diamond-safe Multiple Inheritance (C3 MRO)
            interface Logger {
                fun log(m) = println("[LOG] " + m)
            }
            interface Auth {
                fun login(u) = println("Login: " + u)
            }

            class App() : Logger, Auth {
                fun run() {
                    log("Starting...")
                    login("admin")
                }
            }
            App().run()
            """.trimIndent(),
            """
            // Extension functions and properties
            fun String.shout() = this.toUpperCase() + "!!!"

            println("hello".shout())

            val List.second get = this[1]
            println([10, 20, 30].second)
            """.trimIndent(),
            """
            // Non-local returns from closures
            fun findFirst(list, predicate) {
                list.forEach {
                    if (predicate(it)) return@findFirst it
                }
                null
            }

            val found = findFirst([1, 5, 8, 12]) { it > 10 }
            println("Found: " + found)
            """.trimIndent(),
            """
            // Easy operator overloading
            class Vector(val x, val y) {
                override fun plus(other) = Vector(x + other.x, y + other.y)
                override fun toString() = "Vector(%g, %g)"(x, y)
            }

            val v1 = Vector(1, 2)
            val v2 = Vector(3, 4)
            println(v1 + v2)
            """.trimIndent(),
            """
            // Property delegation to Map
            class User() {
                var name by Map()
            }

            val u = User()
            u.name = "Sergey"
            println("User name: " + u.name)
            """.trimIndent(),
            """
            // Implicit coroutines: parallelism without ceremony
            import lyng.time

            val d1 = launch {
                delay(100.milliseconds)
                "Task A finished"
            }
            val d2 = launch {
                delay(50.milliseconds)
                "Task B finished"
            }

            println(d1.await())
            println(d2.await())
            """.trimIndent()
        )
    }

    var currentSlide by remember { mutableStateOf((samples.indices).random()) }

    LaunchedEffect(Unit) {
        ensureSlideshowStyles()
        while (true) {
            delay(7000)
            currentSlide = (currentSlide + 1) % samples.size
        }
    }
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
            Div({ classes("d-flex", "justify-content-center", "gap-2", "mb-2") }) {
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

    // Code sample slideshow
    Div({
        classes("markdown-body", "mt-0", "slide-container", "position-relative")
        onClick {
            window.location.hash = "#tryling?code=" + encodeURIComponent(samples[currentSlide])
        }
    }) {
        Small({
            classes("position-absolute", "top-0", "end-0", "m-2", "text-muted", "fw-light", "try-hint")
        }) {
            Text("click to try")
        }
        key(currentSlide) {
            val slideCode = samples[currentSlide]
            val mapHtml = "<pre><code>" + htmlEscape(slideCode) + "</code></pre>"
            Div({ classes("slide-animation") }) {
                UnsafeRawHtml(highlightLyngHtml(ensureBootstrapCodeBlocks(mapHtml)))
            }
        }
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

fun ensureSlideshowStyles() {
    if (window.document.getElementById("slideshow-styles") != null) return
    val style = window.document.createElement("style") as org.w3c.dom.HTMLStyleElement
    style.id = "slideshow-styles"
    style.textContent = """
        @keyframes slideIn {
            from { opacity: 0; transform: translateX(30px); }
            to { opacity: 1; transform: translateX(0); }
        }
        .slide-animation {
            animation: slideIn 0.4s ease-out;
        }
        .slide-container {
            min-height: 320px;
            cursor: pointer;
            transition: transform 0.2s;
            background-color: var(--bs-body-bg);
        }
        .slide-container:hover {
            transform: scale(1.005);
        }
        .try-hint {
            opacity: 0.5;
            transition: opacity 0.2s;
            pointer-events: none;
        }
        .slide-container:hover .try-hint {
            opacity: 1;
        }
    """.trimIndent()
    window.document.head!!.appendChild(style)
}
