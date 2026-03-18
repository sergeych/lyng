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

package net.sergeych.lyng.io.console

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.miniast.*
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjIterable
import net.sergeych.lyng.obj.ObjIterationFinishedException
import net.sergeych.lyng.obj.ObjIterator
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.obj.thisAs
import net.sergeych.lyng.obj.toObj
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.raiseIllegalOperation
import net.sergeych.lyng.requireScope
import net.sergeych.lyngio.console.ConsoleEvent
import net.sergeych.lyngio.console.ConsoleEventSource
import net.sergeych.lyngio.console.LyngConsole
import net.sergeych.lyngio.console.getSystemConsole
import net.sergeych.lyngio.console.security.ConsoleAccessDeniedException
import net.sergeych.lyngio.console.security.ConsoleAccessPolicy
import net.sergeych.lyngio.console.security.LyngConsoleSecured

/**
 * Install Lyng module `lyng.io.console` into the given scope's ImportManager.
 */
fun createConsoleModule(policy: ConsoleAccessPolicy, scope: Scope): Boolean =
    createConsoleModule(policy, scope.importManager)

fun createConsole(policy: ConsoleAccessPolicy, scope: Scope): Boolean = createConsoleModule(policy, scope)

/** Same as [createConsoleModule] but with explicit [ImportManager]. */
fun createConsoleModule(policy: ConsoleAccessPolicy, manager: ImportManager): Boolean {
    val name = "lyng.io.console"
    if (manager.packageNames.contains(name)) return false

    manager.addPackage(name) { module ->
        buildConsoleModule(module, policy)
    }
    return true
}

fun createConsole(policy: ConsoleAccessPolicy, manager: ImportManager): Boolean = createConsoleModule(policy, manager)

private suspend fun buildConsoleModule(module: ModuleScope, policy: ConsoleAccessPolicy) {
    val console: LyngConsole = LyngConsoleSecured(getSystemConsole(), policy)

    val consoleType = object : net.sergeych.lyng.obj.ObjClass("Console") {}

    consoleType.apply {
        addClassFnDoc(
            name = "isSupported",
            doc = "Whether console control API is supported on this platform.",
            returns = type("lyng.Bool"),
            moduleName = module.packageName
        ) {
            ObjBool(console.isSupported)
        }

        addClassFnDoc(
            name = "isTty",
            doc = "Whether current stdout is attached to an interactive TTY.",
            returns = type("lyng.Bool"),
            moduleName = module.packageName
        ) {
            consoleGuard {
                ObjBool(console.isTty())
            }
        }

        addClassFnDoc(
            name = "ansiLevel",
            doc = "Detected ANSI color capability: NONE, BASIC16, ANSI256, TRUECOLOR.",
            returns = type("lyng.String"),
            moduleName = module.packageName
        ) {
            consoleGuard {
                ObjString(console.ansiLevel().name)
            }
        }

        addClassFnDoc(
            name = "geometry",
            doc = "Current terminal geometry or null.",
            returns = type("ConsoleGeometry", nullable = true),
            moduleName = module.packageName
        ) {
            consoleGuard {
                console.geometry()?.let { ObjConsoleGeometry(it.columns, it.rows) } ?: ObjNull
            }
        }

        addClassFnDoc(
            name = "details",
            doc = "Get consolidated console details.",
            returns = type("ConsoleDetails"),
            moduleName = module.packageName
        ) {
            consoleGuard {
                val tty = console.isTty()
                val ansi = console.ansiLevel()
                val geometry = console.geometry()
                ObjConsoleDetails(
                    supported = console.isSupported,
                    isTty = tty,
                    ansiLevel = ansi.name,
                    geometry = geometry?.let { ObjConsoleGeometry(it.columns, it.rows) },
                )
            }
        }

        addClassFnDoc(
            name = "write",
            doc = "Write text directly to console output.",
            params = listOf(ParamDoc("text", type("lyng.String"))),
            moduleName = module.packageName
        ) {
            consoleGuard {
                val text = requiredArg<ObjString>(0).value
                console.write(text)
                ObjVoid
            }
        }

        addClassFnDoc(
            name = "flush",
            doc = "Flush console output buffer.",
            moduleName = module.packageName
        ) {
            consoleGuard {
                console.flush()
                ObjVoid
            }
        }

        addClassFnDoc(
            name = "home",
            doc = "Move cursor to home position (1,1).",
            moduleName = module.packageName
        ) {
            consoleGuard {
                console.write("\u001B[H")
                ObjVoid
            }
        }

        addClassFnDoc(
            name = "clear",
            doc = "Clear the visible screen buffer.",
            moduleName = module.packageName
        ) {
            consoleGuard {
                console.write("\u001B[2J")
                ObjVoid
            }
        }

        addClassFnDoc(
            name = "moveTo",
            doc = "Move cursor to 1-based row and column.",
            params = listOf(
                ParamDoc("row", type("lyng.Int")),
                ParamDoc("column", type("lyng.Int")),
            ),
            moduleName = module.packageName
        ) {
            consoleGuard {
                val row = requiredArg<net.sergeych.lyng.obj.ObjInt>(0).value
                val col = requiredArg<net.sergeych.lyng.obj.ObjInt>(1).value
                console.write("\u001B[${row};${col}H")
                ObjVoid
            }
        }

        addClassFnDoc(
            name = "clearLine",
            doc = "Clear the current line.",
            moduleName = module.packageName
        ) {
            consoleGuard {
                console.write("\u001B[2K")
                ObjVoid
            }
        }

        addClassFnDoc(
            name = "enterAltScreen",
            doc = "Switch to terminal alternate screen buffer.",
            moduleName = module.packageName
        ) {
            consoleGuard {
                console.write("\u001B[?1049h")
                ObjVoid
            }
        }

        addClassFnDoc(
            name = "leaveAltScreen",
            doc = "Return from alternate screen buffer to normal screen.",
            moduleName = module.packageName
        ) {
            consoleGuard {
                console.write("\u001B[?1049l")
                ObjVoid
            }
        }

        addClassFnDoc(
            name = "setCursorVisible",
            doc = "Show or hide the terminal cursor.",
            params = listOf(ParamDoc("visible", type("lyng.Bool"))),
            moduleName = module.packageName
        ) {
            consoleGuard {
                val visible = requiredArg<ObjBool>(0).value
                console.write(if (visible) "\u001B[?25h" else "\u001B[?25l")
                ObjVoid
            }
        }

        addClassFnDoc(
            name = "events",
            doc = "Endless iterable console event source (resize, keydown, keyup). Use in a loop, often inside launch.",
            returns = type("ConsoleEventStream"),
            moduleName = module.packageName
        ) {
            consoleGuard {
                console.events().toConsoleEventStream()
            }
        }

        addClassFnDoc(
            name = "setRawMode",
            doc = "Enable or disable raw keyboard mode. Returns true if mode changed.",
            params = listOf(ParamDoc("enabled", type("lyng.Bool"))),
            returns = type("lyng.Bool"),
            moduleName = module.packageName
        ) {
            consoleGuard {
                val enabled = requiredArg<ObjBool>(0).value
                ObjBool(console.setRawMode(enabled))
            }
        }
    }

    module.addConstDoc(
        name = "Console",
        value = consoleType,
        doc = "Console runtime API.",
        type = type("Console"),
        moduleName = module.packageName
    )
    module.addConstDoc(
        name = "ConsoleGeometry",
        value = ObjConsoleGeometry.type,
        doc = "Terminal geometry.",
        type = type("lyng.Class"),
        moduleName = module.packageName
    )
    module.addConstDoc(
        name = "ConsoleDetails",
        value = ObjConsoleDetails.type,
        doc = "Consolidated console capability details.",
        type = type("lyng.Class"),
        moduleName = module.packageName
    )
    module.addConstDoc(
        name = "ConsoleEvent",
        value = ObjConsoleEvent.type,
        doc = "Base class for console events.",
        type = type("lyng.Class"),
        moduleName = module.packageName
    )
    module.addConstDoc(
        name = "ConsoleResizeEvent",
        value = ObjConsoleResizeEvent.type,
        doc = "Terminal resize event.",
        type = type("lyng.Class"),
        moduleName = module.packageName
    )
    module.addConstDoc(
        name = "ConsoleKeyEvent",
        value = ObjConsoleKeyEvent.typeObj,
        doc = "Keyboard event.",
        type = type("lyng.Class"),
        moduleName = module.packageName
    )
    module.addConstDoc(
        name = "ConsoleEventStream",
        value = ObjConsoleEventStream.type,
        doc = "Endless iterable stream of console events.",
        type = type("lyng.Class"),
        moduleName = module.packageName
    )
}

private suspend inline fun ScopeFacade.consoleGuard(crossinline block: suspend () -> Obj): Obj {
    return try {
        block()
    } catch (e: ConsoleAccessDeniedException) {
        raiseIllegalOperation(e.reasonDetail ?: "console access denied")
    } catch (e: Exception) {
        raiseIllegalOperation(e.message ?: "console error")
    }
}

private fun ConsoleEventSource.toConsoleEventStream(): ObjConsoleEventStream {
    return ObjConsoleEventStream(this)
}

private class ObjConsoleEventStream(
    private val source: ConsoleEventSource,
) : Obj() {
    override val objClass: net.sergeych.lyng.obj.ObjClass
        get() = type

    companion object {
        val type = net.sergeych.lyng.obj.ObjClass("ConsoleEventStream", ObjIterable).apply {
            addFnDoc(
                name = "iterator",
                doc = "Create an iterator over incoming console events.",
                returns = type("lyng.Iterator"),
                moduleName = "lyng.io.console",
            ) {
                val stream = thisAs<ObjConsoleEventStream>()
                ObjConsoleEventIterator(stream.source)
            }
        }
    }
}

private class ObjConsoleEventIterator(
    private val source: ConsoleEventSource,
) : Obj() {
    private var cached: Obj? = null
    private var closed = false

    override val objClass: net.sergeych.lyng.obj.ObjClass
        get() = type

    private suspend fun ensureCached(): Boolean {
        if (closed) return false
        if (cached != null) return true
        val event = source.nextEvent()
        if (event == null) {
            closeSource()
            return false
        }
        cached = event.toObjEvent()
        return true
    }

    private suspend fun closeSource() {
        if (closed) return
        closed = true
        source.close()
    }

    suspend fun hasNext(): Boolean = ensureCached()

    suspend fun next(scope: Scope): Obj {
        if (!ensureCached()) {
            scope.raiseError(ObjIterationFinishedException(scope))
        }
        val out = cached ?: scope.raiseError("console iterator internal error: missing cached event")
        cached = null
        return out
    }

    companion object {
        val type = net.sergeych.lyng.obj.ObjClass("ConsoleEventIterator", ObjIterator).apply {
            addFnDoc(
                name = "hasNext",
                doc = "Whether another console event is available.",
                returns = type("lyng.Bool"),
                moduleName = "lyng.io.console",
            ) {
                thisAs<ObjConsoleEventIterator>().hasNext().toObj()
            }
            addFnDoc(
                name = "next",
                doc = "Return the next console event.",
                returns = type("ConsoleEvent"),
                moduleName = "lyng.io.console",
            ) {
                thisAs<ObjConsoleEventIterator>().next(requireScope())
            }
            addFnDoc(
                name = "cancelIteration",
                doc = "Stop reading console events and release resources.",
                returns = type("lyng.Void"),
                moduleName = "lyng.io.console",
            ) {
                thisAs<ObjConsoleEventIterator>().closeSource()
                ObjVoid
            }
        }
    }
}

private fun ConsoleEvent.toObjEvent(): Obj = when (this) {
    is ConsoleEvent.Resize -> ObjConsoleResizeEvent(columns, rows)
    is ConsoleEvent.KeyDown -> ObjConsoleKeyEvent(type = "keydown", key = key, code = code, ctrl = ctrl, alt = alt, shift = shift, meta = meta)
    is ConsoleEvent.KeyUp -> ObjConsoleKeyEvent(type = "keyup", key = key, code = code, ctrl = ctrl, alt = alt, shift = shift, meta = meta)
}

private abstract class ObjConsoleEventBase(
    private val eventType: String,
    final override val objClass: net.sergeych.lyng.obj.ObjClass,
) : Obj() {
    fun eventTypeName(): String = eventType
}

private class ObjConsoleEvent : ObjConsoleEventBase("event", type) {
    companion object {
        val type = net.sergeych.lyng.obj.ObjClass("ConsoleEvent").apply {
            addPropertyDoc(
                name = "type",
                doc = "Event type string: resize, keydown, keyup.",
                type = type("lyng.String"),
                moduleName = "lyng.io.console",
                getter = { ObjString((this.thisObj as ObjConsoleEventBase).eventTypeName()) }
            )
        }
    }
}

private class ObjConsoleResizeEvent(
    val columns: Int,
    val rows: Int,
) : ObjConsoleEventBase("resize", type) {
    companion object {
        val type = net.sergeych.lyng.obj.ObjClass("ConsoleResizeEvent", ObjConsoleEvent.type).apply {
            addPropertyDoc(
                name = "columns",
                doc = "Terminal width in character cells.",
                type = type("lyng.Int"),
                moduleName = "lyng.io.console",
                getter = { (this.thisObj as ObjConsoleResizeEvent).columns.toObj() }
            )
            addPropertyDoc(
                name = "rows",
                doc = "Terminal height in character cells.",
                type = type("lyng.Int"),
                moduleName = "lyng.io.console",
                getter = { (this.thisObj as ObjConsoleResizeEvent).rows.toObj() }
            )
        }
    }
}

private class ObjConsoleKeyEvent(
    type: String,
    val key: String,
    val code: String?,
    val ctrl: Boolean,
    val alt: Boolean,
    val shift: Boolean,
    val meta: Boolean,
) : ObjConsoleEventBase(type, typeObj) {
    companion object {
        val typeObj = net.sergeych.lyng.obj.ObjClass("ConsoleKeyEvent", ObjConsoleEvent.type).apply {
            addPropertyDoc(
                name = "key",
                doc = "Logical key name (e.g. ArrowLeft, a, Escape).",
                type = type("lyng.String"),
                moduleName = "lyng.io.console",
                getter = { ObjString((this.thisObj as ObjConsoleKeyEvent).key) }
            )
            addPropertyDoc(
                name = "code",
                doc = "Optional hardware/code identifier.",
                type = type("lyng.String", nullable = true),
                moduleName = "lyng.io.console",
                getter = {
                    val code = (this.thisObj as ObjConsoleKeyEvent).code
                    code?.let(::ObjString) ?: ObjNull
                }
            )
            addPropertyDoc(
                name = "ctrl",
                doc = "Whether Ctrl modifier is pressed.",
                type = type("lyng.Bool"),
                moduleName = "lyng.io.console",
                getter = { (this.thisObj as ObjConsoleKeyEvent).ctrl.toObj() }
            )
            addPropertyDoc(
                name = "alt",
                doc = "Whether Alt modifier is pressed.",
                type = type("lyng.Bool"),
                moduleName = "lyng.io.console",
                getter = { (this.thisObj as ObjConsoleKeyEvent).alt.toObj() }
            )
            addPropertyDoc(
                name = "shift",
                doc = "Whether Shift modifier is pressed.",
                type = type("lyng.Bool"),
                moduleName = "lyng.io.console",
                getter = { (this.thisObj as ObjConsoleKeyEvent).shift.toObj() }
            )
            addPropertyDoc(
                name = "meta",
                doc = "Whether Meta/Super modifier is pressed.",
                type = type("lyng.Bool"),
                moduleName = "lyng.io.console",
                getter = { (this.thisObj as ObjConsoleKeyEvent).meta.toObj() }
            )
        }
    }
}

private class ObjConsoleGeometry(
    val columns: Int,
    val rows: Int,
) : Obj() {
    override val objClass: net.sergeych.lyng.obj.ObjClass get() = type

    companion object {
        val type = net.sergeych.lyng.obj.ObjClass("ConsoleGeometry").apply {
            addPropertyDoc(
                name = "columns",
                doc = "Terminal width in character cells.",
                type = type("lyng.Int"),
                moduleName = "lyng.io.console",
                getter = { (this.thisObj as ObjConsoleGeometry).columns.toObj() }
            )
            addPropertyDoc(
                name = "rows",
                doc = "Terminal height in character cells.",
                type = type("lyng.Int"),
                moduleName = "lyng.io.console",
                getter = { (this.thisObj as ObjConsoleGeometry).rows.toObj() }
            )
        }
    }
}

private class ObjConsoleDetails(
    val supported: Boolean,
    val isTty: Boolean,
    val ansiLevel: String,
    val geometry: ObjConsoleGeometry?,
) : Obj() {
    override val objClass: net.sergeych.lyng.obj.ObjClass get() = type

    companion object {
        val type = net.sergeych.lyng.obj.ObjClass("ConsoleDetails").apply {
            addPropertyDoc(
                name = "supported",
                doc = "Whether console API is supported.",
                type = type("lyng.Bool"),
                moduleName = "lyng.io.console",
                getter = { (this.thisObj as ObjConsoleDetails).supported.toObj() }
            )
            addPropertyDoc(
                name = "isTty",
                doc = "Whether output is connected to a TTY.",
                type = type("lyng.Bool"),
                moduleName = "lyng.io.console",
                getter = { (this.thisObj as ObjConsoleDetails).isTty.toObj() }
            )
            addPropertyDoc(
                name = "ansiLevel",
                doc = "Detected ANSI color capability level.",
                type = type("lyng.String"),
                moduleName = "lyng.io.console",
                getter = { ObjString((this.thisObj as ObjConsoleDetails).ansiLevel) }
            )
            addPropertyDoc(
                name = "geometry",
                doc = "Current terminal geometry or null.",
                type = type("ConsoleGeometry", nullable = true),
                moduleName = "lyng.io.console",
                getter = { (this.thisObj as ObjConsoleDetails).geometry ?: ObjNull }
            )
        }
    }
}
