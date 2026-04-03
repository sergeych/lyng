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

import kotlinx.coroutines.delay
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjEnumClass
import net.sergeych.lyng.obj.ObjEnumEntry
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
import net.sergeych.lyngio.console.*
import net.sergeych.lyngio.console.security.ConsoleAccessDeniedException
import net.sergeych.lyngio.console.security.ConsoleAccessPolicy
import net.sergeych.lyngio.console.security.LyngConsoleSecured
import net.sergeych.lyngio.stdlib_included.consoleLyng

private const val CONSOLE_MODULE_NAME = "lyng.io.console"

/**
 * Install Lyng module `lyng.io.console` into the given scope's ImportManager.
 */
fun createConsoleModule(policy: ConsoleAccessPolicy, scope: Scope): Boolean =
    createConsoleModule(policy, scope.importManager)

fun createConsole(policy: ConsoleAccessPolicy, scope: Scope): Boolean = createConsoleModule(policy, scope)

/** Same as [createConsoleModule] but with explicit [ImportManager]. */
fun createConsoleModule(policy: ConsoleAccessPolicy, manager: ImportManager): Boolean {
    if (manager.packageNames.contains(CONSOLE_MODULE_NAME)) return false

    manager.addPackage(CONSOLE_MODULE_NAME) { module ->
        buildConsoleModule(module, policy, getSystemConsole())
    }
    return true
}

fun createConsole(policy: ConsoleAccessPolicy, manager: ImportManager): Boolean = createConsoleModule(policy, manager)

internal fun createConsoleModule(
    policy: ConsoleAccessPolicy,
    manager: ImportManager,
    console: LyngConsole,
): Boolean {
    if (manager.packageNames.contains(CONSOLE_MODULE_NAME)) return false

    manager.addPackage(CONSOLE_MODULE_NAME) { module ->
        buildConsoleModule(module, policy, console)
    }
    return true
}

private suspend fun buildConsoleModule(module: ModuleScope, policy: ConsoleAccessPolicy, baseConsole: LyngConsole) {
    // Load Lyng declarations for console enums/types first (module-local source of truth).
    module.eval(Source(CONSOLE_MODULE_NAME, consoleLyng))
    ConsoleEnums.initialize(module)
    val console: LyngConsole = LyngConsoleSecured(baseConsole, policy)

    val consoleType = object : net.sergeych.lyng.obj.ObjClass("Console") {}

    consoleType.apply {
        addClassFn("isSupported") {
            ObjBool(console.isSupported)
        }

        addClassFn("isTty") {
            consoleGuard {
                ObjBool(console.isTty())
            }
        }

        addClassFn("ansiLevel") {
            consoleGuard {
                ConsoleEnums.ansiLevel(console.ansiLevel().name)
            }
        }

        addClassFn("geometry") {
            consoleGuard {
                console.geometry()?.let { ObjConsoleGeometry(it.columns, it.rows) } ?: ObjNull
            }
        }

        addClassFn("details") {
            consoleGuard {
                val tty = console.isTty()
                val ansi = console.ansiLevel()
                val geometry = console.geometry()
                ObjConsoleDetails(
                    supported = console.isSupported,
                    isTty = tty,
                    ansiLevel = ConsoleEnums.ansiLevel(ansi.name),
                    geometry = geometry?.let { ObjConsoleGeometry(it.columns, it.rows) },
                )
            }
        }

        addClassFn("write") {
            consoleGuard {
                val text = requiredArg<ObjString>(0).value
                console.write(text)
                ObjVoid
            }
        }

        addClassFn("flush") {
            consoleGuard {
                console.flush()
                ObjVoid
            }
        }

        addClassFn("home") {
            consoleGuard {
                console.write("\u001B[H")
                ObjVoid
            }
        }

        addClassFn("clear") {
            consoleGuard {
                console.write("\u001B[2J")
                ObjVoid
            }
        }

        addClassFn("moveTo") {
            consoleGuard {
                val row = requiredArg<net.sergeych.lyng.obj.ObjInt>(0).value
                val col = requiredArg<net.sergeych.lyng.obj.ObjInt>(1).value
                console.write("\u001B[${row};${col}H")
                ObjVoid
            }
        }

        addClassFn("clearLine") {
            consoleGuard {
                console.write("\u001B[2K")
                ObjVoid
            }
        }

        addClassFn("enterAltScreen") {
            consoleGuard {
                console.write("\u001B[?1049h")
                ObjVoid
            }
        }

        addClassFn("leaveAltScreen") {
            consoleGuard {
                console.write("\u001B[?1049l")
                ObjVoid
            }
        }

        addClassFn("setCursorVisible") {
            consoleGuard {
                val visible = requiredArg<ObjBool>(0).value
                console.write(if (visible) "\u001B[?25h" else "\u001B[?25l")
                ObjVoid
            }
        }

        addClassFn("events") {
            consoleGuard {
                ObjConsoleEventStream { console.events() }
            }
        }

        addClassFn("setRawMode") {
            consoleGuard {
                val enabled = requiredArg<ObjBool>(0).value
                ObjBool(console.setRawMode(enabled))
            }
        }
    }

    module.addConst("Console", consoleType)
    module.addConst("ConsoleGeometry", ObjConsoleGeometry.type)
    module.addConst("ConsoleDetails", ObjConsoleDetails.type)
    module.addConst("ConsoleEvent", ObjConsoleEvent.type)
    module.addConst("ConsoleResizeEvent", ObjConsoleResizeEvent.type)
    module.addConst("ConsoleKeyEvent", ObjConsoleKeyEvent.typeObj)
    module.addConst("ConsoleEventStream", ObjConsoleEventStream.type)
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

private class ObjConsoleEventStream(
    private val sourceFactory: () -> ConsoleEventSource,
) : Obj() {
    override val objClass: net.sergeych.lyng.obj.ObjClass
        get() = type

    companion object {
        val type = net.sergeych.lyng.obj.ObjClass("ConsoleEventStream", ObjIterable).apply {
            addFn("iterator") {
                val stream = thisAs<ObjConsoleEventStream>()
                ObjConsoleEventIterator(stream.sourceFactory)
            }
        }
    }
}

private class ObjConsoleEventIterator(
    private val sourceFactory: () -> ConsoleEventSource,
) : Obj() {
    private var cached: Obj? = null
    private var closed = false
    private var source: ConsoleEventSource? = null

    override val objClass: net.sergeych.lyng.obj.ObjClass
        get() = type

    private fun ensureSource(): ConsoleEventSource {
        val current = source
        if (current != null) return current
        return sourceFactory().also { source = it }
    }

    private suspend fun recycleSource(reason: String, error: Throwable? = null) {
        if (error != null) {
            consoleFlowDebug(reason, error)
        } else {
            consoleFlowDebug(reason)
        }
        val current = source
        source = null
        runCatching { current?.close() }
            .onFailure { consoleFlowDebug("console-bridge: failed to close recycled source", it) }
        if (!closed) delay(25)
    }

    private suspend fun ensureCached(): Boolean {
        if (closed) return false
        if (cached != null) return true
        while (!closed && cached == null) {
            val currentSource = try {
                ensureSource()
            } catch (e: Throwable) {
                recycleSource("console-bridge: source creation failed; retrying", e)
                continue
            }
            val event = try {
                currentSource.nextEvent()
            } catch (e: Throwable) {
                // Consumer loops must survive source/read failures: rebuild the source and keep polling.
                recycleSource("console-bridge: nextEvent failed; recycling source", e)
                continue
            }
            if (event == null) {
                recycleSource("console-bridge: source ended; recreating")
                continue
            }
            cached = try {
                event.toObjEvent()
            } catch (e: Throwable) {
                // Malformed/native event payload must not terminate consumer iteration.
                consoleFlowDebug("console-bridge: malformed event dropped: $event", e)
                null
            }
        }
        return cached != null
    }

    private suspend fun closeSource() {
        if (closed) return
        closed = true
        val current = source
        source = null
        runCatching { current?.close() }
            .onFailure { consoleFlowDebug("console-bridge: failed to close iterator source", it) }
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
            addFn("hasNext") {
                thisAs<ObjConsoleEventIterator>().hasNext().toObj()
            }
            addFn("next") {
                thisAs<ObjConsoleEventIterator>().next(requireScope())
            }
            addFn("cancelIteration") {
                thisAs<ObjConsoleEventIterator>().closeSource()
                ObjVoid
            }
        }
    }
}

private fun ConsoleEvent.toObjEvent(): Obj = when (this) {
    is ConsoleEvent.Resize -> ObjConsoleResizeEvent(columns, rows)
    is ConsoleEvent.KeyDown -> ObjConsoleKeyEvent(type = ConsoleEnums.KEY_DOWN, key = sanitizedKeyOrFallback(key), codeName = code, ctrl = ctrl, alt = alt, shift = shift, meta = meta)
    is ConsoleEvent.KeyUp -> ObjConsoleKeyEvent(type = ConsoleEnums.KEY_UP, key = sanitizedKeyOrFallback(key), codeName = code, ctrl = ctrl, alt = alt, shift = shift, meta = meta)
}

private fun sanitizedKeyOrFallback(key: String): String {
    if (key.isNotEmpty()) return key
    consoleFlowDebug("console-bridge: empty key value received; using fallback key name")
    return "Unknown"
}

private object ConsoleEnums {
    lateinit var eventTypeClass: ObjEnumClass
        private set
    lateinit var keyCodeClass: ObjEnumClass
        private set
    lateinit var ansiLevelClass: ObjEnumClass
        private set

    private lateinit var eventEntries: Map<String, ObjEnumEntry>
    private lateinit var keyCodeEntries: Map<String, ObjEnumEntry>
    private lateinit var ansiLevelEntries: Map<String, ObjEnumEntry>

    val UNKNOWN: ObjEnumEntry get() = event("UNKNOWN")
    val RESIZE: ObjEnumEntry get() = event("RESIZE")
    val KEY_DOWN: ObjEnumEntry get() = event("KEY_DOWN")
    val KEY_UP: ObjEnumEntry get() = event("KEY_UP")
    val CODE_UNKNOWN: ObjEnumEntry get() = code("UNKNOWN")
    val CHARACTER: ObjEnumEntry get() = code("CHARACTER")

    fun initialize(module: ModuleScope) {
        eventTypeClass = resolveEnum(module, "ConsoleEventType")
        keyCodeClass = resolveEnum(module, "ConsoleKeyCode")
        ansiLevelClass = resolveEnum(module, "ConsoleAnsiLevel")
        eventEntries = resolveEntries(
            eventTypeClass,
            listOf("UNKNOWN", "RESIZE", "KEY_DOWN", "KEY_UP")
        )
        keyCodeEntries = resolveEntries(
            keyCodeClass,
            listOf(
                "UNKNOWN", "CHARACTER", "ARROW_UP", "ARROW_DOWN", "ARROW_LEFT", "ARROW_RIGHT",
                "HOME", "END", "INSERT", "DELETE", "PAGE_UP", "PAGE_DOWN",
                "ESCAPE", "ENTER", "TAB", "BACKSPACE", "SPACE"
            )
        )
        ansiLevelEntries = resolveEntries(
            ansiLevelClass,
            listOf("NONE", "BASIC16", "ANSI256", "TRUECOLOR")
        )
    }

    private fun resolveEnum(module: ModuleScope, enumName: String): ObjEnumClass {
        val local = module.get(enumName)?.value as? ObjEnumClass
        if (local != null) return local
        val root = module.importProvider.rootScope.get(enumName)?.value as? ObjEnumClass
        return root ?: error("lyng.io.console declaration enum is missing: $enumName")
    }

    private fun resolveEntries(enumClass: ObjEnumClass, names: List<String>): Map<String, ObjEnumEntry> {
        return names.associateWith { name ->
            (enumClass.byName[ObjString(name)] as? ObjEnumEntry)
                ?: error("lyng.io.console enum entry is missing: ${enumClass.className}.$name")
        }
    }

    fun event(name: String): ObjEnumEntry = eventEntries[name]
        ?: error("lyng.io.console enum entry is missing: ${eventTypeClass.className}.$name")

    fun code(name: String): ObjEnumEntry = keyCodeEntries[name]
        ?: error("lyng.io.console enum entry is missing: ${keyCodeClass.className}.$name")

    fun ansiLevel(name: String): ObjEnumEntry = ansiLevelEntries[name]
        ?: error("lyng.io.console enum entry is missing: ${ansiLevelClass.className}.$name")
}

private val KEY_CODE_BY_KEY_NAME = mapOf(
    "ArrowUp" to "ARROW_UP",
    "ArrowDown" to "ARROW_DOWN",
    "ArrowLeft" to "ARROW_LEFT",
    "ArrowRight" to "ARROW_RIGHT",
    "Home" to "HOME",
    "End" to "END",
    "Insert" to "INSERT",
    "Delete" to "DELETE",
    "PageUp" to "PAGE_UP",
    "PageDown" to "PAGE_DOWN",
    "Escape" to "ESCAPE",
    "Enter" to "ENTER",
    "Tab" to "TAB",
    "Backspace" to "BACKSPACE",
    " " to "SPACE",
)

private fun codeFrom(key: String, codeName: String?): ObjEnumEntry {
    val resolved = KEY_CODE_BY_KEY_NAME[codeName ?: key]
    return when {
        resolved != null -> ConsoleEnums.code(resolved)
        key.length == 1 -> ConsoleEnums.CHARACTER
        else -> ConsoleEnums.CODE_UNKNOWN
    }
}

private abstract class ObjConsoleEventBase(
    private val type: ObjEnumEntry,
    final override val objClass: net.sergeych.lyng.obj.ObjClass,
) : Obj() {
    fun type(): ObjEnumEntry = type
}

private class ObjConsoleEvent : ObjConsoleEventBase(ConsoleEnums.UNKNOWN, type) {
    companion object {
        val type = net.sergeych.lyng.obj.ObjClass("ConsoleEvent").apply {
            addProperty(name = "type", getter = { (this.thisObj as ObjConsoleEventBase).type() })
        }
    }
}

private class ObjConsoleResizeEvent(
    val columns: Int,
    val rows: Int,
) : ObjConsoleEventBase(ConsoleEnums.RESIZE, type) {
    companion object {
        val type = net.sergeych.lyng.obj.ObjClass("ConsoleResizeEvent", ObjConsoleEvent.type).apply {
            addProperty(name = "columns", getter = { (this.thisObj as ObjConsoleResizeEvent).columns.toObj() })
            addProperty(name = "rows", getter = { (this.thisObj as ObjConsoleResizeEvent).rows.toObj() })
        }
    }
}

private class ObjConsoleKeyEvent(
    type: ObjEnumEntry,
    val key: String,
    val codeName: String?,
    val ctrl: Boolean,
    val alt: Boolean,
    val shift: Boolean,
    val meta: Boolean,
) : ObjConsoleEventBase(type, typeObj) {
    init {
        require(key.isNotEmpty()) { "ConsoleKeyEvent.key must never be empty" }
    }

    companion object {
        val typeObj = net.sergeych.lyng.obj.ObjClass("ConsoleKeyEvent", ObjConsoleEvent.type).apply {
            addProperty(name = "key", getter = { ObjString((this.thisObj as ObjConsoleKeyEvent).key) })
            addProperty(name = "code", getter = { codeFrom((this.thisObj as ObjConsoleKeyEvent).key, (this.thisObj as ObjConsoleKeyEvent).codeName) })
            addProperty(name = "codeName", getter = {
                val code = (this.thisObj as ObjConsoleKeyEvent).codeName
                code?.let(::ObjString) ?: ObjNull
            })
            addProperty(name = "ctrl", getter = { (this.thisObj as ObjConsoleKeyEvent).ctrl.toObj() })
            addProperty(name = "alt", getter = { (this.thisObj as ObjConsoleKeyEvent).alt.toObj() })
            addProperty(name = "shift", getter = { (this.thisObj as ObjConsoleKeyEvent).shift.toObj() })
            addProperty(name = "meta", getter = { (this.thisObj as ObjConsoleKeyEvent).meta.toObj() })
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
            addProperty(name = "columns", getter = { (this.thisObj as ObjConsoleGeometry).columns.toObj() })
            addProperty(name = "rows", getter = { (this.thisObj as ObjConsoleGeometry).rows.toObj() })
        }
    }
}

private class ObjConsoleDetails(
    val supported: Boolean,
    val isTty: Boolean,
    val ansiLevel: ObjEnumEntry,
    val geometry: ObjConsoleGeometry?,
) : Obj() {
    override val objClass: net.sergeych.lyng.obj.ObjClass get() = type

    companion object {
        val type = net.sergeych.lyng.obj.ObjClass("ConsoleDetails").apply {
            addProperty(name = "supported", getter = { (this.thisObj as ObjConsoleDetails).supported.toObj() })
            addProperty(name = "isTty", getter = { (this.thisObj as ObjConsoleDetails).isTty.toObj() })
            addProperty(name = "ansiLevel", getter = { (this.thisObj as ObjConsoleDetails).ansiLevel })
            addProperty(name = "geometry", getter = { (this.thisObj as ObjConsoleDetails).geometry ?: ObjNull })
        }
    }
}
