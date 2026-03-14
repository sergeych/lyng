/*
 * Draft: Kotlin bridge facade + handle API (declarations only)
 * For discussion; not wired into runtime yet.
 */

package net.sergeych.lyng.bridge

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.ObjClass
import net.sergeych.lyng.Pos
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjRecord

/** Where to resolve names from. */
enum class LookupTarget {
    CurrentFrame,
    ParentChain,
    ModuleFrame
}

/** Explicit receiver view (like this@Base). */
data class ReceiverView(
    val type: ObjClass? = null,
    val typeName: String? = null
)

/** Lookup rules for bridge resolution. */
data class LookupSpec(
    val targets: Set<LookupTarget> = setOf(LookupTarget.CurrentFrame, LookupTarget.ModuleFrame),
    val receiverView: ReceiverView? = null
)

/** Base handle type. */
sealed interface BridgeHandle {
    val name: String
}

/** Read-only value handle. */
interface ValHandle : BridgeHandle {
    suspend fun get(scope: ScopeFacade): Obj
}

/** Read/write value handle. */
interface VarHandle : ValHandle {
    suspend fun set(scope: ScopeFacade, value: Obj)
}

/** Callable handle (function/closure/method). */
interface CallableHandle : BridgeHandle {
    suspend fun call(scope: ScopeFacade, args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null): Obj
}

/** Member handle resolved against an instance or receiver view. */
interface MemberHandle : BridgeHandle {
    val declaringClass: ObjClass?
    val receiverView: ReceiverView?
}

/** Member field/property. */
interface MemberValHandle : MemberHandle, ValHandle

/** Member var/property with write access. */
interface MemberVarHandle : MemberHandle, VarHandle

/** Member callable (method or extension). */
interface MemberCallableHandle : MemberHandle, CallableHandle

/** Direct record handle (debug/inspection). */
interface RecordHandle : BridgeHandle {
    fun record(): ObjRecord
}

/** Bridge resolver API (entry point for Kotlin bindings). */
interface BridgeResolver {
    val pos: Pos

    fun selfAs(type: ObjClass): BridgeResolver
    fun selfAs(typeName: String): BridgeResolver

    fun resolveVal(name: String, lookup: LookupSpec = LookupSpec()): ValHandle
    fun resolveVar(name: String, lookup: LookupSpec = LookupSpec()): VarHandle
    fun resolveCallable(name: String, lookup: LookupSpec = LookupSpec()): CallableHandle

    fun resolveMemberVal(
        receiver: Obj,
        name: String,
        lookup: LookupSpec = LookupSpec()
    ): MemberValHandle

    fun resolveMemberVar(
        receiver: Obj,
        name: String,
        lookup: LookupSpec = LookupSpec()
    ): MemberVarHandle

    fun resolveMemberCallable(
        receiver: Obj,
        name: String,
        lookup: LookupSpec = LookupSpec()
    ): MemberCallableHandle

    /** Extension function treated as a member for reflection. */
    fun resolveExtensionCallable(
        receiverClass: ObjClass,
        name: String,
        lookup: LookupSpec = LookupSpec()
    ): MemberCallableHandle

    /** Debug: resolve locals by name (optional, for tooling). */
    fun resolveLocalVal(name: String): ValHandle
    fun resolveLocalVar(name: String): VarHandle

    /** Debug: access raw record handles if needed. */
    fun resolveRecord(name: String, lookup: LookupSpec = LookupSpec()): RecordHandle
}

/** Convenience: call by name with implicit caching in resolver implementation. */
interface BridgeCallByName {
    suspend fun callByName(
        scope: ScopeFacade,
        name: String,
        args: Arguments = Arguments.EMPTY,
        lookup: LookupSpec = LookupSpec()
    ): Obj
}

/** Optional typed wrappers (sugar). */
interface TypedHandle<T : Obj> : ValHandle {
    suspend fun getTyped(scope: ScopeFacade): T
}

/** Debug view of frame stack for tooling. */
interface FrameStackView {
    fun frames(): List<FrameView>
}

interface FrameView {
    val pos: Pos
    val thisObj: Obj
    fun locals(): Map<String, ObjRecord>
}

/** Binder API for Kotlin -> Lyng extern wiring. */
interface ClassBridgeBinder {
    var classData: Any?

    fun init(block: suspend BridgeInstanceContext.(ScopeFacade) -> Unit)
    fun initWithInstance(block: suspend ScopeFacade.() -> Unit)
    @Deprecated("Use receiver-form initWithInstance and read thisObj from ScopeFacade")
    fun initWithInstance(block: suspend (ScopeFacade, Obj) -> Unit)

    fun addFun(name: String, impl: suspend ScopeFacade.() -> Obj)
    @Deprecated("Use receiver-form addFun and read thisObj/args from ScopeFacade")
    fun addFun(name: String, impl: suspend (ScopeFacade, Obj, Arguments) -> Obj)
    fun addVal(name: String, impl: suspend ScopeFacade.() -> Obj)
    @Deprecated("Use receiver-form addVal and read thisObj from ScopeFacade")
    fun addVal(name: String, impl: suspend (ScopeFacade, Obj) -> Obj)
    fun addVar(
        name: String,
        get: suspend ScopeFacade.() -> Obj,
        set: suspend ScopeFacade.(Obj) -> Unit
    )
    @Deprecated("Use receiver-form addVar and read thisObj from ScopeFacade")
    fun addVar(
        name: String,
        get: suspend (ScopeFacade, Obj) -> Obj,
        set: suspend (ScopeFacade, Obj, Obj) -> Unit
    )
}

/** Instance receiver context for init/impl blocks. */
interface BridgeInstanceContext {
    val instance: Obj
    var data: Any?
}
