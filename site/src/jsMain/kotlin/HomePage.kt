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
            // Decimal arithmetic with explicit local precision
            import lyng.decimal

            val exact = "0.3".d
            val fromReal = (0.1 + 0.2).d

            println(exact.toStringExpanded())
            println(fromReal.toStringExpanded())
            println(withDecimalContext(10) { (1.d / 3.d).toStringExpanded() })
            """.trimIndent(),
            """
            // Mixed operators for your own type
            import lyng.operators

            class Coins(val amount: Int) {
                fun plus(other: Coins) = Coins(amount + other.amount)
                fun compareTo(other: Coins) = amount <=> other.amount
            }

            OperatorInterop.register(
                Int,
                Coins,
                Coins,
                [BinaryOperator.Plus, BinaryOperator.Compare, BinaryOperator.Equals],
                { x: Int -> Coins(x) },
                { x: Coins -> x }
            )

            println(1 + Coins(2))
            println(3 > Coins(2))
            """.trimIndent(),
            """
            // Non-local returns from closures
            fun findFirst<T>(list: Iterable<T>, predicate: (T)->Bool): T? {
                list.forEach {
                    if (predicate(it)) return@findFirst it
                }
                null
            }

            val found: Int? = findFirst([1, 5, 8, 12]) { it > 10 }
            println("Found: " + found)
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
            """.trimIndent(),
            """
            // Everything is an expression
            val x: Int = 10
            val status: String = if (x > 0) "Positive" else "Zero or Negative"

            val result = for (i in 1..5) {
                if (i == 3) break "Found 3!"
            } else "Not found"

            println(status)
            println(result)
            """.trimIndent(),
            """
            // Functional collections with strict static types
            val squares: List<Int> = (1..10)
                .filter { it % 2 == 0 }
                .map { it * it }

            println("Even squares: " + squares)
            """.trimIndent(),
            """
            // Diamond-safe Multiple Inheritance (C3 MRO)
            interface Logger {
                fun log(m: String) = println("[LOG] " + m)
            }
            interface Auth {
                fun login(u: String) = println("Login: " + u)
            }

            class App() : Logger, Auth {
                override fun run() {
                    log("Starting...")
                    login("admin")
                }
            }
            App().run()
            """.trimIndent(),
            """
            // Extension functions and properties
            fun String.shout(): String = this.uppercase() + "!!!"

            println("hello".shout())

            val List<T>.second: T get() = this[1]
            println([10, 20, 30].second)
            """.trimIndent(),
            """
            // Easy operator overloading
            class Vector(val x: Real, val y: Real) {
                fun plus(other: Vector): Vector = Vector(x + other.x, y + other.y)
                override fun toString(): String = "Vector(%g, %g)"(x, y)
            }

            val v1 = Vector(1, 2)
            val v2 = Vector(3, 4)
            println(v1 + v2)
            """.trimIndent(),
            """
            // Property delegation to Map
            class User() {
                var name: String by Map()
            }

            val u = User()
            u.name = "Sergey"
            println("User name: " + u.name)
            """.trimIndent(),
            """
            // Flexible map literals and shorthands
            val id = 101
            val name = "Lyng"
            val base = { id:, name: }
            val full = { ...base, version: "1.5.4", status: "stable", tags: ["typed", "portable"] }

            println(full)
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
                Text("A lightweight but extremely powerful and expressive scripting language with strict static typing and functional power. ")
                Br()
                Text("Run it anywhere Kotlin runs — share logic across JS, JVM, Native and more.")
            }
            Div({ classes("d-flex", "justify-content-center", "gap-2", "flex-wrap", "mb-4") }) {
                // Benefits pills
                listOf(
                    "Decimal arithmetic",
                    "Operator interop",
                    "Strict static typing",
                    "Generics & Type Aliases",
                    "Implicit coroutines",
                    "both FP and OOP",
                    "Batteries-included standard library"
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

    Div({ classes("row", "g-3", "mb-4") }) {
        listOf(
            Triple("New: Decimal", "Exact decimal values, local precision control, and mixed operators with Int and Real.", "#/docs/Decimal.md"),
            Triple("New: Operator Interop", "Teach Lyng how your custom types interact with built-ins on the left-hand side.", "#/docs/OperatorInterop.md"),
            Triple("What Changed", "Recent language, stdlib, and tooling improvements in one place.", "#/docs/whats_new.md")
        ).forEach { (title, text, href) ->
            Div({ classes("col-12", "col-lg-4") }) {
                A(attrs = {
                    classes("text-decoration-none")
                    attr("href", href)
                }) {
                    Div({ classes("h-100", "p-3", "border", "rounded-3", "bg-body-tertiary") }) {
                        H3({ classes("h5", "mb-2", "text-body") }) { Text(title) }
                        P({ classes("mb-0", "text-muted") }) { Text(text) }
                    }
                }
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
            Triple("Safe and Robust", "Strict compile-time resolution, generics, and null safety catch errors early.", "shield-check"),
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

    // Bottom section with Telegram and authors links
    Div({ classes("text-center", "mt-5", "d-flex", "justify-content-center", "gap-2", "flex-wrap") }) {
        A(attrs = {
            classes("btn", "btn-outline-primary", "btn-sm")
            attr("href", "https://t.me/lynglang")
            attr("target", "_blank")
            attr("rel", "noopener noreferrer")
        }) {
            I({ classes("bi", "bi-telegram", "me-1") })
            Text("Join our Telegram channel")
        }
        A(attrs = {
            classes("btn", "btn-outline-secondary", "btn-sm")
            attr("href", "#/authors")
        }) {
            I({ classes("bi", "bi-people", "me-1") })
            Text("Meet the authors")
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
