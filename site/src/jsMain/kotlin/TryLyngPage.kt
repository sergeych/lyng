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
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.*
import net.sergeych.lyng.Scope

@Composable
fun TryLyngPage() {
    val scope = rememberCoroutineScope()
    var code by remember {
        mutableStateOf(
            """
            // Welcome to Lyng! Edit and run.
            // Try changing the data and press Ctrl+Enter or click Run.
            import lyng.stdlib

            val data = [1, 2, 3, 4, 5]
            val evens = data.filter { it % 2 == 0 }.map { it * it }
            evens
            """.trimIndent()
        )
    }
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runCode() {
        if (running) return
        running = true
        output = null
        error = null
        scope.launch {
            try {
                // Create a fresh module scope each run so imports and vars are clean
                val s = Scope.new()
                // Capture printed output from Lyng `print`/`println` into the UI result window
                val printed = StringBuilder()
                s.addVoidFn("print") {
                    for ((i, a) in args.withIndex()) {
                        if (i > 0) printed.append(' ')
                        printed.append(a.toString(this).value)
                    }
                }
                s.addVoidFn("println") {
                    for ((i, a) in args.withIndex()) {
                        if (i > 0) printed.append(' ')
                        printed.append(a.toString(this).value)
                    }
                    printed.append('\n')
                }
                val result = s.eval(code)
                // Render with inspect for nice, user-facing representation
                val text = try {
                    result.inspect(s)
                } catch (_: Throwable) {
                    // Fallback if some object lacks inspect override
                    result.toString()
                }
                val combined = buildString {
                    if (printed.isNotEmpty()) append(printed)
                    // Always show the final expression value, like a REPL
                    if (isNotEmpty()) append('\n')
                    append(">>> ")
                    append(text)
                }
                output = combined
            } catch (t: Throwable) {
                // Show error, but also keep anything that has been printed so far
                error = t.message ?: t.toString()
            } finally {
                running = false
            }
        }
    }

    fun resetCode() {
        code = """
            // Welcome to Lyng! Edit and run.
            import lyng.stdlib
            [1,2,3].map { it * 10 }
        """.trimIndent()
        output = null
        error = null
    }

    PageTemplate(title = "Try Lyng", showBack = true) {
        // Intro
        P({ classes("lead", "text-muted", "mb-3") }) {
            Text("Type or paste Lyng code and run it right in your browser.")
        }

        // Editor
        Div({ classes("mb-3") }) {
            Div({ classes("form-label", "fw-semibold") }) { Text("Code") }
            TextArea(value = code, attrs = {
                classes("form-control", "font-monospace")
                attr("style", "min-height: 220px; tab-size: 2;")
                placeholder("Write some Lyng code…")
                onInput { ev -> code = ev.value }
                onKeyDown { ev ->
                    val ctrlEnter = (ev.ctrlKey || ev.metaKey) && ev.key == "Enter"
                    if (ctrlEnter) {
                        ev.preventDefault()
                        runCode()
                    }
                }
            })
        }

        // Actions
        Div({ classes("d-flex", "gap-2", "mb-3") }) {
            Button(attrs = {
                classes("btn", "btn-primary")
                if (running) attr("disabled", "disabled")
                onClick {
                    it.preventDefault()
                    runCode()
                }
            }) {
                I({ classes("bi", "bi-play-fill", "me-1") })
                Text(if (running) "Running…" else "Run")
            }

            Button(attrs = {
                classes("btn", "btn-outline-secondary")
                if (running) attr("disabled", "disabled")
                onClick {
                    it.preventDefault()
                    resetCode()
                }
            }) {
                I({ classes("bi", "bi-arrow-counterclockwise", "me-1") })
                Text("Reset")
            }
        }

        // Results
        if (error != null) {
            Div({ classes("alert", "alert-danger") }) {
                I({ classes("bi", "bi-exclamation-triangle-fill", "me-2") })
                Span({ classes("fw-semibold", "me-1") }) { Text("Error:") }
                Span { Text(" ${'$'}{error}") }
            }
        }

        if (output != null) {
            Div({ classes("card", "mb-3") }) {
                Div({ classes("card-header", "d-flex", "align-items-center", "gap-2") }) {
                    I({ classes("bi", "bi-terminal") })
                    Span({ classes("fw-semibold") }) { Text("Result") }
                }
                Div({ classes("card-body", "bg-body-tertiary") }) {
                    Pre({ classes("mb-0") }) { Code { Text(output!!) } }
                }
            }
        }

        // Tips
        P({ classes("text-muted", "small") }) {
            I({ classes("bi", "bi-info-circle", "me-1") })
            Text("Tip: press Ctrl+Enter (or ⌘+Enter on Mac) to run.")
        }
    }
}
