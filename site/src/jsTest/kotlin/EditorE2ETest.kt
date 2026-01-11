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

/*
 * End-to-end browser tests for the Try Lyng editor (Compose HTML + EditorWithOverlay)
 */
package net.sergeych.site

import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorE2ETest {

    // Utility to wait next animation frame in tests
    private suspend fun nextFrame() {
        js("return new Promise(requestAnimationFrame)").unsafeCast<kotlin.js.Promise<Unit>>().await()
    }

    // Programmatically type text into the textarea at current selection and dispatch an input event
    private fun typeText(ta: HTMLTextAreaElement, s: String) {
        for (ch in s) {
            val key = ch.toString()
            val ev = js("new KeyboardEvent('keydown', {key: key, bubbles: true, cancelable: true})")
            val wasPrevented = !ta.dispatchEvent(ev.unsafeCast<org.w3c.dom.events.Event>())

            if (!wasPrevented) {
                val start = ta.selectionStart ?: 0
                val end = ta.selectionEnd ?: start
                val before = ta.value.substring(0, start)
                val after = ta.value.substring(end)
                ta.value = before + ch + after
                val newPos = start + 1
                ta.selectionStart = newPos
                ta.selectionEnd = newPos
                // Fire input so EditorWithOverlay updates its state
                val inputEv = js("new Event('input', {bubbles:true})")
                ta.dispatchEvent(inputEv.unsafeCast<org.w3c.dom.events.Event>())
            }
        }
    }

    @Test
    fun shift_tab_outdents_rbrace_only_line_no_newline() = runTest {
        val root = document.createElement("div") as HTMLElement
        root.id = "test-root2"
        document.body!!.appendChild(root)

        val initial = """
            }
            3
        """.trimIndent()

        val state = mutableStateOf(initial)

        renderComposable(rootElementId = root.id) {
            net.sergeych.lyngweb.EditorWithOverlay(state.value, { s -> state.value = s })
            Text("")
        }

        // settle
        nextFrame(); nextFrame()

        val ta = (root.querySelector("textarea") as HTMLTextAreaElement?)
            ?: (document.querySelector("#${'$'}{root.id} textarea") as HTMLTextAreaElement?)
            ?: (document.querySelector("textarea") as HTMLTextAreaElement)
        // Put caret after the '}' (line 0, col 1)
        ta.selectionStart = 1
        ta.selectionEnd = 1
        // Shift+Tab
        dispatchKey(ta, key = "Tab", shift = true)
        nextFrame()

        val expected = """
            }
            3
        """.trimIndent()
        // After outdent, since there was no leading spaces, content should stay the same
        // but must not get an extra blank line
        val actual = ta.value
        assertEquals(expected.trimEnd('\n'), actual.trimEnd('\n'))
    }

    private fun dispatchKey(el: HTMLElement, key: String, shift: Boolean = false) {
        val ev = js("new KeyboardEvent('keydown', {key: key, shiftKey: shift, bubbles: true})")
        el.dispatchEvent(ev.unsafeCast<org.w3c.dom.events.Event>())
    }

    @Test
    fun enter_before_closing_brace_produces_expected_layout() = runTest {
        // Mount a root
        val root = document.createElement("div") as HTMLElement
        root.id = "test-root"
        document.body!!.appendChild(root)

        val initial = """
            {
                1
                2
                3
            }
            1
            2
            3
        """.trimIndent()

        val state = mutableStateOf(initial)

        renderComposable(rootElementId = root.id) {
            net.sergeych.lyngweb.EditorWithOverlay(state.value, { s -> state.value = s })
            // Keep composition alive
            Text("")
        }

        // Wait for composition to render
        nextFrame(); nextFrame()

        val ta = (root.querySelector("textarea") as HTMLTextAreaElement?)
            ?: (document.querySelector("#${'$'}{root.id} textarea") as HTMLTextAreaElement?)
            ?: (document.querySelector("textarea") as HTMLTextAreaElement)
        // Place caret at the end of the line containing the last "    3" before the brace
        val lines = ta.value.split('\n')
        var pos = 0
        for (i in lines.indices) {
            val line = lines[i]
            if (line.trimEnd() == "3" && i + 1 < lines.size && lines[i + 1].trim() == "}") {
                pos += line.length // end of this line
                break
            }
            pos += line.length + 1
        }
        ta.selectionStart = pos
        ta.selectionEnd = pos

        // Press Enter
        dispatchKey(ta, key = "Enter")

        // Allow compose state to propagate
        nextFrame(); nextFrame()

        val expected = """
            {
                1
                2
                3
            }
            1
            2
            3
        """.trimIndent()
        val actual = ta.value
        println("[DEBUG_LOG] Editor textarea after Enter:\n" + actual)
        // Allow a trailing newline at EOF (browser textareas often keep one)
        assertEquals(expected.trimEnd('\n'), actual.trimEnd('\n'))
    }

    @Test
    fun enter_between_braces_inserts_inner_line_e2e() = runTest {
        val root = document.createElement("div") as HTMLElement
        root.id = "test-root3"
        document.body!!.appendChild(root)

        val initial = "{}"
        val state = mutableStateOf(initial)

        renderComposable(rootElementId = root.id) {
            net.sergeych.lyngweb.EditorWithOverlay(state.value, { s: String -> state.value = s })
            Text("")
        }

        nextFrame(); nextFrame()

        val ta = (root.querySelector("textarea") as HTMLTextAreaElement?)
            ?: (document.querySelector("#${'$'}{root.id} textarea") as HTMLTextAreaElement?)
            ?: (document.querySelector("textarea") as HTMLTextAreaElement)

        // Place caret between braces
        ta.selectionStart = 1
        ta.selectionEnd = 1

        // Press Enter
        dispatchKey(ta, key = "Enter")
        nextFrame(); nextFrame()

        val expected = "{\n    \n}"
        assertEquals(expected, ta.value.trimEnd('\n'))
    }

    @Test
    fun example_sequence_from_rules_matches_expected() = runTest {
        // Mount root
        val root = document.createElement("div") as HTMLElement
        root.id = "test-root4"
        document.body!!.appendChild(root)

        val initial = ""
        val state = mutableStateOf(initial)

        renderComposable(rootElementId = root.id) {
            net.sergeych.lyngweb.EditorWithOverlay(state.value, { s: String -> state.value = s })
            Text("")
        }

        // Allow initial composition
        nextFrame(); nextFrame()

        val ta = (root.querySelector("textarea") as HTMLTextAreaElement?)
            ?: (document.querySelector("#${'$'}{root.id} textarea") as HTMLTextAreaElement?)
            ?: (document.querySelector("textarea") as HTMLTextAreaElement)

        // Ensure caret at end
        ta.selectionStart = ta.value.length
        ta.selectionEnd = ta.selectionStart

        // Perform the documented sequence: 1<Enter>2<Enter>{<Enter>3<Enter>4<Enter>}<Enter>5
        typeText(ta, "1"); nextFrame()
        dispatchKey(ta, key = "Enter"); nextFrame(); nextFrame()

        typeText(ta, "2"); nextFrame()
        dispatchKey(ta, key = "Enter"); nextFrame(); nextFrame()

        typeText(ta, "{"); nextFrame()
        dispatchKey(ta, key = "Enter"); nextFrame(); nextFrame()

        typeText(ta, "3"); nextFrame()
        dispatchKey(ta, key = "Enter"); nextFrame(); nextFrame()

        typeText(ta, "4"); nextFrame()
        dispatchKey(ta, key = "Enter"); nextFrame(); nextFrame()

        typeText(ta, "}"); nextFrame(); nextFrame(); nextFrame()
        dispatchKey(ta, key = "Enter"); nextFrame(); nextFrame()

        typeText(ta, "5"); nextFrame(); nextFrame(); nextFrame()

        val expected = (
            """
            1
            2
            {
                3
                4
            }
            5
            """
        ).trimIndent()

        val actual = ta.value.trimEnd('\n')
        assertEquals(expected, actual)
    }

    @Test
    fun enter_after_rbrace_with_only_spaces_to_eol_e2e() = runTest {
        // Mount root
        val root = document.createElement("div") as HTMLElement
        root.id = "test-rule5"
        document.body!!.appendChild(root)

        val initial = "    }   " // rbrace at indent 4, followed by 3 spaces until EOL
        val state = mutableStateOf(initial)

        renderComposable(rootElementId = root.id) {
            net.sergeych.lyngweb.EditorWithOverlay(state.value, { s: String -> state.value = s })
            Text("")
        }

        nextFrame(); nextFrame()

        val ta = (root.querySelector("textarea") as HTMLTextAreaElement?)
            ?: (document.querySelector("#${'$'}{root.id} textarea") as HTMLTextAreaElement?)
            ?: (document.querySelector("textarea") as HTMLTextAreaElement)

        // Place caret right after '}' (index 5)
        val braceIdx = ta.value.indexOf('}')
        ta.selectionStart = braceIdx + 1
        ta.selectionEnd = ta.selectionStart

        // Press Enter
        dispatchKey(ta, key = "Enter")
        nextFrame(); nextFrame()

        // Expect: brace line is dedented by one block (from 4 to 0), newline inserted AFTER it,
        // new line indent equals dedented indent (0), caret at start of the new blank line
        val expected = "}\n"
        val actual = ta.value.take(expected.length)
        kotlin.test.assertEquals(expected, actual)

        // Caret should be at start of newly inserted line (position expected.length)
        kotlin.test.assertEquals(expected.length, ta.selectionStart)
        kotlin.test.assertEquals(ta.selectionStart, ta.selectionEnd)
    }
}
