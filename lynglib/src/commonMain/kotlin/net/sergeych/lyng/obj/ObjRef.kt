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

@file:Suppress("INLINE_NOT_NEEDED", "REDUNDANT_INLINE")

package net.sergeych.lyng.obj

import net.sergeych.lyng.*
import net.sergeych.lyng.FrameSlotRef
import net.sergeych.lyng.RecordSlotRef

/**
 * A reference to a value with optional write-back path.
 * This is a sealed, allocation-light alternative to the lambda-based Accessor.
 */
sealed interface ObjRef {
    suspend fun get(scope: Scope): ObjRecord

    /**
     * Fast path for evaluating an expression to a raw Obj value without wrapping it into ObjRecord.
     * Default implementation calls [get] and returns its value. Nodes can override to avoid record traffic.
     */
    suspend fun evalValue(scope: Scope): Obj {
        scope.raiseIllegalState("bytecode-only execution is required; ObjRef evaluation is disabled")
    }
    suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        throw ScriptError(pos, "can't assign value")
    }

    /**
     * Calls [block] for each variable name that this reference targets for writing.
     * Used for declaring local variables in destructuring.
     */
    fun forEachVariable(block: (String) -> Unit) {}

    /**
     * Calls [block] for each variable name that this reference targets for writing,
     * including its source position if available.
     */
    fun forEachVariableWithPos(block: (String, Pos) -> Unit) {
        forEachVariable { block(it, Pos.UNKNOWN) }
    }
}

private fun Scope.raiseObjRefEvalDisabled(): Nothing {
    return raiseIllegalState("bytecode-only execution is required; ObjRef evaluation is disabled")
}

/** Runtime-computed read-only reference backed by a lambda. */
open class ValueFnRef(private val fn: suspend (Scope) -> ObjRecord) : ObjRef {
    internal fun valueFn(): suspend (Scope) -> ObjRecord = fn

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()
}

/** Compile-time supported ::class operator reference. */
class ClassOperatorRef(val target: ObjRef, val pos: Pos) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()
}

/** Unary operations supported by ObjRef. */
enum class UnaryOp { NOT, POSITIVE, NEGATE, BITNOT }

/** Binary operations supported by ObjRef. */
enum class BinOp {
    OR, AND,
    EQARROW, EQ, NEQ, REF_EQ, REF_NEQ, MATCH, NOTMATCH,
    LTE, LT, GTE, GT,
    IN, NOTIN,
    IS, NOTIS,
    SHUTTLE,
    // bitwise
    BAND, BXOR, BOR,
    // shifts
    SHL, SHR,
    // arithmetic
    PLUS, MINUS, STAR, SLASH, PERCENT
}

/** R-value reference for unary operations. */
class UnaryOpRef(internal val op: UnaryOp, internal val a: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/** R-value reference for binary operations. */
class BinaryOpRef(internal val op: BinOp, internal val left: ObjRef, internal val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/** Conditional (ternary) operator reference: cond ? a : b */
class ConditionalRef(
    internal val condition: ObjRef,
    internal val ifTrue: ObjRef,
    internal val ifFalse: ObjRef
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()

    private suspend fun evalCondition(scope: Scope): ObjRef {
        val condVal = condition.evalValue(scope)
        val condTrue = when (condVal) {
            is ObjBool -> condVal.value
            is ObjInt -> condVal.value != 0L
            else -> condVal.toBool()
        }
        return if (condTrue) ifTrue else ifFalse
    }
}

/** Cast operator reference: left `as` rightType or `as?` (nullable). */
class CastRef(
    private val valueRef: ObjRef,
    private val typeRef: ObjRef,
    private val isNullable: Boolean,
    private val atPos: Pos,
) : ObjRef {
    internal fun castValueRef(): ObjRef = valueRef
    internal fun castTypeRef(): ObjRef = typeRef
    internal fun castIsNullable(): Boolean = isNullable
    internal fun castPos(): Pos = atPos

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/** Type expression reference used for `is` checks (including unions/intersections). */
class TypeDeclRef(private val typeDecl: TypeDecl, private val atPos: Pos) : ObjRef {
    internal fun decl(): TypeDecl = typeDecl
    internal fun pos(): Pos = atPos

    override fun forEachVariable(block: (String) -> Unit) {}

    override fun forEachVariableWithPos(block: (String, Pos) -> Unit) {}

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/** Qualified `this@Type`: resolves to a view of current `this` starting dispatch from the ancestor Type. */
class QualifiedThisRef(val typeName: String, private val atPos: Pos) : ObjRef {
    internal fun pos(): Pos = atPos
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()
}

private suspend fun resolveQualifiedThisInstance(scope: Scope, typeName: String): Pair<ObjInstance, ObjClass> {
    val t = scope[typeName]?.value as? ObjClass
        ?: scope.raiseError("unknown type $typeName")
    var s: Scope? = scope
    while (s != null) {
        val inst = s.thisObj as? ObjInstance
        if (inst != null && (inst.objClass === t || inst.objClass.allParentsSet.contains(t))) {
            return inst to t
        }
        s = s.parent
    }
    scope.raiseClassCastError(
        "No instance of type ${t.className} found in the scope chain"
    )
}

/**
 * Fast path for direct `this@Type.name` access using slot maps when possible.
 */
class QualifiedThisFieldSlotRef(
    private val typeName: String,
    val name: String,
    private val fieldId: Int?,
    private val methodId: Int?,
    private val isOptional: Boolean
) : ObjRef {
    internal fun fieldId(): Int? = fieldId
    internal fun methodId(): Int? = methodId
    internal fun receiverTypeName(): String = typeName
    internal fun optional(): Boolean = isOptional

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) = scope.raiseObjRefEvalDisabled()

    private suspend fun assignToRecord(scope: Scope, rec: ObjRecord, newValue: Obj) {
        if ((rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField) && !rec.isAbstract) {
            if (!rec.isMutable && rec.value !== ObjUnset) {
                ObjIllegalAssignmentException(scope, "can't reassign val ${rec.memberName ?: name}").raise()
            }
            if (rec.value.assign(scope, newValue) == null) rec.value = newValue
        } else {
            scope.assign(rec, rec.memberName ?: name, newValue)
        }
    }
}

/**
 * Fast path for direct `this@Type.method(...)` calls using slots when the qualifier is the
 * dynamic class. Otherwise falls back to a qualified view dispatch.
 */
class QualifiedThisMethodSlotCallRef(
    private val typeName: String,
    private val name: String,
    private val methodId: Int?,
    private val args: List<ParsedArgument>,
    private val tailBlock: Boolean,
    private val isOptional: Boolean
) : ObjRef {
    internal fun receiverTypeName(): String = typeName
    internal fun methodName(): String = name
    internal fun methodId(): Int? = methodId
    internal fun arguments(): List<ParsedArgument> = args
    internal fun hasTailBlock(): Boolean = tailBlock
    internal fun optionalInvoke(): Boolean = isOptional

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/** Assignment compound op: target op= value */
class AssignOpRef(
    internal val op: BinOp,
    internal val target: ObjRef,
    internal val value: ObjRef,
    private val atPos: Pos,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()
}

/** Pre/post ++/-- on l-values */
class IncDecRef(
    internal val target: ObjRef,
    internal val isIncrement: Boolean,
    internal val isPost: Boolean,
    private val atPos: Pos,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()
}

/** Elvis operator reference: a ?: b */
class ElvisRef(internal val left: ObjRef, internal val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()
}

/** Logical OR with short-circuit: a || b */
class LogicalOrRef(private val left: ObjRef, private val right: ObjRef) : ObjRef {
    internal fun left(): ObjRef = left
    internal fun right(): ObjRef = right

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/** Logical AND with short-circuit: a && b */
class LogicalAndRef(private val left: ObjRef, private val right: ObjRef) : ObjRef {
    internal fun left(): ObjRef = left
    internal fun right(): ObjRef = right

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/**
 * Read-only reference that always returns the same cached record.
 */
class ConstRef(private val record: ObjRecord) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()
    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
    // Expose constant value for compiler constant folding (pure, read-only)
    val constValue: Obj get() = record.value
}

/**
 * Reference to an object's field with optional chaining.
 */
class FieldRef(
    val target: ObjRef,
    val name: String,
    val isOptional: Boolean,
) : ObjRef {
    // 4-entry PIC for reads/writes (guarded by PerfFlags.FIELD_PIC)
    // Reads
    private var rKey1: Long = 0L; private var rVer1: Int = -1; private var rGetter1: (suspend (Obj, Scope) -> ObjRecord)? = null
    private var rKey2: Long = 0L; private var rVer2: Int = -1; private var rGetter2: (suspend (Obj, Scope) -> ObjRecord)? = null
    private var rKey3: Long = 0L; private var rVer3: Int = -1; private var rGetter3: (suspend (Obj, Scope) -> ObjRecord)? = null
    private var rKey4: Long = 0L; private var rVer4: Int = -1; private var rGetter4: (suspend (Obj, Scope) -> ObjRecord)? = null

    // Writes
    private var wKey1: Long = 0L; private var wVer1: Int = -1; private var wSetter1: (suspend (Obj, Scope, Obj) -> Unit)? = null
    private var wKey2: Long = 0L; private var wVer2: Int = -1; private var wSetter2: (suspend (Obj, Scope, Obj) -> Unit)? = null
    private var wKey3: Long = 0L; private var wVer3: Int = -1; private var wSetter3: (suspend (Obj, Scope, Obj) -> Unit)? = null
    private var wKey4: Long = 0L; private var wVer4: Int = -1; private var wSetter4: (suspend (Obj, Scope, Obj) -> Unit)? = null

    // Transient per-step cache to optimize read-then-write sequences within the same frame
    private var tKey: Long = 0L; private var tVer: Int = -1; private var tFrameId: Long = -1L; private var tRecord: ObjRecord? = null

    // Adaptive PIC (2→4) for reads/writes
    private var rAccesses: Int = 0; private var rMisses: Int = 0; private var rPromotedTo4: Boolean = false
    private var wAccesses: Int = 0; private var wMisses: Int = 0; private var wPromotedTo4: Boolean = false
    @Suppress("NOTHING_TO_INLINE")
    private inline fun size4ReadsEnabled(): Boolean =
        PerfFlags.FIELD_PIC_SIZE_4 ||
            (PerfFlags.PIC_ADAPTIVE_2_TO_4 && rPromotedTo4)
    @Suppress("NOTHING_TO_INLINE")
    private inline fun size4WritesEnabled(): Boolean =
        PerfFlags.FIELD_PIC_SIZE_4 ||
            (PerfFlags.PIC_ADAPTIVE_2_TO_4 && wPromotedTo4)
    private fun noteReadHit() {
        if (!PerfFlags.PIC_ADAPTIVE_2_TO_4) return
        val a = (rAccesses + 1).coerceAtMost(1_000_000)
        rAccesses = a
    }
    private fun noteReadMiss() {
        if (!PerfFlags.PIC_ADAPTIVE_2_TO_4) return
        val a = (rAccesses + 1).coerceAtMost(1_000_000)
        rAccesses = a
        rMisses = (rMisses + 1).coerceAtMost(1_000_000)
        if (!rPromotedTo4 && a >= 256) {
            // promote if miss rate > 20%
            if (rMisses * 100 / a > 20) rPromotedTo4 = true
            // reset counters after decision
            rAccesses = 0; rMisses = 0
        }
    }
    private fun noteWriteHit() {
        if (!PerfFlags.PIC_ADAPTIVE_2_TO_4) return
        val a = (wAccesses + 1).coerceAtMost(1_000_000)
        wAccesses = a
    }
    private fun noteWriteMiss() {
        if (!PerfFlags.PIC_ADAPTIVE_2_TO_4) return
        val a = (wAccesses + 1).coerceAtMost(1_000_000)
        wAccesses = a
        wMisses = (wMisses + 1).coerceAtMost(1_000_000)
        if (!wPromotedTo4 && a >= 256) {
            if (wMisses * 100 / a > 20) wPromotedTo4 = true
            wAccesses = 0; wMisses = 0
        }
    }

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) = scope.raiseObjRefEvalDisabled()

    private fun receiverKeyAndVersion(obj: Obj): Pair<Long, Int> = when (obj) {
        is ObjInstance -> obj.objClass.classId to obj.objClass.layoutVersion
        is ObjClass -> obj.classId to obj.layoutVersion
        else -> 0L to -1 // no caching for primitives/dynamics without stable shape
    }

    private suspend fun resolveValue(scope: Scope, base: Obj, rec: ObjRecord): Obj {
        if (rec.type == ObjRecord.Type.Delegated || rec.value is ObjProperty || rec.type == ObjRecord.Type.Property) {
            val receiver = rec.receiver ?: base
            return receiver.resolveRecord(scope, rec, name, rec.declaringClass).value
        }
        if (rec.receiver != null && rec.declaringClass != null) {
            return rec.receiver!!.resolveRecord(scope, rec, name, rec.declaringClass).value
        }
        if (rec.type == ObjRecord.Type.Fun && !rec.isAbstract) {
            val receiver = rec.receiver ?: base
            return rec.value.invoke(scope, receiver, Arguments.EMPTY, rec.declaringClass)
        }
        return rec.value
    }

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/**
 * Fast path for direct `this.name` access using slot maps.
 * Falls back to normal member resolution when needed.
 */
class ThisFieldSlotRef(
    val name: String,
    private val fieldId: Int?,
    private val methodId: Int?,
    private val isOptional: Boolean
) : ObjRef {
    internal fun fieldId(): Int? = fieldId
    internal fun methodId(): Int? = methodId
    internal fun optional(): Boolean = isOptional

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) = scope.raiseObjRefEvalDisabled()

    private suspend fun assignToRecord(
        scope: Scope,
        rec: ObjRecord,
        newValue: Obj
    ) {
        if ((rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField) && !rec.isAbstract) {
            if (!rec.isMutable && rec.value !== ObjUnset) {
                ObjIllegalAssignmentException(scope, "can't reassign val ${rec.memberName ?: name}").raise()
            }
            if (rec.value.assign(scope, newValue) == null) rec.value = newValue
        } else {
            scope.assign(rec, rec.memberName ?: name, newValue)
        }
    }
}

/**
 * Reference to index access (a[i]) with optional chaining.
 */
class IndexRef(
    private val target: ObjRef,
    private val index: ObjRef,
    private val isOptional: Boolean,
) : ObjRef {
    internal val targetRef: ObjRef get() = target
    internal val indexRef: ObjRef get() = index
    internal val optionalRef: Boolean get() = isOptional
    // Tiny 4-entry PIC for index reads (guarded implicitly by RVAL_FASTPATH); move-to-front on hits
    private var rKey1: Long = 0L; private var rVer1: Int = -1; private var rGetter1: (suspend (Obj, Scope, Obj) -> Obj)? = null
    private var rKey2: Long = 0L; private var rVer2: Int = -1; private var rGetter2: (suspend (Obj, Scope, Obj) -> Obj)? = null
    private var rKey3: Long = 0L; private var rVer3: Int = -1; private var rGetter3: (suspend (Obj, Scope, Obj) -> Obj)? = null
    private var rKey4: Long = 0L; private var rVer4: Int = -1; private var rGetter4: (suspend (Obj, Scope, Obj) -> Obj)? = null

    // Tiny 4-entry PIC for index writes
    private var wKey1: Long = 0L; private var wVer1: Int = -1; private var wSetter1: (suspend (Obj, Scope, Obj, Obj) -> Unit)? = null
    private var wKey2: Long = 0L; private var wVer2: Int = -1; private var wSetter2: (suspend (Obj, Scope, Obj, Obj) -> Unit)? = null
    private var wKey3: Long = 0L; private var wVer3: Int = -1; private var wSetter3: (suspend (Obj, Scope, Obj, Obj) -> Unit)? = null
    private var wKey4: Long = 0L; private var wVer4: Int = -1; private var wSetter4: (suspend (Obj, Scope, Obj, Obj) -> Unit)? = null

    private fun receiverKeyAndVersion(obj: Obj): Pair<Long, Int> = when (obj) {
        is ObjInstance -> obj.objClass.classId to obj.objClass.layoutVersion
        is ObjClass -> obj.classId to obj.layoutVersion
        else -> 0L to -1
    }
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) = scope.raiseObjRefEvalDisabled()
}

/**
 * R-value reference that wraps a Statement (used during migration for expressions parsed as Statement).
 */
class StatementRef(internal val statement: Statement) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()
}

/**
 * Direct function call reference: f(args) and optional f?(args).
 */
class CallRef(
    internal val target: ObjRef,
    internal val args: List<ParsedArgument>,
    internal val tailBlock: Boolean,
    internal val isOptionalInvoke: Boolean,
    internal val explicitTypeArgs: List<TypeDecl>? = null,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()
}

/**
 * Instance method call reference: obj.method(args) and optional obj?.method(args).
 */
class MethodCallRef(
    internal val receiver: ObjRef,
    internal val name: String,
    internal val args: List<ParsedArgument>,
    internal val tailBlock: Boolean,
    internal val isOptional: Boolean,
    internal val explicitTypeArgs: List<TypeDecl>? = null,
) : ObjRef {
    // 4-entry PIC for method invocations (guarded by PerfFlags.METHOD_PIC)
    private var mKey1: Long = 0L; private var mVer1: Int = -1; private var mInvoker1: (suspend (Obj, Scope, Arguments) -> Obj)? = null
    private var mKey2: Long = 0L; private var mVer2: Int = -1; private var mInvoker2: (suspend (Obj, Scope, Arguments) -> Obj)? = null
    private var mKey3: Long = 0L; private var mVer3: Int = -1; private var mInvoker3: (suspend (Obj, Scope, Arguments) -> Obj)? = null
    private var mKey4: Long = 0L; private var mVer4: Int = -1; private var mInvoker4: (suspend (Obj, Scope, Arguments) -> Obj)? = null

    // Adaptive PIC (2→4) for methods
    private var mAccesses: Int = 0; private var mMisses: Int = 0; private var mPromotedTo4: Boolean = false
    // Heuristic: windowed miss-rate tracking and temporary freeze back to size=2
    private var mFreezeWindowsLeft: Int = 0
    private var mWindowAccesses: Int = 0
    private var mWindowMisses: Int = 0
    @Suppress("NOTHING_TO_INLINE")
    private inline fun size4MethodsEnabled(): Boolean =
        PerfFlags.METHOD_PIC_SIZE_4 ||
            ((PerfFlags.PIC_ADAPTIVE_2_TO_4 || PerfFlags.PIC_ADAPTIVE_METHODS_ONLY) && mPromotedTo4 && mFreezeWindowsLeft == 0)
    private fun noteMethodHit() {
        if (!(PerfFlags.PIC_ADAPTIVE_2_TO_4 || PerfFlags.PIC_ADAPTIVE_METHODS_ONLY)) return
        val a = (mAccesses + 1).coerceAtMost(1_000_000)
        mAccesses = a
        if (PerfFlags.PIC_ADAPTIVE_HEURISTIC) {
            // Windowed tracking
            mWindowAccesses = (mWindowAccesses + 1).coerceAtMost(1_000_000)
            if (mWindowAccesses >= 256) endHeuristicWindow()
        }
    }
    private fun noteMethodMiss() {
        if (!(PerfFlags.PIC_ADAPTIVE_2_TO_4 || PerfFlags.PIC_ADAPTIVE_METHODS_ONLY)) return
        val a = (mAccesses + 1).coerceAtMost(1_000_000)
        mAccesses = a
        mMisses = (mMisses + 1).coerceAtMost(1_000_000)
        if (!mPromotedTo4 && mFreezeWindowsLeft == 0 && a >= 256) {
            if (mMisses * 100 / a > 20) mPromotedTo4 = true
            mAccesses = 0; mMisses = 0
        }
        if (PerfFlags.PIC_ADAPTIVE_HEURISTIC) {
            mWindowAccesses = (mWindowAccesses + 1).coerceAtMost(1_000_000)
            mWindowMisses = (mWindowMisses + 1).coerceAtMost(1_000_000)
            if (mWindowAccesses >= 256) endHeuristicWindow()
        }
    }

    private fun endHeuristicWindow() {
        // Called only when PIC_ADAPTIVE_HEURISTIC is true
        val accesses = mWindowAccesses
        val misses = mWindowMisses
        // Reset window
        mWindowAccesses = 0
        mWindowMisses = 0
        // Count down freeze if active
        if (mFreezeWindowsLeft > 0) {
            mFreezeWindowsLeft = (mFreezeWindowsLeft - 1).coerceAtLeast(0)
            return
        }
        // If promoted, but still high miss rate, freeze back to 2 for a few windows
        if (mPromotedTo4 && accesses >= 256) {
            val rate = misses * 100 / accesses
            if (rate >= 25) {
                mPromotedTo4 = false
                mFreezeWindowsLeft = 4 // freeze next 4 windows
            }
        }
    }

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()

    private suspend fun performInvoke(
        scope: Scope,
        base: Obj,
        callArgs: Arguments,
        methodPic: Boolean,
        picCounters: Boolean
    ): Obj {
        if (methodPic) {
            val key: Long
            val ver: Int
            when (base) {
                is ObjInstance -> { key = base.objClass.classId; ver = base.objClass.layoutVersion }
                is ObjClass -> { key = base.classId; ver = base.layoutVersion }
                else -> { key = 0L; ver = -1 }
            }
            if (key != 0L) {
                mInvoker1?.let { inv ->
                    if (key == mKey1 && ver == mVer1) {
                        if (picCounters) PerfStats.methodPicHit++
                        noteMethodHit()
                        return inv(base, scope, callArgs)
                    }
                }
                mInvoker2?.let { inv ->
                    if (key == mKey2 && ver == mVer2) {
                        if (picCounters) PerfStats.methodPicHit++
                        noteMethodHit()
                        // move-to-front: promote 2→1
                        val tK = mKey2; val tV = mVer2; val tI = mInvoker2
                        mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                        mKey1 = tK; mVer1 = tV; mInvoker1 = tI
                        return inv(base, scope, callArgs)
                    }
                }
                if (size4MethodsEnabled()) mInvoker3?.let { inv ->
                    if (key == mKey3 && ver == mVer3) {
                        if (picCounters) PerfStats.methodPicHit++
                        noteMethodHit()
                        // move-to-front: promote 3→1
                        val tK = mKey3; val tV = mVer3; val tI = mInvoker3
                        mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                        mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                        mKey1 = tK; mVer1 = tV; mInvoker1 = tI
                        return inv(base, scope, callArgs)
                    }
                }
                if (size4MethodsEnabled()) mInvoker4?.let { inv ->
                    if (key == mKey4 && ver == mVer4) {
                        if (picCounters) PerfStats.methodPicHit++
                        noteMethodHit()
                        // move-to-front: promote 4→1
                        val tK = mKey4; val tV = mVer4; val tI = mInvoker4
                        mKey4 = mKey3; mVer4 = mVer3; mInvoker4 = mInvoker3
                        mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                        mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                        mKey1 = tK; mVer1 = tV; mInvoker1 = tI
                        return inv(base, scope, callArgs)
                    }
                }
                // Slow path
                if (picCounters) PerfStats.methodPicMiss++
                noteMethodMiss()
                val result = try {
                    base.invokeInstanceMethod(scope, name, callArgs)
                } catch (e: ExecutionError) {
                    // Cache-after-miss negative entry for this shape
                    mKey4 = mKey3; mVer4 = mVer3; mInvoker4 = mInvoker3
                    mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                    mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                    mKey1 = key; mVer1 = ver; mInvoker1 = { _, sc, _ -> sc.raiseError(e.message ?: "method not found: $name") }
                    throw e
                }
                // Install move-to-front with a handle-aware invoker; honor PIC size flag
                if (size4MethodsEnabled()) {
                    mKey4 = mKey3; mVer4 = mVer3; mInvoker4 = mInvoker3
                    mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                }
                mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                when (base) {
                    is ObjInstance -> {
                        // Prefer resolved class member to avoid per-call lookup on hit
                        // BUT only if it's NOT a root object member (which can be shadowed by extensions)
                        val cls0 = base.objClass
                        val keyInScope = cls0.publicMemberResolution[name]
                        val methodSlot = if (keyInScope != null) cls0.methodSlotForKey(keyInScope) else null
                        val fastRec = if (methodSlot != null) {
                            val idx = methodSlot.slot
                            if (idx >= 0 && idx < base.methodSlots.size) base.methodSlots[idx] else null
                        } else if (keyInScope != null) {
                            base.methodRecordForKey(keyInScope) ?: base.instanceScope.objects[keyInScope]
                        } else null
                        val resolved = if (fastRec != null) null else cls0.resolveInstanceMember(name)

                        val targetRec = when {
                            fastRec != null && fastRec.type == ObjRecord.Type.Fun -> fastRec
                            resolved != null && resolved.record.type == ObjRecord.Type.Fun && !resolved.record.isAbstract -> resolved.record
                            else -> null
                        }
                        if (targetRec != null) {
                            val visibility = targetRec.visibility
                            val decl = targetRec.declaringClass ?: (resolved?.declaringClass ?: cls0)
                            if (methodSlot != null && targetRec.type == ObjRecord.Type.Fun) {
                                val slotIndex = methodSlot.slot
                                mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a ->
                                    val inst = obj as ObjInstance
                                    if (inst.objClass === cls0) {
                                        val rec = if (slotIndex >= 0 && slotIndex < inst.methodSlots.size) inst.methodSlots[slotIndex] else null
                                        if (rec != null && rec.type == ObjRecord.Type.Fun && !rec.isAbstract) {
                                            if (!visibility.isPublic && !canAccessMember(visibility, decl, sc.currentClassCtx, name))
                                                sc.raiseError(ObjIllegalAccessException(sc, "can't invoke non-public method $name"))
                                            rec.value.invoke(inst.instanceScope, inst, a, decl)
                                        } else {
                                            obj.invokeInstanceMethod(sc, name, a)
                                        }
                                    } else {
                                        obj.invokeInstanceMethod(sc, name, a)
                                    }
                                }
                            } else {
                                val callable = targetRec.value
                                mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a ->
                                    val inst = obj as ObjInstance
                                    if (!visibility.isPublic && !canAccessMember(visibility, decl, sc.currentClassCtx, name))
                                        sc.raiseError(ObjIllegalAccessException(sc, "can't invoke non-public method $name"))
                                    callable.invoke(inst.instanceScope, inst, a)
                                }
                            }
                        } else {
                            // Fallback to name-based lookup per call (handles extensions and root members)
                            mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a -> obj.invokeInstanceMethod(sc, name, a) }
                        }
                    }
                    is ObjClass -> {
                        val clsScope = base.classScope
                        val rec = clsScope?.get(name)
                        if (rec != null) {
                            val callable = rec.value
                            mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a -> callable.invoke(sc, obj, a) }
                        } else {
                            mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a -> obj.invokeInstanceMethod(sc, name, a) }
                        }
                    }
                    else -> {
                        mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a -> obj.invokeInstanceMethod(sc, name, a) }
                    }
                }
                return result
            }
        }
        return base.invokeInstanceMethod(scope, name, callArgs)
    }

    private fun receiverKeyAndVersion(obj: Obj): Pair<Long, Int> = when (obj) {
        is ObjInstance -> obj.objClass.classId to obj.objClass.layoutVersion
        is ObjClass -> obj.classId to obj.layoutVersion
        else -> 0L to -1
    }
}

/**
 * Fast path for direct `this.method(...)` calls using slot maps.
 * Falls back to normal invoke semantics when needed.
 */
class ThisMethodSlotCallRef(
    private val name: String,
    private val methodId: Int?,
    private val args: List<ParsedArgument>,
    private val tailBlock: Boolean,
    private val isOptional: Boolean
) : ObjRef {
    internal fun methodName(): String = name
    internal fun methodId(): Int? = methodId
    internal fun arguments(): List<ParsedArgument> = args
    internal fun hasTailBlock(): Boolean = tailBlock
    internal fun optionalInvoke(): Boolean = isOptional

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/**
 * Reference to a local/visible variable by name (Phase A: scope lookup).
 */
class LocalVarRef(val name: String, private val atPos: Pos) : ObjRef {
    internal fun pos(): Pos = atPos
    override fun forEachVariable(block: (String) -> Unit) {
        block(name)
    }

    override fun forEachVariableWithPos(block: (String, Pos) -> Unit) {
        block(name, atPos)
    }
    // Per-frame slot cache to avoid repeated name lookups
    private var cachedFrameId: Long = 0L
    private var cachedSlot: Int = -1

    private fun resolveSlot(scope: Scope): Int {
        val idx = scope.getSlotIndexOf(name)
        if (idx != null) {
            cachedFrameId = scope.frameId
            cachedSlot = idx
            return idx
        }
        return -1
    }

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) = scope.raiseObjRefEvalDisabled()
}


/**
 * Array/list literal construction without per-access lambdas.
 */
class BoundLocalVarRef(
    private val slot: Int,
    private val atPos: Pos,
) : ObjRef {
    internal fun slotIndex(): Int = slot
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) = scope.raiseObjRefEvalDisabled()
}

/**
 * Fast local-by-name reference meant for identifiers that the compiler knows are locals/parameters.
 * It resolves the slot once per frame and never falls back to global/module lookup.
 */
class FastLocalVarRef(
    val name: String,
    private val atPos: Pos,
) : ObjRef {
    override fun forEachVariable(block: (String) -> Unit) {
        block(name)
    }
    // Cache the exact scope frame that owns the slot, not just the current frame
    private var cachedOwnerScope: Scope? = null
    private var cachedOwnerFrameId: Long = 0L
    private var cachedSlot: Int = -1

    private fun isOwnerValidFor(current: Scope): Boolean {
        val owner = cachedOwnerScope ?: return false
        if (owner.frameId != cachedOwnerFrameId) return false
        // Ensure owner is an ancestor (or same) of current
        var s: Scope? = current
        var guard = 0
        while (s != null) {
            if (s === owner) return true
            val next = s.parent
            // Defensive: break on self-parent or pathological cycles
            if (next === s) return false
            s = next
            if (++guard > 4096) return false
        }
        return false
    }

    private fun resolveSlotInAncestry(scope: Scope): Int {
        var s: Scope? = scope
        var guard = 0
        while (s != null) {
            val idx = s.getSlotIndexOf(name)
            if (idx != null) {
                cachedOwnerScope = s
                cachedOwnerFrameId = s.frameId
                cachedSlot = idx
                return idx
            }
            val next = s.parent
            if (next === s) return -1
            s = next
            if (++guard > 4096) return -1
        }
        return -1
    }

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) = scope.raiseObjRefEvalDisabled()
}

/**
 * Identifier reference in class context that prefers member slots on `this` after local lookup.
 * Falls back to normal scope lookup for globals/outer scopes.
 */
class ImplicitThisMemberRef(
    val name: String,
    val atPos: Pos,
    internal val fieldId: Int?,
    internal val methodId: Int?,
    private val preferredThisTypeName: String? = null
) : ObjRef {
    internal fun preferredThisTypeName(): String? = preferredThisTypeName
    override fun forEachVariable(block: (String) -> Unit) {
        block(name)
    }

    override fun forEachVariableWithPos(block: (String, Pos) -> Unit) {
        block(name, atPos)
    }

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) = scope.raiseObjRefEvalDisabled()
}

/**
 * Reference to a class-scope member in the nearest enclosing class context.
 */
class ClassScopeMemberRef(
    val name: String,
    private val atPos: Pos,
    private val ownerClassName: String
) : ObjRef {
    internal fun ownerClassName(): String = ownerClassName
    override fun forEachVariable(block: (String) -> Unit) {
        block(name)
    }

    override fun forEachVariableWithPos(block: (String, Pos) -> Unit) {
        block(name, atPos)
    }

    private fun resolveClass(scope: Scope): ObjClass {
        scope.thisVariants.firstOrNull { it is ObjClass && it.className == ownerClassName }?.let {
            return it as ObjClass
        }
        val cls = scope[ownerClassName]?.value as? ObjClass
        if (cls != null) return cls
        scope.raiseSymbolNotFound(ownerClassName)
    }

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) = scope.raiseObjRefEvalDisabled()
}

/**
 * Fast path for implicit member calls in class bodies: `foo(...)` resolves locals first,
 * then falls back to member lookup on `this`.
 */
class ImplicitThisMethodCallRef(
    private val name: String,
    private val methodId: Int?,
    private val args: List<ParsedArgument>,
    private val tailBlock: Boolean,
    private val isOptional: Boolean,
    private val atPos: Pos,
    private val preferredThisTypeName: String? = null
) : ObjRef {
    internal fun pos(): Pos = atPos
    internal fun methodName(): String = name
    internal fun arguments(): List<ParsedArgument> = args
    internal fun hasTailBlock(): Boolean = tailBlock
    internal fun optionalInvoke(): Boolean = isOptional
    internal fun preferredThisTypeName(): String? = preferredThisTypeName
    internal fun slotId(): Int? = methodId

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/**
 * Direct local slot reference with known slot index and lexical depth.
 * Depth=0 means current scope, depth=1 means parent scope, etc.
 */
class LocalSlotRef(
    val name: String,
    internal val slot: Int,
    internal val scopeId: Int,
    internal val isMutable: Boolean,
    internal val isDelegated: Boolean,
    private val atPos: Pos,
    private val strict: Boolean = false,
    internal val captureOwnerScopeId: Int? = null,
    internal val captureOwnerSlot: Int? = null,
) : ObjRef {
    internal fun pos(): Pos = atPos
    override fun forEachVariable(block: (String) -> Unit) {
        block(name)
    }
    private fun resolveOwner(scope: Scope): Scope? {
        var s: Scope? = scope
        var guard = 0
        while (s != null && guard++ < 1024) {
            val idx = s.getSlotIndexOf(name)
            if (idx != null && idx == slot) return s
            s = s.parent
        }
        return null
    }

    private fun resolveOwnerAndSlot(scope: Scope): Pair<Scope, Int>? {
        var s: Scope? = scope
        var guard = 0
        while (s != null && guard++ < 1024) {
            val idx = s.getSlotIndexOf(name)
            if (idx != null) {
                if (idx == slot) return s to slot
                if (!strict || captureOwnerSlot != null) return s to idx
            }
            s = s.parent
        }
        return null
    }

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) = scope.raiseObjRefEvalDisabled()
}

class ListLiteralRef(private val entries: List<ListEntry>) : ObjRef {
    internal fun entries(): List<ListEntry> = entries

    override fun forEachVariable(block: (String) -> Unit) {
        for (e in entries) {
            when (e) {
                is ListEntry.Element -> e.ref.forEachVariable(block)
                is ListEntry.Spread -> e.ref.forEachVariable(block)
            }
        }
    }

    override fun forEachVariableWithPos(block: (String, Pos) -> Unit) {
        for (e in entries) {
            when (e) {
                is ListEntry.Element -> e.ref.forEachVariableWithPos(block)
                is ListEntry.Spread -> e.ref.forEachVariableWithPos(block)
            }
        }
    }

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) = scope.raiseObjRefEvalDisabled()
}

// --- Map literal support ---

sealed class MapLiteralEntry {
    data class Named(val key: String, val value: ObjRef) : MapLiteralEntry()
    data class Spread(val ref: ObjRef) : MapLiteralEntry()
}

class MapLiteralRef(private val entries: List<MapLiteralEntry>) : ObjRef {
    internal fun entries(): List<MapLiteralEntry> = entries

    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/**
 * Range literal: left .. right or left ..< right. Right may be omitted in certain contexts.
 */
class RangeRef(
    internal val left: ObjRef?,
    internal val right: ObjRef?,
    internal val isEndInclusive: Boolean,
    internal val isDescending: Boolean = false,
    internal val step: ObjRef? = null
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/** Assignment if null op: target ?= value */
class AssignIfNullRef(
    internal val target: ObjRef,
    internal val value: ObjRef,
    internal val atPos: Pos,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

/** Simple assignment: target = value */
class AssignRef(
    internal val target: ObjRef,
    internal val value: ObjRef,
    private val atPos: Pos,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = scope.raiseObjRefEvalDisabled()

    override suspend fun evalValue(scope: Scope): Obj = scope.raiseObjRefEvalDisabled()
}

    // (duplicate LocalVarRef removed; the canonical implementation is defined earlier in this file)
