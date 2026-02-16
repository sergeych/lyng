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
 * Kotlin bridge reflection facade: handle-based access for fast get/set/call.
 */

package net.sergeych.lyng.bridge

import net.sergeych.lyng.*
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjIllegalAccessException
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjProperty
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjUnset
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.requireScope

/**
 * Where a bridge resolver should search for names.
 *
 * Used by [LookupSpec] to control reflection scope for Kotlin-side tooling and bindings.
 */
enum class LookupTarget {
    /** Resolve from the current frame only (locals/params declared in the active scope). */
    CurrentFrame,
    /** Resolve by walking the raw parent chain of frames (locals only, no member fallback). */
    ParentChain,
    /** Resolve against the module frame (top-level declarations in the module). */
    ModuleFrame
}

/**
 * Explicit receiver view, similar to `this@Base` in Lyng.
 *
 * When provided, the resolver will treat `this` as the requested type
 * for member resolution and visibility checks.
 */
data class ReceiverView(
    val type: ObjClass? = null,
    val typeName: String? = null
)

/**
 * Lookup rules for bridge resolution.
 *
 * @property targets where to resolve names from
 * @property receiverView optional explicit receiver for member lookup (like `this@Base`)
 */
data class LookupSpec(
    val targets: Set<LookupTarget> = setOf(LookupTarget.CurrentFrame, LookupTarget.ModuleFrame),
    val receiverView: ReceiverView? = null
)

/**
 * Base handle type returned by the Kotlin reflection bridge.
 *
 * Handles are inexpensive to keep and cache; they resolve lazily and
 * may internally cache slots/records once a frame is known.
 */
sealed interface BridgeHandle {
    /** Name of the underlying symbol (as written in Lyng). */
    val name: String
}

/** Read-only value handle resolved in a [ScopeFacade]. */
interface ValHandle : BridgeHandle {
    /** Read the current value. */
    suspend fun get(scope: ScopeFacade): Obj
}

/** Read/write value handle resolved in a [ScopeFacade]. */
interface VarHandle : ValHandle {
    /** Assign a new value. */
    suspend fun set(scope: ScopeFacade, value: Obj)
}

/** Callable handle (function/closure/method). */
interface CallableHandle : BridgeHandle {
    /**
     * Call the target with optional [args].
     *
     * @param newThisObj overrides receiver for member calls (defaults to current `this`/record receiver).
     */
    suspend fun call(scope: ScopeFacade, args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null): Obj
}

/** Member handle resolved against an instance or receiver view. */
interface MemberHandle : BridgeHandle {
    /** Declaring class resolved for the last call/get/set (if known). */
    val declaringClass: ObjClass?
    /** Explicit receiver view used for resolution (if any). */
    val receiverView: ReceiverView?
}

/** Member field/property (read-only). */
interface MemberValHandle : MemberHandle, ValHandle

/** Member var/property with write access. */
interface MemberVarHandle : MemberHandle, VarHandle

/** Member callable (method or extension). */
interface MemberCallableHandle : MemberHandle, CallableHandle

/**
 * Direct record handle (debug/inspection).
 *
 * Exposes raw [ObjRecord] access and should be used only in tooling.
 */
interface RecordHandle : BridgeHandle {
    /** Resolve and return the raw [ObjRecord]. */
    fun record(): ObjRecord
}

/**
 * Bridge resolver API (entry point for Kotlin reflection and bindings).
 *
 * Obtain via [ScopeFacade.resolver] and reuse for multiple lookups.
 * Resolver methods return handles that can be cached and reused across calls.
 */
interface BridgeResolver {
    /** Source position used for error reporting. */
    val pos: Pos

    /** Treat `this` as [type] for member lookup (like `this@Type`). */
    fun selfAs(type: ObjClass): BridgeResolver
    /** Treat `this` as [typeName] for member lookup (like `this@Type`). */
    fun selfAs(typeName: String): BridgeResolver

    /** Resolve a read-only value by name using [lookup]. */
    fun resolveVal(name: String, lookup: LookupSpec = LookupSpec()): ValHandle
    /** Resolve a mutable value by name using [lookup]. */
    fun resolveVar(name: String, lookup: LookupSpec = LookupSpec()): VarHandle
    /** Resolve a callable by name using [lookup]. */
    fun resolveCallable(name: String, lookup: LookupSpec = LookupSpec()): CallableHandle

    /** Resolve a member value on [receiver]. */
    fun resolveMemberVal(
        receiver: Obj,
        name: String,
        lookup: LookupSpec = LookupSpec()
    ): MemberValHandle

    /** Resolve a mutable member on [receiver]. */
    fun resolveMemberVar(
        receiver: Obj,
        name: String,
        lookup: LookupSpec = LookupSpec()
    ): MemberVarHandle

    /** Resolve a member callable on [receiver]. */
    fun resolveMemberCallable(
        receiver: Obj,
        name: String,
        lookup: LookupSpec = LookupSpec()
    ): MemberCallableHandle

    /**
     * Resolve an extension function treated as a member for reflection.
     *
     * This uses the extension wrapper name (same rules as Lyng compiler).
     */
    fun resolveExtensionCallable(
        receiverClass: ObjClass,
        name: String,
        lookup: LookupSpec = LookupSpec()
    ): MemberCallableHandle

    /** Debug: resolve locals by name (optional, for tooling). */
    fun resolveLocalVal(name: String): ValHandle
    /** Debug: resolve mutable locals by name (optional, for tooling). */
    fun resolveLocalVar(name: String): VarHandle

    /** Debug: access raw record handles if needed. */
    fun resolveRecord(name: String, lookup: LookupSpec = LookupSpec()): RecordHandle
}

/**
 * Convenience: call by name with implicit caching in resolver implementation.
 *
 * Implemented by the default resolver; useful for lightweight call-by-name flows.
 */
interface BridgeCallByName {
    /** Resolve and call [name] with [args] using [lookup]. */
    suspend fun callByName(
        scope: ScopeFacade,
        name: String,
        args: Arguments = Arguments.EMPTY,
        lookup: LookupSpec = LookupSpec()
    ): Obj
}

/**
 * Optional typed wrapper (sugar) around [ValHandle].
 *
 * Performs a runtime cast to [T] and raises a class cast error on mismatch.
 */
interface TypedHandle<T : Obj> : ValHandle {
    /** Read value and cast it to [T]. */
    suspend fun getTyped(scope: ScopeFacade): T
}

/**
 * Factory for bridge resolver.
 *
 * Prefer this over ad-hoc lookups when writing Kotlin extensions or tooling.
 */
fun ScopeFacade.resolver(): BridgeResolver = BridgeResolverImpl(this)

private class BridgeResolverImpl(
    private val facade: ScopeFacade,
    private val receiverView: ReceiverView? = null
) : BridgeResolver, BridgeCallByName {
    private val cachedCallables: MutableMap<String, CallableHandle> = LinkedHashMap()

    override val pos: Pos
        get() = facade.pos

    override fun selfAs(type: ObjClass): BridgeResolver = BridgeResolverImpl(facade, ReceiverView(type = type))

    override fun selfAs(typeName: String): BridgeResolver = BridgeResolverImpl(facade, ReceiverView(typeName = typeName))

    override fun resolveVal(name: String, lookup: LookupSpec): ValHandle =
        LocalValHandle(this, name, lookup)

    override fun resolveVar(name: String, lookup: LookupSpec): VarHandle =
        LocalVarHandle(this, name, lookup)

    override fun resolveCallable(name: String, lookup: LookupSpec): CallableHandle =
        LocalCallableHandle(this, name, lookup)

    override fun resolveMemberVal(receiver: Obj, name: String, lookup: LookupSpec): MemberValHandle =
        MemberValHandleImpl(this, receiver, name, lookup.receiverView ?: receiverView)

    override fun resolveMemberVar(receiver: Obj, name: String, lookup: LookupSpec): MemberVarHandle =
        MemberVarHandleImpl(this, receiver, name, lookup.receiverView ?: receiverView)

    override fun resolveMemberCallable(receiver: Obj, name: String, lookup: LookupSpec): MemberCallableHandle =
        MemberCallableHandleImpl(this, receiver, name, lookup.receiverView ?: receiverView)

    override fun resolveExtensionCallable(receiverClass: ObjClass, name: String, lookup: LookupSpec): MemberCallableHandle =
        ExtensionCallableHandleImpl(this, receiverClass, name, lookup)

    override fun resolveLocalVal(name: String): ValHandle =
        LocalValHandle(this, name, LookupSpec(targets = setOf(LookupTarget.CurrentFrame)))

    override fun resolveLocalVar(name: String): VarHandle =
        LocalVarHandle(this, name, LookupSpec(targets = setOf(LookupTarget.CurrentFrame)))

    override fun resolveRecord(name: String, lookup: LookupSpec): RecordHandle =
        RecordHandleImpl(this, name, lookup)

    override suspend fun callByName(scope: ScopeFacade, name: String, args: Arguments, lookup: LookupSpec): Obj {
        val handle = cachedCallables.getOrPut(name) { resolveCallable(name, lookup) }
        return handle.call(scope, args)
    }

    fun facade(): ScopeFacade = facade

    fun resolveLocalRecord(scope: Scope, name: String, lookup: LookupSpec): ObjRecord {
        val caller = scope.currentClassCtx
        if (LookupTarget.CurrentFrame in lookup.targets) {
            scope.tryGetLocalRecord(scope, name, caller)?.let { return it }
        }
        if (LookupTarget.ParentChain in lookup.targets) {
            scope.chainLookupIgnoreClosure(name, followClosure = false, caller = caller)?.let { return it }
        }
        if (LookupTarget.ModuleFrame in lookup.targets) {
            findModuleScope(scope)?.let { module ->
                module.tryGetLocalRecord(module, name, caller)?.let { return it }
            }
        }
        facade.raiseSymbolNotFound(name)
    }

    fun resolveReceiver(scope: Scope, receiver: Obj, view: ReceiverView?): Obj {
        if (view == null) return receiver
        if (receiver !== scope.thisObj) return receiver
        val target = when {
            view.type != null -> scope.thisVariants.firstOrNull { it.isInstanceOf(view.type) }
            view.typeName != null -> scope.thisVariants.firstOrNull { it.isInstanceOf(view.typeName) }
            else -> null
        }
        return target ?: facade.raiseSymbolNotFound(view.typeName ?: view.type?.className ?: "<receiver>")
    }

    fun resolveMemberRecord(scope: Scope, receiver: Obj, name: String): MemberResolution {
        if (receiver is ObjClass) {
            val rec = receiver.classScope?.objects?.get(name) ?: receiver.members[name]
                ?: facade.raiseSymbolNotFound("member $name not found on ${receiver.className}")
            val decl = rec.declaringClass ?: receiver
            if (!canAccessMember(rec.visibility, decl, scope.currentClassCtx, name)) {
                facade.raiseError(
                    ObjIllegalAccessException(
                        scope,
                        "can't access ${name}: not visible (declared in ${decl.className}, caller ${scope.currentClassCtx?.className ?: "?"})"
                    )
                )
            }
            return MemberResolution(rec, decl, receiver, rec.fieldId, rec.methodId)
        }
        val cls = receiver.objClass
        val resolved = cls.resolveInstanceMember(name)
            ?: facade.raiseSymbolNotFound("member $name not found on ${cls.className}")
        val decl = resolved.declaringClass
        if (!canAccessMember(resolved.record.visibility, decl, scope.currentClassCtx, name)) {
            facade.raiseError(
                ObjIllegalAccessException(
                    scope,
                    "can't access ${name}: not visible (declared in ${decl.className}, caller ${scope.currentClassCtx?.className ?: "?"})"
                )
            )
        }
        val fieldId = if (resolved.record.type == ObjRecord.Type.Field ||
            resolved.record.type == ObjRecord.Type.ConstructorField
        ) {
            resolved.record.fieldId ?: cls.instanceFieldIdMap()[name]
        } else null
        val methodId = if (resolved.record.type == ObjRecord.Type.Fun ||
            resolved.record.type == ObjRecord.Type.Property ||
            resolved.record.type == ObjRecord.Type.Delegated
        ) {
            resolved.record.methodId ?: cls.instanceMethodIdMap(includeAbstract = true)[name]
        } else null
        return MemberResolution(resolved.record, decl, receiver.objClass, fieldId, methodId)
    }

    private fun findModuleScope(scope: Scope): ModuleScope? {
        var s: Scope? = scope
        var hops = 0
        while (s != null && hops++ < 1024) {
            if (s is ModuleScope) return s
            s = s.parent
        }
        return null
    }
}

private data class LocalResolution(
    val record: ObjRecord,
    val frameId: Long
)

private data class MemberResolution(
    val record: ObjRecord,
    val declaringClass: ObjClass,
    val receiverClass: ObjClass,
    val fieldId: Int?,
    val methodId: Int?
)

private abstract class LocalHandleBase(
    protected val resolver: BridgeResolverImpl,
    override val name: String,
    private val lookup: LookupSpec
) : BridgeHandle {
    private var cached: LocalResolution? = null

    protected fun resolve(scope: Scope): ObjRecord {
        val cachedLocal = cached
        if (cachedLocal != null && cachedLocal.frameId == scope.frameId) {
            return cachedLocal.record
        }
        val rec = resolver.resolveLocalRecord(scope, name, lookup)
        cached = LocalResolution(rec, scope.frameId)
        return rec
    }
}

private class LocalValHandle(
    resolver: BridgeResolverImpl,
    name: String,
    lookup: LookupSpec
) : LocalHandleBase(resolver, name, lookup), ValHandle {
    override suspend fun get(scope: ScopeFacade): Obj {
        val real = scope.requireScope()
        val rec = resolve(real)
        return real.resolve(rec, name)
    }
}

private class LocalVarHandle(
    resolver: BridgeResolverImpl,
    name: String,
    lookup: LookupSpec
) : LocalHandleBase(resolver, name, lookup), VarHandle {
    override suspend fun get(scope: ScopeFacade): Obj {
        val real = scope.requireScope()
        val rec = resolve(real)
        return real.resolve(rec, name)
    }

    override suspend fun set(scope: ScopeFacade, value: Obj) {
        val real = scope.requireScope()
        val rec = resolve(real)
        real.assign(rec, name, value)
    }
}

private class LocalCallableHandle(
    resolver: BridgeResolverImpl,
    name: String,
    lookup: LookupSpec
) : LocalHandleBase(resolver, name, lookup), CallableHandle {
    override suspend fun call(scope: ScopeFacade, args: Arguments, newThisObj: Obj?): Obj {
        val real = scope.requireScope()
        val rec = resolve(real)
        val callee = rec.value
        return scope.call(callee, args, newThisObj ?: rec.receiver)
    }
}

private abstract class MemberHandleBase(
    protected val resolver: BridgeResolverImpl,
    receiver: Obj,
    override val name: String,
    override val receiverView: ReceiverView?
) : MemberHandle {
    private val baseReceiver: Obj = receiver
    private var cachedResolution: MemberResolution? = null
    private var cachedDeclaringClass: ObjClass? = null

    protected fun resolve(scope: Scope): Pair<Obj, MemberResolution> {
        val resolvedReceiver = resolver.resolveReceiver(scope, baseReceiver, receiverView)
        val cached = cachedResolution
        if (cached != null && resolvedReceiver.objClass === cached.receiverClass) {
            cachedDeclaringClass = cached.declaringClass
            return Pair(resolvedReceiver, cached)
        }
        val res = resolver.resolveMemberRecord(scope, resolvedReceiver, name)
        cachedResolution = res
        cachedDeclaringClass = res.declaringClass
        return Pair(resolvedReceiver, res)
    }

    protected fun declaringClass(): ObjClass? = cachedDeclaringClass
}

private class MemberValHandleImpl(
    resolver: BridgeResolverImpl,
    receiver: Obj,
    name: String,
    receiverView: ReceiverView?
) : MemberHandleBase(resolver, receiver, name, receiverView), MemberValHandle {
    override val declaringClass: ObjClass?
        get() = declaringClass()

    override suspend fun get(scope: ScopeFacade): Obj {
        val real = scope.requireScope()
        val (receiver, res) = resolve(real)
        val rec = resolveMemberRecordFast(receiver, res)
        return receiver.resolveRecord(real, rec, name, res.declaringClass).value
    }
}

private class MemberVarHandleImpl(
    resolver: BridgeResolverImpl,
    receiver: Obj,
    name: String,
    receiverView: ReceiverView?
) : MemberHandleBase(resolver, receiver, name, receiverView), MemberVarHandle {
    override val declaringClass: ObjClass?
        get() = declaringClass()

    override suspend fun get(scope: ScopeFacade): Obj {
        val real = scope.requireScope()
        val (receiver, res) = resolve(real)
        val rec = resolveMemberRecordFast(receiver, res)
        return receiver.resolveRecord(real, rec, name, res.declaringClass).value
    }

    override suspend fun set(scope: ScopeFacade, value: Obj) {
        val real = scope.requireScope()
        val (receiver, res) = resolve(real)
        val rec = resolveMemberRecordFast(receiver, res)
        assignMemberRecord(real, receiver, res.declaringClass, rec, name, value)
    }
}

private class MemberCallableHandleImpl(
    resolver: BridgeResolverImpl,
    receiver: Obj,
    name: String,
    receiverView: ReceiverView?
) : MemberHandleBase(resolver, receiver, name, receiverView), MemberCallableHandle {
    override val declaringClass: ObjClass?
        get() = declaringClass()

    override suspend fun call(scope: ScopeFacade, args: Arguments, newThisObj: Obj?): Obj {
        val real = scope.requireScope()
        val (receiver, res) = resolve(real)
        val rec = resolveMemberRecordFast(receiver, res)
        if (rec.type != ObjRecord.Type.Fun) {
            scope.raiseError("member $name is not callable")
        }
        return rec.value.invoke(real, receiver, args, res.declaringClass)
    }
}

private class ExtensionCallableHandleImpl(
    private val resolver: BridgeResolverImpl,
    private val receiverClass: ObjClass,
    override val name: String,
    private val lookup: LookupSpec
) : MemberCallableHandle {
    override val receiverView: ReceiverView?
        get() = null
    override val declaringClass: ObjClass?
        get() = receiverClass

    override suspend fun call(scope: ScopeFacade, args: Arguments, newThisObj: Obj?): Obj {
        val real = scope.requireScope()
        val wrapperName = extensionCallableName(receiverClass.className, name)
        val rec = resolver.resolveLocalRecord(real, wrapperName, lookup)
        val receiver = newThisObj ?: real.thisObj
        val callArgs = Arguments(listOf(receiver) + args.list)
        return scope.call(rec.value, callArgs)
    }
}

private class RecordHandleImpl(
    private val resolver: BridgeResolverImpl,
    override val name: String,
    private val lookup: LookupSpec
) : RecordHandle {
    override fun record(): ObjRecord {
        val scope = resolver.facade().requireScope()
        return resolver.resolveLocalRecord(scope, name, lookup)
    }
}

private class TypedHandleImpl<T : Obj>(
    private val inner: ValHandle,
    private val clazzName: String
) : TypedHandle<T> {
    override val name: String
        get() = inner.name

    override suspend fun get(scope: ScopeFacade): Obj = inner.get(scope)

    @Suppress("UNCHECKED_CAST")
    override suspend fun getTyped(scope: ScopeFacade): T {
        val value = inner.get(scope)
        return (value as? T)
            ?: scope.raiseClassCastError("Expected $clazzName, got ${value.objClass.className}")
    }
}

private fun resolveMemberRecordFast(receiver: Obj, res: MemberResolution): ObjRecord {
    val inst = receiver as? ObjInstance
    if (inst != null) {
        res.fieldId?.let { inst.fieldRecordForId(it)?.let { return it } }
        res.methodId?.let { inst.methodRecordForId(it)?.let { return it } }
    }
    return res.record
}

private suspend fun assignMemberRecord(
    scope: Scope,
    receiver: Obj,
    declaringClass: ObjClass,
    rec: ObjRecord,
    name: String,
    value: Obj
) {
    val caller = scope.currentClassCtx
    if (!canAccessMember(rec.effectiveWriteVisibility, declaringClass, caller, name)) {
        scope.raiseError(
            ObjIllegalAccessException(
                scope,
                "can't assign ${name}: not visible (declared in ${declaringClass.className}, caller ${caller?.className ?: "?"})"
            )
        )
    }
    when {
        rec.type == ObjRecord.Type.Delegated -> {
            val del = rec.delegate ?: scope.raiseError("Internal error: delegated property $name has no delegate")
            val th = if (receiver === ObjVoid) net.sergeych.lyng.obj.ObjNull else receiver
            del.invokeInstanceMethod(scope, "setValue", Arguments(th, ObjString(name), value))
        }
        rec.value is ObjProperty || rec.type == ObjRecord.Type.Property -> {
            val prop = rec.value as? ObjProperty
                ?: scope.raiseError("Expected ObjProperty for property member $name")
            prop.callSetter(scope, receiver, value, declaringClass)
        }
        rec.isMutable -> {
            val slotRef = rec.value
            if (slotRef is net.sergeych.lyng.FrameSlotRef) {
                if (!rec.isMutable && slotRef.read() !== ObjUnset) scope.raiseError("can't reassign val $name")
                slotRef.write(value)
            } else {
                rec.value = value
            }
        }
        else -> scope.raiseError("can't assign to read-only field: $name")
    }
}
