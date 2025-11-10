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

package net.sergeych.lyng

import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.pacman.ImportProvider

// Simple per-frame id generator for perf caches (not thread-safe, fine for scripts)
private object FrameIdGen { var c: Long = 1L; fun nextId(): Long = c++ }
private fun nextFrameId(): Long = FrameIdGen.nextId()

/**
 * Scope is where local variables and methods are stored. Scope is also a parent scope for other scopes.
 * Each block usually creates a scope. Accessing Lyng closures usually is done via a scope.
 *
 * To create default scope, use default `Scope()` constructor, it will create a scope with a parent
 * module scope with default [ImportManager], you can access with [currentImportProvider] as needed.
 *
 * If you want to create [ModuleScope] by hand, try [currentImportProvider] and [ImportManager.newModule],
 * or [ImportManager.newModuleAt].
 *
 *  There are special types of scopes:
 *
 *  - [ClosureScope] - scope used to apply a closure to some thisObj scope
 */
open class Scope(
    val parent: Scope?,
    val args: Arguments = Arguments.EMPTY,
    var pos: Pos = Pos.builtIn,
    var thisObj: Obj = ObjVoid,
    var skipScopeCreation: Boolean = false,
) {
    // Unique id per scope frame for PICs; cheap to compare and stable for the frame lifetime.
    val frameId: Long = nextFrameId()

    // Fast-path storage for local variables/arguments accessed by slot index.
    // Enabled by default for child scopes; module/class scopes can ignore it.
    private val slots: MutableList<ObjRecord> = mutableListOf()
    private val nameToSlot: MutableMap<String, Int> = mutableMapOf()
    open val packageName: String = "<anonymous package>"

    fun slotCount(): Int = slots.size

    constructor(
        args: Arguments = Arguments.EMPTY,
        pos: Pos = Pos.builtIn,
    )
            : this(Script.defaultImportManager.copy().newModuleAt(pos), args, pos)

    fun raiseNotImplemented(what: String = "operation"): Nothing = raiseError("$what is not implemented")

    @Suppress("unused")
    fun raiseNPE(): Nothing = raiseError(ObjNullReferenceException(this))

    @Suppress("unused")
    fun raiseIndexOutOfBounds(message: String = "Index out of bounds"): Nothing =
        raiseError(ObjIndexOutOfBoundsException(this, message))

    @Suppress("unused")
    fun raiseIllegalArgument(message: String = "Illegal argument error"): Nothing =
        raiseError(ObjIllegalArgumentException(this, message))

    @Suppress("unused")
    fun raiseIllegalState(message: String = "Illegal argument error"): Nothing =
        raiseError(ObjIllegalStateException(this, message))

    fun raiseIllegalAssignment(message: String): Nothing =
        raiseError(ObjIllegalAssignmentException(this, message))

    @Suppress("unused")
    fun raiseNoSuchElement(message: String = "No such element"): Nothing =
        raiseError(ObjIllegalArgumentException(this, message))

    fun raiseClassCastError(msg: String): Nothing = raiseError(ObjClassCastException(this, msg))

    @Suppress("unused")
    fun raiseSymbolNotFound(name: String): Nothing =
        raiseError(ObjSymbolNotDefinedException(this, "symbol is not defined: $name"))

    fun raiseError(message: String): Nothing {
        throw ExecutionError(ObjException(this, message))
    }

    fun raiseError(obj: ObjException): Nothing {
        throw ExecutionError(obj)
    }

    @Suppress("unused")
    fun raiseNotFound(message: String = "not found"): Nothing {
        throw ExecutionError(ObjNotFoundException(this, message))
    }

    inline fun <reified T : Obj> requiredArg(index: Int): T {
        if (args.list.size <= index) raiseError("Expected at least ${index + 1} argument, got ${args.list.size}")
        return (args.list[index].byValueCopy() as? T)
            ?: raiseClassCastError("Expected type ${T::class.simpleName}, got ${args.list[index]::class.simpleName}")
    }

    inline fun <reified T : Obj> requireOnlyArg(): T {
        if (args.list.size != 1) raiseError("Expected exactly 1 argument, got ${args.list.size}")
        return requiredArg(0)
    }

    @Suppress("unused")
    fun requireExactCount(count: Int) {
        if (args.list.size != count) {
            raiseError("Expected exactly $count arguments, got ${args.list.size}")
        }
    }

    fun requireNoArgs() {
        if (args.list.isNotEmpty())
            raiseError("This function does not accept any arguments")
    }

    inline fun <reified T : Obj> thisAs(): T {
        var s: Scope? = this
        do {
            val t = s!!.thisObj
            if (t is T) return t
            s = s.parent
        } while (s != null)
        raiseClassCastError("Cannot cast ${thisObj.objClass.className} to ${T::class.simpleName}")
    }

    internal val objects = mutableMapOf<String, ObjRecord>()

    open operator fun get(name: String): ObjRecord? =
        if (name == "this") thisObj.asReadonly
        else {
            (objects[name]
                ?: parent?.get(name)
                ?: thisObj.objClass
                    .getInstanceMemberOrNull(name)
                    )
        }

    // Slot fast-path API
    fun getSlotRecord(index: Int): ObjRecord = slots[index]
    fun setSlotValue(index: Int, newValue: Obj) {
        slots[index].value = newValue
    }

    fun getSlotIndexOf(name: String): Int? = nameToSlot[name]
    fun allocateSlotFor(name: String, record: ObjRecord): Int {
        val idx = slots.size
        slots.add(record)
        nameToSlot[name] = idx
        return idx
    }

    /**
     * Creates a new child scope using the provided arguments and optional `thisObj`.
     */
    fun createChildScope(pos: Pos, args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null): Scope =
        Scope(this, args, pos, newThisObj ?: thisObj)

    /**
     * Creates a new child scope using the provided arguments and optional `thisObj`.
     * The child scope inherits the current scope's properties such as position and the existing `thisObj` if no new `thisObj` is provided.
     *
     * @param args The arguments to associate with the child scope. Defaults to [Arguments.EMPTY].
     * @param newThisObj The new `thisObj` to associate with the child scope. Defaults to the current scope's `thisObj` if not provided.
     * @return A new instance of [Scope] initialized with the specified arguments and `thisObj`.
     */
    fun createChildScope(args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null): Scope =
        Scope(this, args, pos, newThisObj ?: thisObj)

    /**
     * @return A child scope with the same arguments, position and [thisObj]
     */
    fun createChildScope() = Scope(this, args, pos, thisObj)

    /**
     * Add or update ObjRecord with a given value checking rights. Created [ObjRecord] is mutable.
     * Throws Lyng [ObjIllegalArgumentException] if yje [name] exists and readonly.
     * @return ObjRector, new or updated.
     */
    fun addOrUpdateItem(
        name: String,
        value: Obj,
        visibility: Visibility = Visibility.Public,
        recordType: ObjRecord.Type = ObjRecord.Type.Other
    ): ObjRecord =
        objects[name]?.let {
            if( !it.isMutable )
                raiseIllegalAssignment("symbol is readonly: $name")
            it.value = value
            it
        } ?: addItem(name, true, value, visibility, recordType)

    fun addItem(
        name: String,
        isMutable: Boolean,
        value: Obj,
        visibility: Visibility = Visibility.Public,
        recordType: ObjRecord.Type = ObjRecord.Type.Other
    ): ObjRecord {
        val rec = ObjRecord(value, isMutable, visibility, type = recordType)
        objects[name] = rec
        // Map to a slot for fast local access (if not already mapped)
        if (getSlotIndexOf(name) == null) {
            allocateSlotFor(name, rec)
        }
        return rec
    }

    fun getOrCreateNamespace(name: String): ObjClass {
        val ns = objects.getOrPut(name) { ObjRecord(ObjNamespace(name), isMutable = false) }.value
        return ns.objClass
    }

    inline fun addVoidFn(vararg names: String, crossinline fn: suspend Scope.() -> Unit) {
        addFn<ObjVoid>(*names) {
            fn(this)
            ObjVoid
        }
    }

    inline fun <reified T : Obj> addFn(vararg names: String, crossinline fn: suspend Scope.() -> T) {
        val newFn = object : Statement() {
            override val pos: Pos = Pos.builtIn

            override suspend fun execute(scope: Scope): Obj = scope.fn()

        }
        for (name in names) {
            addItem(
                name,
                false,
                newFn
            )
        }
    }

    fun addConst(name: String, value: Obj) = addItem(name, false, value)

    suspend fun eval(code: String): Obj =
        eval(code.toSource())

    suspend fun eval(source: Source): Obj {
        return Compiler.compile(
            source,
            currentImportProvider
        ).execute(this)
    }

    fun containsLocal(name: String): Boolean = name in objects

    /**
     * Some scopes can be imported into other scopes, like [ModuleScope]. Those must correctly implement this method.
     * @param scope where to copy symbols from this module
     * @param symbols symbols to import, ir present, only symbols keys will be imported renamed to corresponding values
     */
    open suspend fun importInto(scope: Scope, symbols: Map<String, String>? = null) {
        scope.raiseError(ObjIllegalOperationException(scope, "Import is not allowed here: import $packageName"))
    }

    /**
     * Find a first [ImportManager] in this Scope hierarchy. Normally there should be one. Found instance is cached.
     *
     * Use it to register your package sources, see [ImportManager] features.
     *
     * @throws IllegalStateException if there is no such manager (if you create some specific scope with no manager,
     *      then you knew what you did)
     */
    val currentImportProvider: ImportProvider by lazy {
        if (this is ModuleScope)
            importProvider.getActualProvider()
        else
            parent?.currentImportProvider ?: throw IllegalStateException("this scope has no manager in the chain")
    }

    val importManager by lazy {
        (currentImportProvider as? ImportManager)
            ?: throw IllegalStateException("this scope has no manager in the chain (provided $currentImportProvider")
    }

    override fun toString(): String {
        val contents =
            objects.entries.joinToString { "${if (it.value.isMutable) "var" else "val"} ${it.key}=${it.value.value}" }
        return "S[this=$thisObj $contents]"
    }

    fun trace(text: String = "") {
        println("trace Scope: $text ------------------")
        var p = this.parent
        var level = 0
        while (p != null) {
            println("     parent#${++level}: $p")
            println("     ( ${p.args.list} )")
            p = p.parent
        }
        println("--------------------")
    }

    open fun applyClosure(closure: Scope): Scope = ClosureScope(this, closure)

    companion object {

        fun new(): Scope =
            Script.defaultImportManager.copy().newModuleAt(Pos.builtIn)
    }
}
