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

package net.sergeych.lyngio.docs

import net.sergeych.lyng.miniast.BuiltinDocRegistry
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.type

object ConsoleBuiltinDocs {
    private var registered = false

    fun ensure() {
        if (registered) return
        BuiltinDocRegistry.module("lyng.io.console") {
            classDoc(
                name = "Console",
                doc = "Console runtime API."
            ) {
                method(
                    name = "isSupported",
                    doc = "Whether console control API is supported on this platform.",
                    returns = type("lyng.Bool"),
                    isStatic = true
                )
                method(
                    name = "isTty",
                    doc = "Whether stdout is attached to an interactive TTY.",
                    returns = type("lyng.Bool"),
                    isStatic = true
                )
                method(
                    name = "ansiLevel",
                    doc = "Detected ANSI color capability: NONE, BASIC16, ANSI256, TRUECOLOR.",
                    returns = type("lyng.String"),
                    isStatic = true
                )
                method(
                    name = "geometry",
                    doc = "Current terminal geometry or null.",
                    returns = type("ConsoleGeometry", nullable = true),
                    isStatic = true
                )
                method(
                    name = "details",
                    doc = "Get consolidated console details.",
                    returns = type("ConsoleDetails"),
                    isStatic = true
                )
                method(
                    name = "write",
                    doc = "Write text directly to console output.",
                    params = listOf(ParamDoc("text", type("lyng.String"))),
                    isStatic = true
                )
                method(
                    name = "flush",
                    doc = "Flush console output buffer.",
                    isStatic = true
                )
                method(
                    name = "home",
                    doc = "Move cursor to home position (1,1).",
                    isStatic = true
                )
                method(
                    name = "clear",
                    doc = "Clear the visible screen buffer.",
                    isStatic = true
                )
                method(
                    name = "moveTo",
                    doc = "Move cursor to 1-based row and column.",
                    params = listOf(
                        ParamDoc("row", type("lyng.Int")),
                        ParamDoc("column", type("lyng.Int")),
                    ),
                    isStatic = true
                )
                method(
                    name = "clearLine",
                    doc = "Clear the current line.",
                    isStatic = true
                )
                method(
                    name = "enterAltScreen",
                    doc = "Switch to terminal alternate screen buffer.",
                    isStatic = true
                )
                method(
                    name = "leaveAltScreen",
                    doc = "Return from alternate screen buffer to normal screen.",
                    isStatic = true
                )
                method(
                    name = "setCursorVisible",
                    doc = "Show or hide the terminal cursor.",
                    params = listOf(ParamDoc("visible", type("lyng.Bool"))),
                    isStatic = true
                )
                method(
                    name = "events",
                    doc = "Endless iterable console event source (resize, keydown, keyup). Use in a loop, often inside launch.",
                    returns = type("ConsoleEventStream"),
                    isStatic = true
                )
                method(
                    name = "setRawMode",
                    doc = "Enable or disable raw keyboard mode. Returns true if mode changed.",
                    params = listOf(ParamDoc("enabled", type("lyng.Bool"))),
                    returns = type("lyng.Bool"),
                    isStatic = true
                )
            }
            classDoc(
                name = "ConsoleEventStream",
                doc = "Endless iterable stream of console events."
            ) {
                method(
                    name = "iterator",
                    doc = "Create an iterator over incoming console events.",
                    returns = type("lyng.Iterator")
                )
            }
            classDoc(
                name = "ConsoleGeometry",
                doc = "Terminal geometry."
            ) {
                field(
                    name = "columns",
                    doc = "Terminal width in character cells.",
                    type = type("lyng.Int")
                )
                field(
                    name = "rows",
                    doc = "Terminal height in character cells.",
                    type = type("lyng.Int")
                )
            }
            classDoc(
                name = "ConsoleDetails",
                doc = "Consolidated console capability details."
            ) {
                field(
                    name = "supported",
                    doc = "Whether console API is supported.",
                    type = type("lyng.Bool")
                )
                field(
                    name = "isTty",
                    doc = "Whether output is attached to a TTY.",
                    type = type("lyng.Bool")
                )
                field(
                    name = "ansiLevel",
                    doc = "Detected ANSI color capability.",
                    type = type("lyng.String")
                )
                field(
                    name = "geometry",
                    doc = "Current geometry or null.",
                    type = type("ConsoleGeometry", nullable = true)
                )
            }
            classDoc(
                name = "ConsoleEvent",
                doc = "Base class for console events."
            ) {
                field(
                    name = "type",
                    doc = "Event kind string.",
                    type = type("lyng.String")
                )
            }
            classDoc(
                name = "ConsoleResizeEvent",
                doc = "Resize event."
            ) {
                field(
                    name = "type",
                    doc = "Event kind string: resize.",
                    type = type("lyng.String")
                )
                field(
                    name = "columns",
                    doc = "Terminal width in character cells.",
                    type = type("lyng.Int")
                )
                field(
                    name = "rows",
                    doc = "Terminal height in character cells.",
                    type = type("lyng.Int")
                )
            }
            classDoc(
                name = "ConsoleKeyEvent",
                doc = "Keyboard event."
            ) {
                field(
                    name = "type",
                    doc = "Event kind string: keydown or keyup.",
                    type = type("lyng.String")
                )
                field(
                    name = "key",
                    doc = "Logical key name.",
                    type = type("lyng.String")
                )
                field(
                    name = "code",
                    doc = "Optional hardware code.",
                    type = type("lyng.String", nullable = true)
                )
                field(
                    name = "ctrl",
                    doc = "Ctrl modifier state.",
                    type = type("lyng.Bool")
                )
                field(
                    name = "alt",
                    doc = "Alt modifier state.",
                    type = type("lyng.Bool")
                )
                field(
                    name = "shift",
                    doc = "Shift modifier state.",
                    type = type("lyng.Bool")
                )
                field(
                    name = "meta",
                    doc = "Meta modifier state.",
                    type = type("lyng.Bool")
                )
            }

            valDoc(
                name = "Console",
                doc = "Console runtime API.",
                type = type("Console")
            )
            valDoc(name = "ConsoleGeometry", doc = "Terminal geometry class.", type = type("lyng.Class"))
            valDoc(name = "ConsoleDetails", doc = "Console details class.", type = type("lyng.Class"))
            valDoc(name = "ConsoleEvent", doc = "Base console event class.", type = type("lyng.Class"))
            valDoc(name = "ConsoleResizeEvent", doc = "Resize event class.", type = type("lyng.Class"))
            valDoc(name = "ConsoleKeyEvent", doc = "Keyboard event class.", type = type("lyng.Class"))
            valDoc(name = "ConsoleEventStream", doc = "Iterable console event stream class.", type = type("lyng.Class"))
        }
        registered = true
    }
}
