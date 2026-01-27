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

package net.sergeych.lyng

import net.sergeych.lyng.obj.*
import net.sergeych.lyng.bytecode.CmdDisassembler
import net.sergeych.lyng.bytecode.BytecodeStatement
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.pacman.ImportProvider

// Simple per-frame id generator for perf caches (not thread-safe, fine for scripts)
object FrameIdGen { var c: Long = 1L; fun nextId(): Long = c++ }
fun nextFrameId(): Long = FrameIdGen.nextId()

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
    var parent: Scope?,
    var args: Arguments = Arguments.EMPTY,
    var pos: Pos = Pos.builtIn,
    var thisObj: Obj = ObjVoid,
    var skipScopeCreation: Boolean = false,
) {
    /** Lexical class context for visibility checks (propagates from parent). */
    var currentClassCtx: net.sergeych.lyng.obj.ObjClass? = parent?.currentClassCtx
    // Unique id per scope frame for PICs; regenerated on each borrow from the pool.
    var frameId: Long = nextFrameId()

    // Fast-path storage for local variables/arguments accessed by slot index.
    // Enabled by default for child scopes; module/class scopes can ignore it.
    private val slots: MutableList<ObjRecord> = mutableListOf()
    private val nameToSlot: MutableMap<String, Int> = mutableMapOf()
    /**
     * Auxiliary per-frame map of local bindings (locals declared in this frame).
     * This helps resolving locals across suspension when slot ownership isn't
     * directly discoverable from the current frame.
     */
    internal val localBindings: MutableMap<String, ObjRecord> = mutableMapOf()

    internal val extensions: MutableMap<ObjClass, MutableMap<String, ObjRecord>> = mutableMapOf()

    fun addExtension(cls: ObjClass, name: String, record: ObjRecord) {
        extensions.getOrPut(cls) { mutableMapOf() }[name] = record
    }

    internal fun findExtension(receiverClass: ObjClass, name: String): ObjRecord? {
        var s: Scope? = this
        var hops = 0
        while (s != null && hops++ < 1024) {
            // Proximity rule: check all extensions in the current scope before going to parent.
            // Priority within scope: more specific class in MRO wins.
            for (cls in receiverClass.mro) {
                s.extensions[cls]?.get(name)?.let { return it }
            }
            if (s is ClosureScope) {
                s.closureScope.findExtension(receiverClass, name)?.let { return it }
            }
            s = s.parent
        }
        return null
    }

    /** Debug helper: ensure assigning [candidateParent] does not create a structural cycle. */
    private fun ensureNoCycle(candidateParent: Scope?) {
        if (candidateParent == null) return
        var s: Scope? = candidateParent
        var hops = 0
        while (s != null && hops++ < 1024) {
            if (s === this) {
                // In production we silently ignore; for debugging throw an error to signal misuse
                throw IllegalStateException("cycle detected in scope parent chain assignment")
            }
            s = s.parent
        }
    }

    /**
     * Internal lookup helpers that deliberately avoid invoking overridden `get` implementations
     * (notably in ClosureScope) to prevent accidental ping-pong and infinite recursion across
     * intertwined closure frames. They traverse the plain parent chain and consult only locals
     * and bindings of each frame. Instance/class member fallback must be decided by the caller.
     */
    internal fun tryGetLocalRecord(s: Scope, name: String, caller: net.sergeych.lyng.obj.ObjClass?): ObjRecord? {
        caller?.let { ctx ->
            s.objects[ctx.mangledName(name)]?.let { rec ->
                if (rec.visibility == Visibility.Private) return rec
            }
        }
        s.objects[name]?.let { rec ->
            if (rec.declaringClass == null || canAccessMember(rec.visibility, rec.declaringClass, caller, name)) return rec
        }
        caller?.let { ctx ->
            s.localBindings[ctx.mangledName(name)]?.let { rec ->
                if (rec.visibility == Visibility.Private) return rec
            }
        }
        s.localBindings[name]?.let { rec ->
            if (rec.declaringClass == null || canAccessMember(rec.visibility, rec.declaringClass, caller, name)) return rec
        }
        s.getSlotIndexOf(name)?.let { idx ->
            val rec = s.getSlotRecord(idx)
            if (rec.declaringClass == null || canAccessMember(rec.visibility, rec.declaringClass, caller, name)) return rec
        }
        return null
    }

    internal fun chainLookupIgnoreClosure(name: String, followClosure: Boolean = false, caller: net.sergeych.lyng.obj.ObjClass? = null): ObjRecord? {
        var s: Scope? = this
        // use hop counter to detect unexpected structural cycles in the parent chain
        var hops = 0
        val effectiveCaller = caller ?: currentClassCtx
        while (s != null && hops++ < 1024) {
            tryGetLocalRecord(s, name, effectiveCaller)?.let { return it }
            s = if (followClosure && s is ClosureScope) s.closureScope else s.parent
        }
        return null
    }

    /**
     * Perform base Scope.get semantics for this frame without delegating into parent.get
     * virtual dispatch. This checks:
     *  - locals/bindings in this frame
     *  - walks raw parent chain for locals/bindings (ignoring ClosureScope-specific overrides)
     *  - finally falls back to this frame's `thisObj` instance/class members
     */
    internal fun baseGetIgnoreClosure(name: String): ObjRecord? {
        // 1) locals/bindings in this frame
        tryGetLocalRecord(this, name, currentClassCtx)?.let { return it }
        // 2) walk parents for plain locals/bindings only
        var s = parent
        var hops = 0
        while (s != null && hops++ < 1024) {
            tryGetLocalRecord(s, name, currentClassCtx)?.let { return it }
            s = s.parent
        }
        // 3) fallback to instance/class members of this frame's thisObj
        for (cls in thisObj.objClass.mro) {
            this.extensions[cls]?.get(name)?.let { return it }
        }
        return thisObj.objClass.getInstanceMemberOrNull(name)?.let { rec ->
            if (canAccessMember(rec.visibility, rec.declaringClass, currentClassCtx, name)) {
                if (rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.Property || rec.isAbstract) null
                else rec
            } else null
        }
    }

    /**
     * Walk the ancestry starting from this scope and try to resolve [name] against:
     *  - locals/bindings of each frame
     *  - then instance/class members of each frame's `thisObj`.
     * This completely avoids invoking overridden `get` implementations, preventing
     * ping-pong recursion between `ClosureScope` frames.
     */
    internal fun chainLookupWithMembers(name: String, caller: net.sergeych.lyng.obj.ObjClass? = currentClassCtx, followClosure: Boolean = false): ObjRecord? {
        var s: Scope? = this
        var hops = 0
        while (s != null && hops++ < 1024) {
            tryGetLocalRecord(s, name, caller)?.let { return it }
            for (cls in s.thisObj.objClass.mro) {
                s.extensions[cls]?.get(name)?.let { return it }
            }
            s.thisObj.objClass.getInstanceMemberOrNull(name)?.let { rec ->
                if (canAccessMember(rec.visibility, rec.declaringClass, caller, name)) {
                    if (rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.Property || rec.isAbstract) {
                        // ignore fields, properties and abstracts here, they will be handled by the caller via readField
                    } else return rec
                }
            }
            s = if (followClosure && s is ClosureScope) s.closureScope else s.parent
        }
        return null
    }

    /**
     * Create a non-pooled snapshot of this scope suitable for capturing as a closure environment.
     * Copies locals, slots, and localBindings; preserves parent chain and class context.
     */
    fun snapshotForClosure(): Scope {
        val snap = Scope(parent, args, pos, thisObj)
        snap.currentClassCtx = this.currentClassCtx
        // copy locals and bindings
        snap.objects.putAll(this.objects)
        snap.localBindings.putAll(this.localBindings)
        // copy extensions
        for ((cls, map) in extensions) {
            snap.extensions[cls] = map.toMutableMap()
        }
        // copy slots map preserving indices
        if (this.slotCount() > 0) {
            var i = 0
            while (i < this.slotCount()) {
                val rec = this.getSlotRecord(i)
                snap.allocateSlotFor(this.nameToSlot.entries.firstOrNull { it.value == i }?.key ?: "slot${'$'}i", rec)
                i++
            }
        }
        return snap
    }

    /**
     * Hint internal collections to reduce reallocations for upcoming parameter/local assignments.
     * Only effective for ArrayList-backed slots; maps are left as-is (rehashed lazily by JVM).
     */
    private fun reserveLocalCapacity(expected: Int) {
        if (expected <= 0) return
        (slots as? ArrayList<ObjRecord>)?.ensureCapacity(expected)
        // nameToSlot has no portable ensureCapacity across KMP; leave it to grow as needed.
    }

    /**
     * Hint expected number of local variables/arguments to reduce internal reallocations.
     * Safe no-op for small or unknown values.
     */
    fun hintLocalCapacity(expected: Int) {
        reserveLocalCapacity(expected)
    }
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

    fun raiseUnset(message: String = "property is unset (not initialized)"): Nothing =
        raiseError(ObjUnsetException(this, message))

    @Suppress("unused")
    fun raiseSymbolNotFound(name: String): Nothing =
        raiseError(ObjSymbolNotDefinedException(this, "symbol is not defined: $name"))

    fun raiseError(message: String): Nothing {
        val ex = ObjException(this, message)
        throw ExecutionError(ex, pos, ex.message.value)
    }

    fun raiseError(obj: ObjException): Nothing {
        throw ExecutionError(obj, obj.scope.pos, obj.message.value)
    }

    fun raiseError(obj: Obj, pos: Pos, message: String): Nothing {
        throw ExecutionError(obj, pos, message)
    }

    @Suppress("unused")
    fun raiseNotFound(message: String = "not found"): Nothing {
        val ex = ObjNotFoundException(this, message)
        throw ExecutionError(ex, ex.scope.pos, ex.message.value)
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
        while (s != null) {
            val t = s.thisObj
            if (t is T) return t
            s = s.parent
        }
        raiseClassCastError("Cannot cast ${thisObj.objClass.className} to ${T::class.simpleName}")
    }

    internal val objects = mutableMapOf<String, ObjRecord>()

    internal fun getLocalRecordDirect(name: String): ObjRecord? = objects[name]

    open operator fun get(name: String): ObjRecord? {
        if (name == "this") return thisObj.asReadonly

        // 1. Prefer direct locals/bindings declared in this frame
        tryGetLocalRecord(this, name, currentClassCtx)?.let { return it }

        val p = parent

        // 2. If we share thisObj with parent, delegate to parent to maintain
        // "locals shadow members" priority across the this-context.
        if (p != null && p.thisObj === thisObj) {
            return p.get(name)
        }

        // 3. Otherwise, we are the "primary" scope for this thisObj (or have no parent),
        // so check members of thisObj before walking up to a different this-context.
        val receiver = thisObj
        val effectiveClass = receiver as? ObjClass ?: receiver.objClass
        for (cls in effectiveClass.mro) {
            val rec = cls.members[name] ?: cls.classScope?.objects?.get(name)
            if (rec != null && !rec.isAbstract) {
                if (canAccessMember(rec.visibility, rec.declaringClass ?: cls, currentClassCtx, name)) {
                    return rec.copy(receiver = receiver)
                }
            }
        }
        // Finally, root object fallback
        Obj.rootObjectType.members[name]?.let { rec ->
            if (canAccessMember(rec.visibility, rec.declaringClass, currentClassCtx, name)) {
                return rec.copy(receiver = receiver)
            }
        }

        // 4. Finally, walk up ancestry to a scope with a different thisObj context
        return p?.get(name)
    }

    // Slot fast-path API
    fun getSlotRecord(index: Int): ObjRecord = slots[index]
    fun setSlotValue(index: Int, newValue: Obj) {
        slots[index].value = newValue
    }
    val slotCount: Int
        get() = slots.size

    fun getSlotIndexOf(name: String): Int? = nameToSlot[name]
    fun allocateSlotFor(name: String, record: ObjRecord): Int {
        val idx = slots.size
        slots.add(record)
        nameToSlot[name] = idx
        return idx
    }

    fun updateSlotFor(name: String, record: ObjRecord) {
        nameToSlot[name]?.let { slots[it] = record }
    }

    /**
     * Apply a precomputed slot plan (name -> slot index) for this scope.
     * This enables direct slot references to bypass name-based lookup.
     */
    fun applySlotPlan(plan: Map<String, Int>) {
        if (plan.isEmpty()) return
        val maxIndex = plan.values.maxOrNull() ?: return
        if (slots.size <= maxIndex) {
            val targetSize = maxIndex + 1
            while (slots.size < targetSize) {
                slots.add(ObjRecord(ObjUnset, isMutable = true))
            }
        }
        for ((name, idx) in plan) {
            nameToSlot[name] = idx
        }
    }

    fun applySlotPlanWithSnapshot(plan: Map<String, Int>): Map<String, Int?> {
        if (plan.isEmpty()) return emptyMap()
        val maxIndex = plan.values.maxOrNull() ?: return emptyMap()
        if (slots.size <= maxIndex) {
            val targetSize = maxIndex + 1
            while (slots.size < targetSize) {
                slots.add(ObjRecord(ObjUnset, isMutable = true))
            }
        }
        val snapshot = LinkedHashMap<String, Int?>(plan.size)
        for ((name, idx) in plan) {
            snapshot[name] = nameToSlot[name]
            nameToSlot[name] = idx
        }
        return snapshot
    }

    fun restoreSlotPlan(snapshot: Map<String, Int?>) {
        if (snapshot.isEmpty()) return
        for ((name, idx) in snapshot) {
            if (idx == null) {
                nameToSlot.remove(name)
            } else {
                nameToSlot[name] = idx
            }
        }
    }

    fun hasSlotPlanConflict(plan: Map<String, Int>): Boolean {
        if (plan.isEmpty() || nameToSlot.isEmpty()) return false
        val planIndexToNames = HashMap<Int, HashSet<String>>(plan.size)
        for ((name, idx) in plan) {
            val names = planIndexToNames.getOrPut(idx) { HashSet(2) }
            names.add(name)
        }
        for ((existingName, existingIndex) in nameToSlot) {
            val plannedNames = planIndexToNames[existingIndex] ?: continue
            if (!plannedNames.contains(existingName)) return true
        }
        return false
    }

    /**
     * Clear all references and maps to prevent memory leaks when pooled.
     */
    fun scrub() {
        this.parent = null
        this.skipScopeCreation = false
        this.currentClassCtx = null
        objects.clear()
        slots.clear()
        nameToSlot.clear()
        localBindings.clear()
        extensions.clear()
    }

    /**
     * Reset this scope instance so it can be safely reused as a fresh child frame.
     * Clears locals and slots, assigns new frameId, and sets parent/args/pos/thisObj.
     */
    fun resetForReuse(parent: Scope?, args: Arguments, pos: Pos, thisObj: Obj) {
        // Fully detach from any previous chain/state first to avoid residual ancestry
        // that could interact badly with the new parent and produce a cycle.
        this.parent = null
        this.skipScopeCreation = false
        this.currentClassCtx = parent?.currentClassCtx
        // fresh identity for PIC caches
        this.frameId = nextFrameId()
        // clear locals and slot maps
        objects.clear()
        slots.clear()
        nameToSlot.clear()
        localBindings.clear()
        extensions.clear()
        // Now safe to validate and re-parent
        ensureNoCycle(parent)
        this.parent = parent
        this.args = args
        this.pos = pos
        this.thisObj = thisObj
        // Pre-size local slots for upcoming parameter assignment where possible
        reserveLocalCapacity(args.list.size + 4)
    }

    /**
     * Creates a new child scope using the provided arguments and optional `thisObj`.
     */
    fun createChildScope(pos: Pos, args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null): Scope =
        Scope(this, args, pos, newThisObj ?: thisObj).also {
            it.ensureNoCycle(it.parent)
            it.reserveLocalCapacity(args.list.size + 4)
        }

    /**
     * Execute a block inside a child frame. Guarded for future pooling via [PerfFlags.SCOPE_POOL].
     * Currently always creates a fresh child scope to preserve unique frameId semantics.
     */
    inline suspend fun <R> withChildFrame(args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null, crossinline block: suspend (Scope) -> R): R {
        if (PerfFlags.SCOPE_POOL) {
            val child = ScopePool.borrow(this, args, pos, newThisObj ?: thisObj)
            try {
                return block(child)
            } finally {
                ScopePool.release(child)
            }
        } else {
            val child = createChildScope(args, newThisObj)
            return block(child)
        }
    }

    /**
     * Creates a new child scope using the provided arguments and optional `thisObj`.
     * The child scope inherits the current scope's properties such as position and the existing `thisObj` if no new `thisObj` is provided.
     *
     * @param args The arguments to associate with the child scope. Defaults to [Arguments.EMPTY].
     * @param newThisObj The new `thisObj` to associate with the child scope. Defaults to the current scope's `thisObj` if not provided.
     * @return A new instance of [Scope] initialized with the specified arguments and `thisObj`.
     */
    fun createChildScope(args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null): Scope =
        Scope(this, args, pos, newThisObj ?: thisObj).also { it.ensureNoCycle(it.parent) }

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
        writeVisibility: Visibility? = null,
        recordType: ObjRecord.Type = ObjRecord.Type.Other,
        isAbstract: Boolean = false,
        isClosed: Boolean = false,
        isOverride: Boolean = false
    ): ObjRecord =
        objects[name]?.let {
            if( !it.isMutable )
                raiseIllegalAssignment("symbol is readonly: $name")
            it.value = value
            // keep local binding index consistent within the frame
            localBindings[name] = it
            // If we are a ClosureScope, mirror binding into the caller frame to keep it discoverable
            // across suspension when resumed on the call frame
            if (this is ClosureScope) {
                callScope.localBindings[name] = it
            }
            bumpClassLayoutIfNeeded(name, value, recordType)
            it
        } ?: addItem(name, true, value, visibility, writeVisibility, recordType, isAbstract = isAbstract, isClosed = isClosed, isOverride = isOverride)

    fun addItem(
        name: String,
        isMutable: Boolean,
        value: Obj,
        visibility: Visibility = Visibility.Public,
        writeVisibility: Visibility? = null,
        recordType: ObjRecord.Type = ObjRecord.Type.Other,
        declaringClass: net.sergeych.lyng.obj.ObjClass? = currentClassCtx,
        isAbstract: Boolean = false,
        isClosed: Boolean = false,
        isOverride: Boolean = false,
        isTransient: Boolean = false
    ): ObjRecord {
        val rec = ObjRecord(
            value, isMutable, visibility, writeVisibility,
            declaringClass = declaringClass,
            type = recordType,
            isAbstract = isAbstract,
            isClosed = isClosed,
            isOverride = isOverride,
            isTransient = isTransient
        )
        objects[name] = rec
        bumpClassLayoutIfNeeded(name, value, recordType)
        if (recordType == ObjRecord.Type.Field || recordType == ObjRecord.Type.ConstructorField) {
            val inst = thisObj as? net.sergeych.lyng.obj.ObjInstance
            if (inst != null) {
                val slot = inst.objClass.fieldSlotForKey(name)
                if (slot != null) inst.setFieldSlotRecord(slot.slot, rec)
            }
        }
        if (value is Statement ||
            recordType == ObjRecord.Type.Fun ||
            recordType == ObjRecord.Type.Delegated ||
            recordType == ObjRecord.Type.Property) {
            val inst = thisObj as? net.sergeych.lyng.obj.ObjInstance
            if (inst != null) {
                val slot = inst.objClass.methodSlotForKey(name)
                if (slot != null) inst.setMethodSlotRecord(slot.slot, rec)
            }
        }
        // Index this binding within the current frame to help resolve locals across suspension
        localBindings[name] = rec
        // If we are a ClosureScope, mirror binding into the caller frame to keep it discoverable
        // across suspension when resumed on the call frame
        if (this is ClosureScope) {
            callScope.localBindings[name] = rec
            // Additionally, expose the binding in caller's objects and slot map so identifier
            // resolution after suspension can still find it even if the active scope is a child
            // of the callScope (e.g., due to internal withChildFrame usage).
            // This keeps visibility within the method body but prevents leaking outside the caller frame.
            callScope.objects[name] = rec
            if (callScope.getSlotIndexOf(name) == null) {
                callScope.allocateSlotFor(name, rec)
            }
        }
        // Map to a slot for fast local access (ensure consistency)
        if (nameToSlot.isEmpty()) {
            allocateSlotFor(name, rec)
        } else {
            val idx = nameToSlot[name]
            if (idx == null) {
                allocateSlotFor(name, rec)
            } else {
                slots[idx] = rec
            }
        }
        return rec
    }

    private fun bumpClassLayoutIfNeeded(name: String, value: Obj, recordType: ObjRecord.Type) {
        val cls = thisObj as? net.sergeych.lyng.obj.ObjClass ?: return
        if (cls.classScope !== this) return
        if (!(value is Statement || recordType == ObjRecord.Type.Fun || recordType == ObjRecord.Type.Delegated)) return
        if (cls.members.containsKey(name)) return
        cls.layoutVersion += 1
    }

    fun getOrCreateNamespace(name: String): ObjClass {
        val ns = objects.getOrPut(name) { ObjRecord(ObjNamespace(name), isMutable = false) }.value
        return ns.objClass
    }

    inline fun addVoidFn(vararg names: String, crossinline fn: suspend Scope.() -> Unit) {
        addFn(*names) {
            fn(this)
            ObjVoid
        }
    }

    fun disassembleSymbol(name: String): String {
        val record = get(name) ?: return "$name is not found"
        val stmt = record.value as? Statement ?: return "$name is not a compiled body"
        val bytecode = (stmt as? BytecodeStatement)?.bytecodeFunction()
            ?: (stmt as? BytecodeBodyProvider)?.bytecodeBody()?.bytecodeFunction()
            ?: return "$name is not a compiled body"
        return CmdDisassembler.disassemble(bytecode)
    }

    fun addFn(vararg names: String, fn: suspend Scope.() -> Obj) {
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

    // --- removed doc-aware overloads to keep runtime lean ---

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

    /**
     * Resolve and evaluate a qualified identifier exactly as compiled code would.
     * For input like `A.B.C`, it builds the same ObjRef chain the compiler emits:
     * `LocalVarRef("A", Pos.builtIn)` followed by `FieldRef` for each segment, then evaluates it.
     * This mirrors `eval("A.B.C")` resolution semantics without invoking the compiler.
     */
    suspend fun resolveQualifiedIdentifier(qualifiedName: String): Obj {
        val trimmed = qualifiedName.trim()
        if (trimmed.isEmpty()) raiseSymbolNotFound("empty identifier")
        val parts = trimmed.split('.')
        var ref: ObjRef = LocalVarRef(parts[0], Pos.builtIn)
        for (i in 1 until parts.size) {
            ref = FieldRef(ref, parts[i], false)
        }
        return ref.evalValue(this)
    }

    suspend fun resolve(rec: ObjRecord, name: String): Obj {
        val receiver = rec.receiver ?: thisObj
        return receiver.resolveRecord(this, rec, name, rec.declaringClass).value
    }

    suspend fun assign(rec: ObjRecord, name: String, newValue: Obj) {
        if (rec.type == ObjRecord.Type.Delegated) {
            val receiver = rec.receiver ?: thisObj
            val del = rec.delegate ?: run {
                if (receiver is ObjInstance) {
                    (receiver as ObjInstance).writeField(this, name, newValue)
                    return
                }
                raiseError("Internal error: delegated property $name has no delegate")
            }
            val th = if (receiver === ObjVoid) ObjNull else receiver
            del.invokeInstanceMethod(this, "setValue", Arguments(th, ObjString(name), newValue))
            return
        }
        if (rec.value is ObjProperty) {
            (rec.value as ObjProperty).callSetter(this, rec.receiver ?: thisObj, newValue, rec.declaringClass)
            return
        }
        // If it's a member (explicitly tracked by receiver or declaringClass), use writeField.
        // Important: locals have receiver == null and declaringClass == null (enforced in addItem).
        if (rec.receiver != null || (rec.declaringClass != null && (rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.Property))) {
            (rec.receiver ?: thisObj).writeField(this, name, newValue)
            return
        }
        if (!rec.isMutable && rec.value !== ObjUnset) raiseIllegalAssignment("can't reassign val $name")
        rec.value = newValue
    }

    companion object {

        fun new(): Scope =
            Script.defaultImportManager.copy().newModuleAt(Pos.builtIn)
    }
}
