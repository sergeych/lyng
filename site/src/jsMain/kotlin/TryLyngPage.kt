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
import kotlinx.coroutines.launch
import net.sergeych.lyng.LyngVersion
import net.sergeych.lyng.Script
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.highlight.TextRange
import net.sergeych.lyng.miniast.CompletionItem
import net.sergeych.lyng.requireScope
import net.sergeych.lyng.tools.LyngDiagnostic
import net.sergeych.lyng.tools.LyngDiagnosticSeverity
import net.sergeych.lyng.tools.LyngSymbolInfo
import net.sergeych.lyng.tools.LyngSymbolTarget
import net.sergeych.lyngweb.EditorWithOverlay
import net.sergeych.lyngweb.LyngWebTools
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.*

@Composable
fun TryLyngPage(route: String) {
    val scope = rememberCoroutineScope()
    val initialCode = remember(route) {
        val params = route.substringAfter('?', "")
        val codeParam = params.split('&').find { it.startsWith("code=") }?.substringAfter('=')
        if (codeParam != null) {
            try {
                decodeURIComponent(codeParam)
            } catch (_: Throwable) {
                null
            }
        } else null
    }
    var code by remember(initialCode) {
        mutableStateOf(
            initialCode ?: """
            // Welcome to Lyng! Modern scripting with strict types and generics.

            type Numeric = Int | Real

            fun process<T: Numeric>(items: List<T>): List<T> {
                items.filter { it > 0 }.map { it * it }
            }

            val data = [-2, -1, 0, 1, 2]
            println("Processed: " + process(data))

            // Try changing data or adding Real numbers!
            """.trimIndent()
        )
    }
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var extendedError by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<List<LyngDiagnostic>>(emptyList()) }
    var completionItems by remember { mutableStateOf<List<CompletionItem>>(emptyList()) }
    var completionOffset by remember { mutableStateOf<Int?>(null) }
    var docInfo by remember { mutableStateOf<LyngSymbolInfo?>(null) }
    var definitionTarget by remember { mutableStateOf<LyngSymbolTarget?>(null) }
    var usageRanges by remember { mutableStateOf<List<TextRange>>(emptyList()) }
    var disasmSymbol by remember { mutableStateOf<String>("") }
    var disasmOutput by remember { mutableStateOf<String?>(null) }
    var disasmError by remember { mutableStateOf<String?>(null) }

    fun runCode() {
        if (running) return
        running = true
        output = null
        error = null
        extendedError = null
        completionItems = emptyList()
        completionOffset = null
        docInfo = null
        definitionTarget = null
        usageRanges = emptyList()
        diagnostics = emptyList()
        disasmOutput = null
        disasmError = null
        scope.launch {
            // keep this outside try so we can show partial prints if evaluation fails
            val printed = StringBuilder()
            try {
                // Create a fresh module scope each run so imports and vars are clean
                val s = Script.newScope()
                // Capture printed output from Lyng `print`/`println` into the UI result window
                s.addVoidFn("print") {
                    for ((i, a) in this.args.withIndex()) {
                        if (i > 0) printed.append(' ')
                        printed.append(a.toString(this.requireScope()).value)
                    }
                }
                s.addVoidFn("println") {
                    for ((i, a) in this.args.withIndex()) {
                        if (i > 0) printed.append(' ')
                        printed.append(a.toString(this.requireScope()).value)
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
                // Prefer detailed message including stack if available (K/JS)
                val errText = buildString {
                    append(t.toString())
                    if (t !is ScriptError) {
                        try {
                            val st = t.asDynamic().stack as? String
                            if (!st.isNullOrBlank()) {
                                append("\n")
                                append(st)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (printed.isNotEmpty()) {
                    output = printed.toString()
                }
                if (t is ScriptError) {
                    error = t.errorMessage
                    extendedError = errText
                } else {
                    error = t.message
                    extendedError = errText
                }
            } finally {
                running = false
            }
        }
    }

    fun resetCode() {
        code = initialCode ?: """
            // Welcome to Lyng! Modern scripting with strict types and generics.
            [1, 2, 3].map { it * 10 }
        """.trimIndent()
        output = null
        error = null
    }

    PageTemplate(title = "Try Lyng", showBack = true) {
        // Intro
        P({ classes("lead", "text-muted", "mb-3") }) {
            Text("Type or paste Lyng code and run it right in your browser with embedded Lyng interpreter")
        }

        // Editor
        Div({ classes("mb-3") }) {
            Div({ classes("form-label", "fw-semibold") }) { Text("Code (v${LyngVersion})") }
            EditorWithOverlay(
                code = code,
                setCode = { code = it },
                onKeyDown = { ev ->
                    val key = ev.key
                    val ctrlOrMeta = ev.ctrlKey || ev.metaKey
                    if (ctrlOrMeta && key.lowercase() == "enter") {
                        ev.preventDefault()
                        runCode()
                    }
                },
                onAnalysisReady = { analysis ->
                    diagnostics = analysis.diagnostics
                },
                onCompletionRequested = { offset, items ->
                    completionOffset = offset
                    completionItems = items
                },
                onDefinitionResolved = { _, target ->
                    definitionTarget = target
                },
                onUsagesResolved = { _, ranges ->
                    usageRanges = ranges
                },
                onDocRequested = { _, info ->
                    docInfo = info
                },
                // Keep current initial size but allow the editor to grow with content
                autoGrow = true
            )
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
                // Show actual error text (previously printed the literal template)
                Span { Text(error!!) }
            }
        }

        if (output != null || error != null) {
            Div({ classes("card", "mb-3") }) {
                Div({ classes("card-header", "d-flex", "align-items-center", "gap-2") }) {
                    I({ classes("bi", "bi-terminal") })
                    Span({ classes("fw-semibold") }) { Text("Result") }
                }
                Div({ classes("card-body", "bg-body-tertiary") }) {
                    if (output != null) {
                        Pre({ classes("mb-0") }) { Code { Text(output!!) } }
                    }
                    if (extendedError != null) {
                        if (output != null) Hr({})
                        Div({ classes("alert", "alert-danger", "mb-0") }) {
                            Pre({ classes("mb-0") }) { Code { Text(extendedError!!) } }
                        }
                    }
                }
            }
        }

        // Language tools quick view
        Div({ classes("card", "mb-3") }) {
            Div({ classes("card-header", "d-flex", "align-items-center", "gap-2") }) {
                I({ classes("bi", "bi-diagram-3") })
                Span({ classes("fw-semibold") }) { Text("Language tools") }
            }
            Div({ classes("card-body") }) {
                Div({ classes("mb-3") }) {
                    Span({ classes("fw-semibold", "me-2") }) { Text("Diagnostics") }
                    if (diagnostics.isEmpty()) {
                        Span({ classes("text-muted") }) { Text("No errors or warnings.") }
                    } else {
                        Ul({ classes("mb-0") }) {
                            diagnostics.forEach { d ->
                                Li {
                                    val sev = when (d.severity) {
                                        LyngDiagnosticSeverity.Error -> "Error"
                                        LyngDiagnosticSeverity.Warning -> "Warning"
                                    }
                                    val range = d.range?.let { " @${it.start}-${it.endExclusive}" } ?: ""
                                    Text("$sev: ${d.message}$range")
                                }
                            }
                        }
                    }
                }

                Div({ classes("mb-3") }) {
                    Span({ classes("fw-semibold", "me-2") }) { Text("Quick docs") }
                    if (docInfo == null) {
                        Span({ classes("text-muted") }) { Text("Press Ctrl+Q (or ⌘+Q) on a symbol.") }
                    } else {
                        val info = docInfo!!
                        Div({ classes("small") }) {
                            Text("${info.target.kind} ${info.target.name}")
                            info.signature?.let { sig ->
                                Br()
                                Code { Text(sig) }
                            }
                            info.doc?.summary?.let { doc ->
                                Br()
                                Text(doc)
                            }
                        }
                    }
                }

                Div({ classes("mb-3") }) {
                    Span({ classes("fw-semibold", "me-2") }) { Text("Definition") }
                    if (definitionTarget == null) {
                        Span({ classes("text-muted") }) { Text("Press Ctrl+B (or ⌘+B) on a symbol.") }
                    } else {
                        val def = definitionTarget!!
                        Span({ classes("small") }) {
                            Text("${def.kind} ${def.name} @${def.range.start}-${def.range.endExclusive}")
                        }
                    }
                }

                Div({ classes("mb-3") }) {
                    Span({ classes("fw-semibold", "me-2") }) { Text("Usages") }
                    if (usageRanges.isEmpty()) {
                        Span({ classes("text-muted") }) { Text("Press Ctrl+Shift+U (or ⌘+Shift+U) on a symbol.") }
                    } else {
                        Span({ classes("small") }) { Text("${usageRanges.size} usage(s) found.") }
                    }
                }

                Div({ classes("mb-0") }) {
                    Span({ classes("fw-semibold", "me-2") }) { Text("Completions") }
                    if (completionItems.isEmpty()) {
                        Span({ classes("text-muted") }) { Text("Press Ctrl+Space (or ⌘+Space).") }
                    } else {
                        val shown = completionItems.take(8)
                        Span({ classes("text-muted", "small", "ms-1") }) {
                            completionOffset?.let { Text("@$it") }
                        }
                        Ul({ classes("mb-0") }) {
                            shown.forEach { item ->
                                Li { Text("${item.name} (${item.kind})") }
                            }
                        }
                        if (completionItems.size > shown.size) {
                            Span({ classes("text-muted", "small") }) {
                                Text("…and ${completionItems.size - shown.size} more")
                            }
                        }
                    }
                }
            }
        }

        // Disassembly
        Div({ classes("card", "mb-3") }) {
            Div({ classes("card-header", "d-flex", "align-items-center", "gap-2") }) {
                I({ classes("bi", "bi-braces") })
                Span({ classes("fw-semibold") }) { Text("Disassembly") }
            }
            Div({ classes("card-body") }) {
                Div({ classes("d-flex", "gap-2", "align-items-center", "mb-2") }) {
                    Input(type = InputType.Text, attrs = {
                        classes("form-control")
                        attr("placeholder", "Symbol (e.g., MyClass.method or topLevelFun)")
                        value(disasmSymbol)
                        onInput { ev ->
                            disasmSymbol = ev.value
                        }
                    })
                    Button(attrs = {
                        classes("btn", "btn-outline-primary")
                        if (disasmSymbol.isBlank()) attr("disabled", "disabled")
                        onClick {
                            it.preventDefault()
                            val symbol = disasmSymbol.trim()
                            if (symbol.isEmpty()) return@onClick
                            disasmOutput = null
                            disasmError = null
                            scope.launch {
                                try {
                                    disasmOutput = LyngWebTools.disassembleSymbol(code, symbol)
                                } catch (t: Throwable) {
                                    disasmError = t.message ?: t.toString()
                                }
                            }
                        }
                    }) { Text("Disassemble") }
                }
                if (disasmError != null) {
                    Div({ classes("alert", "alert-danger", "py-2", "mb-2") }) { Text(disasmError!!) }
                }
                if (disasmOutput != null) {
                    Pre({ classes("mb-0") }) { Code { Text(disasmOutput!!) } }
                } else if (disasmError == null) {
                    Span({ classes("text-muted", "small") }) {
                        Text("Uses the bytecode compiler; not a dry run.")
                    }
                }
            }
        }

        // Tips
        P({ classes("text-muted", "small") }) {
            I({ classes("bi", "bi-info-circle", "me-1") })
            Text("Tip: Ctrl+Enter runs, Ctrl+Space completes, Ctrl+B jumps to definition, Ctrl+Shift+U finds usages, Ctrl+Q shows docs.")
        }
    }
}
