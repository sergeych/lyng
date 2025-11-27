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

@file:Suppress("INLINE_NOT_NEEDED", "REDUNDANT_INLINE")

package net.sergeych.lyng.obj

import net.sergeych.lyng.*

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
    suspend fun evalValue(scope: Scope): Obj = get(scope).value
    suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        throw ScriptError(pos, "can't assign value")
    }
}

/** Runtime-computed read-only reference backed by a lambda. */
class ValueFnRef(private val fn: suspend (Scope) -> ObjRecord) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = fn(scope)
}

/** Unary operations supported by ObjRef. */
enum class UnaryOp { NOT, NEGATE, BITNOT }

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
class UnaryOpRef(private val op: UnaryOp, private val a: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val fastRval = PerfFlags.RVAL_FASTPATH
        val v = if (fastRval) a.evalValue(scope) else a.get(scope).value
        if (PerfFlags.PRIMITIVE_FASTOPS) {
            val rFast: Obj? = when (op) {
                UnaryOp.NOT -> if (v is ObjBool) if (!v.value) ObjTrue else ObjFalse else null
                UnaryOp.NEGATE -> when (v) {
                    is ObjInt -> ObjInt(-v.value)
                    is ObjReal -> ObjReal(-v.value)
                    else -> null
                }
                UnaryOp.BITNOT -> if (v is ObjInt) ObjInt(v.value.inv()) else null
            }
            if (rFast != null) {
                if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                return rFast.asReadonly
            }
        }
        val r = when (op) {
            UnaryOp.NOT -> v.logicalNot(scope)
            UnaryOp.NEGATE -> v.negate(scope)
            UnaryOp.BITNOT -> v.bitNot(scope)
        }
        return r.asReadonly
    }
}

/** R-value reference for binary operations. */
class BinaryOpRef(private val op: BinOp, private val left: ObjRef, private val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val a = if (PerfFlags.RVAL_FASTPATH) left.evalValue(scope) else left.get(scope).value
        val b = if (PerfFlags.RVAL_FASTPATH) right.evalValue(scope) else right.get(scope).value

        // Primitive fast paths for common cases (guarded by PerfFlags.PRIMITIVE_FASTOPS)
        if (PerfFlags.PRIMITIVE_FASTOPS) {
            // Fast boolean ops when both operands are ObjBool
            if (a is ObjBool && b is ObjBool) {
                val r: Obj? = when (op) {
                    BinOp.OR -> if (a.value || b.value) ObjTrue else ObjFalse
                    BinOp.AND -> if (a.value && b.value) ObjTrue else ObjFalse
                    BinOp.EQ -> if (a.value == b.value) ObjTrue else ObjFalse
                    BinOp.NEQ -> if (a.value != b.value) ObjTrue else ObjFalse
                    else -> null
                }
                if (r != null) {
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                    return r.asReadonly
                }
            }
            // Fast integer ops when both operands are ObjInt
            if (a is ObjInt && b is ObjInt) {
                val av = a.value
                val bv = b.value
                val r: Obj? = when (op) {
                    BinOp.PLUS -> ObjInt(av + bv)
                    BinOp.MINUS -> ObjInt(av - bv)
                    BinOp.STAR -> ObjInt(av * bv)
                    BinOp.SLASH -> if (bv != 0L) ObjInt(av / bv) else null
                    BinOp.PERCENT -> if (bv != 0L) ObjInt(av % bv) else null
                    BinOp.BAND -> ObjInt(av and bv)
                    BinOp.BXOR -> ObjInt(av xor bv)
                    BinOp.BOR -> ObjInt(av or bv)
                    BinOp.SHL -> ObjInt(av shl (bv.toInt() and 63))
                    BinOp.SHR -> ObjInt(av shr (bv.toInt() and 63))
                    BinOp.EQ -> if (av == bv) ObjTrue else ObjFalse
                    BinOp.NEQ -> if (av != bv) ObjTrue else ObjFalse
                    BinOp.LT -> if (av < bv) ObjTrue else ObjFalse
                    BinOp.LTE -> if (av <= bv) ObjTrue else ObjFalse
                    BinOp.GT -> if (av > bv) ObjTrue else ObjFalse
                    BinOp.GTE -> if (av >= bv) ObjTrue else ObjFalse
                    else -> null
                }
                if (r != null) {
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                    return r.asReadonly
                }
            }
            // Fast string operations when both are strings
            if (a is ObjString && b is ObjString) {
                val r: Obj? = when (op) {
                    BinOp.EQ -> if (a.value == b.value) ObjTrue else ObjFalse
                    BinOp.NEQ -> if (a.value != b.value) ObjTrue else ObjFalse
                    BinOp.LT -> if (a.value < b.value) ObjTrue else ObjFalse
                    BinOp.LTE -> if (a.value <= b.value) ObjTrue else ObjFalse
                    BinOp.GT -> if (a.value > b.value) ObjTrue else ObjFalse
                    BinOp.GTE -> if (a.value >= b.value) ObjTrue else ObjFalse
                    BinOp.PLUS -> ObjString(a.value + b.value)
                    else -> null
                }
                if (r != null) {
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                    return r.asReadonly
                }
            }
            // Fast char vs char comparisons
            if (a is ObjChar && b is ObjChar) {
                val av = a.value
                val bv = b.value
                val r: Obj? = when (op) {
                    BinOp.EQ -> if (av == bv) ObjTrue else ObjFalse
                    BinOp.NEQ -> if (av != bv) ObjTrue else ObjFalse
                    BinOp.LT -> if (av < bv) ObjTrue else ObjFalse
                    BinOp.LTE -> if (av <= bv) ObjTrue else ObjFalse
                    BinOp.GT -> if (av > bv) ObjTrue else ObjFalse
                    BinOp.GTE -> if (av >= bv) ObjTrue else ObjFalse
                    else -> null
                }
                if (r != null) {
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                    return r.asReadonly
                }
            }
            // Fast concatenation for String with Int/Char on either side
            if (op == BinOp.PLUS) {
                when {
                    a is ObjString && b is ObjInt -> {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                        return ObjString(a.value + b.value.toString()).asReadonly
                    }
                    a is ObjString && b is ObjChar -> {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                        return ObjString(a.value + b.value).asReadonly
                    }
                    b is ObjString && a is ObjInt -> {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                        return ObjString(a.value.toString() + b.value).asReadonly
                    }
                    b is ObjString && a is ObjChar -> {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                        return ObjString(a.value.toString() + b.value).asReadonly
                    }
                }
            }
            // Fast numeric mixed ops for Int/Real combinations by promoting to double
            if ((a is ObjInt || a is ObjReal) && (b is ObjInt || b is ObjReal)) {
                val ad: Double = if (a is ObjInt) a.doubleValue else (a as ObjReal).value
                val bd: Double = if (b is ObjInt) b.doubleValue else (b as ObjReal).value
                val rNum: Obj? = when (op) {
                    BinOp.PLUS -> ObjReal(ad + bd)
                    BinOp.MINUS -> ObjReal(ad - bd)
                    BinOp.STAR -> ObjReal(ad * bd)
                    BinOp.SLASH -> ObjReal(ad / bd)
                    BinOp.PERCENT -> ObjReal(ad % bd)
                    BinOp.LT -> if (ad < bd) ObjTrue else ObjFalse
                    BinOp.LTE -> if (ad <= bd) ObjTrue else ObjFalse
                    BinOp.GT -> if (ad > bd) ObjTrue else ObjFalse
                    BinOp.GTE -> if (ad >= bd) ObjTrue else ObjFalse
                    BinOp.EQ -> if (ad == bd) ObjTrue else ObjFalse
                    BinOp.NEQ -> if (ad != bd) ObjTrue else ObjFalse
                    else -> null
                }
                if (rNum != null) {
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                    return rNum.asReadonly
                }
            }
        }

        val r: Obj = when (op) {
            BinOp.OR -> a.logicalOr(scope, b)
            BinOp.AND -> a.logicalAnd(scope, b)
            BinOp.EQARROW -> ObjMapEntry(a, b)
            BinOp.EQ -> ObjBool(a.compareTo(scope, b) == 0)
            BinOp.NEQ -> ObjBool(a.compareTo(scope, b) != 0)
            BinOp.REF_EQ -> ObjBool(a === b)
            BinOp.REF_NEQ -> ObjBool(a !== b)
            BinOp.MATCH -> a.operatorMatch(scope, b)
            BinOp.NOTMATCH -> a.operatorNotMatch(scope, b)
            BinOp.LTE -> ObjBool(a.compareTo(scope, b) <= 0)
            BinOp.LT -> ObjBool(a.compareTo(scope, b) < 0)
            BinOp.GTE -> ObjBool(a.compareTo(scope, b) >= 0)
            BinOp.GT -> ObjBool(a.compareTo(scope, b) > 0)
            BinOp.IN -> ObjBool(b.contains(scope, a))
            BinOp.NOTIN -> ObjBool(!b.contains(scope, a))
            BinOp.IS -> ObjBool(a.isInstanceOf(b))
            BinOp.NOTIS -> ObjBool(!a.isInstanceOf(b))
            BinOp.SHUTTLE -> ObjInt(a.compareTo(scope, b).toLong())
            BinOp.BAND -> a.bitAnd(scope, b)
            BinOp.BXOR -> a.bitXor(scope, b)
            BinOp.BOR -> a.bitOr(scope, b)
            BinOp.SHL -> a.shl(scope, b)
            BinOp.SHR -> a.shr(scope, b)
            BinOp.PLUS -> a.plus(scope, b)
            BinOp.MINUS -> a.minus(scope, b)
            BinOp.STAR -> a.mul(scope, b)
            BinOp.SLASH -> a.div(scope, b)
            BinOp.PERCENT -> a.mod(scope, b)
        }
        return r.asReadonly
    }
}

/** Conditional (ternary) operator reference: cond ? a : b */
class ConditionalRef(
    private val condition: ObjRef,
    private val ifTrue: ObjRef,
    private val ifFalse: ObjRef
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val condVal = if (PerfFlags.RVAL_FASTPATH) condition.evalValue(scope) else condition.get(scope).value
        val condTrue = when (condVal) {
            is ObjBool -> condVal.value
            is ObjInt -> condVal.value != 0L
            else -> condVal.toBool()
        }
        val branch = if (condTrue) ifTrue else ifFalse
        return branch.get(scope)
    }
}

/** Cast operator reference: left `as` rightType or `as?` (nullable). */
class CastRef(
    private val valueRef: ObjRef,
    private val typeRef: ObjRef,
    private val isNullable: Boolean,
    private val atPos: Pos,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val v0 = if (PerfFlags.RVAL_FASTPATH) valueRef.evalValue(scope) else valueRef.get(scope).value
        val t = if (PerfFlags.RVAL_FASTPATH) typeRef.evalValue(scope) else typeRef.get(scope).value
        val target = (t as? ObjClass) ?: scope.raiseClassCastError("${'$'}t is not the class instance")
        // unwrap qualified views
        val v = when (v0) {
            is ObjQualifiedView -> v0.instance
            else -> v0
        }
        return if (v.isInstanceOf(target)) {
            // For instances, return a qualified view to enforce ancestor-start dispatch
            if (v is ObjInstance) ObjQualifiedView(v, target).asReadonly else v.asReadonly
        } else {
            if (isNullable) ObjNull.asReadonly else scope.raiseClassCastError(
                "Cannot cast ${'$'}{(v as? Obj)?.objClass?.className ?: v::class.simpleName} to ${'$'}{target.className}"
            )
        }
    }
}

/** Qualified `this@Type`: resolves to a view of current `this` starting dispatch from the ancestor Type. */
class QualifiedThisRef(private val typeName: String, private val atPos: Pos) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val thisObj = scope.thisObj
        val t = scope[typeName]?.value as? ObjClass
            ?: scope.raiseError("unknown type $typeName")
        val inst = (thisObj as? ObjInstance)
            ?: scope.raiseClassCastError("this is not an instance")
        if (!inst.objClass.allParentsSet.contains(t) && inst.objClass !== t)
            scope.raiseClassCastError(
                "Qualifier ${'$'}{t.className} is not an ancestor of ${'$'}{inst.objClass.className} (order: ${'$'}{inst.objClass.renderLinearization(true)})"
            )
        return ObjQualifiedView(inst, t).asReadonly
    }
}

/** Assignment compound op: target op= value */
class AssignOpRef(
    private val op: BinOp,
    private val target: ObjRef,
    private val value: ObjRef,
    private val atPos: Pos,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val x = target.get(scope).value
        val y = if (PerfFlags.RVAL_FASTPATH) value.evalValue(scope) else value.get(scope).value
        val inPlace: Obj? = when (op) {
            BinOp.PLUS -> x.plusAssign(scope, y)
            BinOp.MINUS -> x.minusAssign(scope, y)
            BinOp.STAR -> x.mulAssign(scope, y)
            BinOp.SLASH -> x.divAssign(scope, y)
            BinOp.PERCENT -> x.modAssign(scope, y)
            else -> null
        }
        if (inPlace != null) return inPlace.asReadonly
        val result: Obj = when (op) {
            BinOp.PLUS -> x.plus(scope, y)
            BinOp.MINUS -> x.minus(scope, y)
            BinOp.STAR -> x.mul(scope, y)
            BinOp.SLASH -> x.div(scope, y)
            BinOp.PERCENT -> x.mod(scope, y)
            else -> scope.raiseError("unsupported assignment op: $op")
        }
        target.setAt(atPos, scope, result)
        return result.asReadonly
    }
}

/** Pre/post ++/-- on l-values */
class IncDecRef(
    private val target: ObjRef,
    private val isIncrement: Boolean,
    private val isPost: Boolean,
    private val atPos: Pos,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val rec = target.get(scope)
        if (!rec.isMutable) scope.raiseError("Cannot ${if (isIncrement) "increment" else "decrement"} immutable value")
        val v = rec.value
        val one = ObjInt.One
        return if (v.isConst) {
            // Mirror existing semantics in Compiler for const values
            val result = if (isIncrement) v.plus(scope, one) else v.minus(scope, one)
            // write back
            target.setAt(atPos, scope, result)
            // For post-inc: previous code returned NEW value; for pre-inc: returned ORIGINAL value
            if (isPost) result.asReadonly else v.asReadonly
        } else {
            val res = when {
                isIncrement && isPost -> v.getAndIncrement(scope)
                isIncrement && !isPost -> v.incrementAndGet(scope)
                !isIncrement && isPost -> v.getAndDecrement(scope)
                else -> v.decrementAndGet(scope)
            }
            res.asReadonly
        }
    }
}

/** Elvis operator reference: a ?: b */
class ElvisRef(private val left: ObjRef, private val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val fastRval = PerfFlags.RVAL_FASTPATH
        val a = if (fastRval) left.evalValue(scope) else left.get(scope).value
        val r = if (a != ObjNull) a else if (fastRval) right.evalValue(scope) else right.get(scope).value
        return r.asReadonly
    }
}

/** Logical OR with short-circuit: a || b */
class LogicalOrRef(private val left: ObjRef, private val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val fastRval = PerfFlags.RVAL_FASTPATH
        val fastPrim = PerfFlags.PRIMITIVE_FASTOPS
        val a = if (fastRval) left.evalValue(scope) else left.get(scope).value
        if ((a as? ObjBool)?.value == true) return ObjTrue.asReadonly
        val b = if (fastRval) right.evalValue(scope) else right.get(scope).value
        if (fastPrim) {
            if (a is ObjBool && b is ObjBool) {
                return if (a.value || b.value) ObjTrue.asReadonly else ObjFalse.asReadonly
            }
        }
        return a.logicalOr(scope, b).asReadonly
    }
}

/** Logical AND with short-circuit: a && b */
class LogicalAndRef(private val left: ObjRef, private val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        // Hoist flags to locals for JIT friendliness
        val fastRval = PerfFlags.RVAL_FASTPATH
        val fastPrim = PerfFlags.PRIMITIVE_FASTOPS
        val a = if (fastRval) left.evalValue(scope) else left.get(scope).value
        if ((a as? ObjBool)?.value == false) return ObjFalse.asReadonly
        val b = if (fastRval) right.evalValue(scope) else right.get(scope).value
        if (fastPrim) {
            if (a is ObjBool && b is ObjBool) {
                return if (a.value && b.value) ObjTrue.asReadonly else ObjFalse.asReadonly
            }
        }
        return a.logicalAnd(scope, b).asReadonly
    }
}

/**
 * Read-only reference that always returns the same cached record.
 */
class ConstRef(private val record: ObjRecord) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = record
    // Expose constant value for compiler constant folding (pure, read-only)
    val constValue: Obj get() = record.value
}

/**
 * Reference to an object's field with optional chaining.
 */
class FieldRef(
    private val target: ObjRef,
    private val name: String,
    private val isOptional: Boolean,
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
    private inline fun size4ReadsEnabled(): Boolean =
        PerfFlags.FIELD_PIC_SIZE_4 ||
            (PerfFlags.PIC_ADAPTIVE_2_TO_4 && rPromotedTo4)
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

    override suspend fun get(scope: Scope): ObjRecord {
        val fastRval = PerfFlags.RVAL_FASTPATH
        val fieldPic = PerfFlags.FIELD_PIC
        val picCounters = PerfFlags.PIC_DEBUG_COUNTERS
        val base = if (fastRval) target.evalValue(scope) else target.get(scope).value
        if (base == ObjNull && isOptional) return ObjNull.asMutable
        if (fieldPic) {
            val (key, ver) = receiverKeyAndVersion(base)
            rGetter1?.let { g -> if (key == rKey1 && ver == rVer1) {
                if (picCounters) PerfStats.fieldPicHit++
                noteReadHit()
                val rec0 = g(base, scope)
                if (base is ObjClass) {
                    val idx0 = base.classScope?.getSlotIndexOf(name)
                    if (idx0 != null) { tKey = key; tVer = ver; tFrameId = scope.frameId; tRecord = rec0 } else { tRecord = null }
                } else { tRecord = null }
                return rec0
            } }
            rGetter2?.let { g -> if (key == rKey2 && ver == rVer2) {
                if (picCounters) PerfStats.fieldPicHit++
                noteReadHit()
                // move-to-front: promote 2→1
                val tK = rKey2; val tV = rVer2; val tG = rGetter2
                rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                rKey1 = tK; rVer1 = tV; rGetter1 = tG
                val rec0 = g(base, scope)
                if (base is ObjClass) {
                    val idx0 = base.classScope?.getSlotIndexOf(name)
                    if (idx0 != null) { tKey = key; tVer = ver; tFrameId = scope.frameId; tRecord = rec0 } else { tRecord = null }
                } else { tRecord = null }
                return rec0
            } }
            if (size4ReadsEnabled()) rGetter3?.let { g -> if (key == rKey3 && ver == rVer3) {
                if (picCounters) PerfStats.fieldPicHit++
                noteReadHit()
                // move-to-front: promote 3→1
                val tK = rKey3; val tV = rVer3; val tG = rGetter3
                rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                rKey1 = tK; rVer1 = tV; rGetter1 = tG
                val rec0 = g(base, scope)
                if (base is ObjClass) {
                    val idx0 = base.classScope?.getSlotIndexOf(name)
                    if (idx0 != null) { tKey = key; tVer = ver; tFrameId = scope.frameId; tRecord = rec0 } else { tRecord = null }
                } else { tRecord = null }
                return rec0
            } }
            if (size4ReadsEnabled()) rGetter4?.let { g -> if (key == rKey4 && ver == rVer4) {
                if (picCounters) PerfStats.fieldPicHit++
                noteReadHit()
                // move-to-front: promote 4→1
                val tK = rKey4; val tV = rVer4; val tG = rGetter4
                rKey4 = rKey3; rVer4 = rVer3; rGetter4 = rGetter3
                rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                rKey1 = tK; rVer1 = tV; rGetter1 = tG
                val rec0 = g(base, scope)
                if (base is ObjClass) {
                    val idx0 = base.classScope?.getSlotIndexOf(name)
                    if (idx0 != null) { tKey = key; tVer = ver; tFrameId = scope.frameId; tRecord = rec0 } else { tRecord = null }
                } else { tRecord = null }
                return rec0
            } }
            // Slow path
            if (picCounters) PerfStats.fieldPicMiss++
            noteReadMiss()
            val rec = try {
                base.readField(scope, name)
            } catch (e: ExecutionError) {
                // Cache-after-miss negative entry: rethrow the same error quickly for this shape
                rKey4 = rKey3; rVer4 = rVer3; rGetter4 = rGetter3
                rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                rKey1 = key; rVer1 = ver; rGetter1 = { _, sc -> sc.raiseError(e.message ?: "no such field: $name") }
                throw e
            }
            // Install move-to-front with a handle-aware getter; honor PIC size flag
            if (size4ReadsEnabled()) {
                rKey4 = rKey3; rVer4 = rVer3; rGetter4 = rGetter3
                rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
            }
            rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
            when (base) {
                is ObjClass -> {
                    val clsScope = base.classScope
                    val capturedIdx = clsScope?.getSlotIndexOf(name)
                    if (clsScope != null && capturedIdx != null) {
                        rKey1 = key; rVer1 = ver; rGetter1 = { obj, sc ->
                            val scope0 = (obj as ObjClass).classScope!!
                            val r0 = scope0.getSlotRecord(capturedIdx)
                            if (!r0.visibility.isPublic)
                                sc.raiseError(ObjAccessException(sc, "can't access non-public field $name"))
                            r0
                        }
                    } else {
                        rKey1 = key; rVer1 = ver; rGetter1 = { obj, sc -> obj.readField(sc, name) }
                    }
                }
                else -> {
                    // For instances and other types, fall back to name-based lookup per access (slot index may differ per instance)
                    rKey1 = key; rVer1 = ver; rGetter1 = { obj, sc -> obj.readField(sc, name) }
                }
            }
            return rec
        }
        return base.readField(scope, name)
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        val fieldPic = PerfFlags.FIELD_PIC
        val picCounters = PerfFlags.PIC_DEBUG_COUNTERS
        val base = target.get(scope).value
        if (base == ObjNull && isOptional) {
            // no-op on null receiver for optional chaining assignment
            return
        }
        // Read→write micro fast-path: reuse transient record captured by get()
        if (fieldPic) {
            val (k, v) = receiverKeyAndVersion(base)
            val rec = tRecord
            if (rec != null && tKey == k && tVer == v && tFrameId == scope.frameId) {
                // visibility/mutability checks
                if (!rec.isMutable) scope.raiseError(ObjIllegalAssignmentException(scope, "can't reassign val $name"))
                if (!rec.visibility.isPublic)
                    scope.raiseError(ObjAccessException(scope, "can't access non-public field $name"))
                if (rec.value.assign(scope, newValue) == null) rec.value = newValue
                return
            }
        }
        if (fieldPic) {
            val (key, ver) = receiverKeyAndVersion(base)
            wSetter1?.let { s -> if (key == wKey1 && ver == wVer1) {
                if (picCounters) PerfStats.fieldPicSetHit++
                noteWriteHit()
                return s(base, scope, newValue)
            } }
            wSetter2?.let { s -> if (key == wKey2 && ver == wVer2) {
                if (picCounters) PerfStats.fieldPicSetHit++
                noteWriteHit()
                // move-to-front: promote 2→1
                val tK = wKey2; val tV = wVer2; val tS = wSetter2
                wKey2 = wKey1; wVer2 = wVer1; wSetter2 = wSetter1
                wKey1 = tK; wVer1 = tV; wSetter1 = tS
                return s(base, scope, newValue)
            } }
            if (size4WritesEnabled()) wSetter3?.let { s -> if (key == wKey3 && ver == wVer3) {
                if (picCounters) PerfStats.fieldPicSetHit++
                noteWriteHit()
                // move-to-front: promote 3→1
                val tK = wKey3; val tV = wVer3; val tS = wSetter3
                wKey3 = wKey2; wVer3 = wVer2; wSetter3 = wSetter2
                wKey2 = wKey1; wVer2 = wVer1; wSetter2 = wSetter1
                wKey1 = tK; wVer1 = tV; wSetter1 = tS
                return s(base, scope, newValue)
            } }
            if (size4WritesEnabled()) wSetter4?.let { s -> if (key == wKey4 && ver == wVer4) {
                if (picCounters) PerfStats.fieldPicSetHit++
                noteWriteHit()
                // move-to-front: promote 4→1
                val tK = wKey4; val tV = wVer4; val tS = wSetter4
                wKey4 = wKey3; wVer4 = wVer3; wSetter4 = wSetter3
                wKey3 = wKey2; wVer3 = wVer2; wSetter3 = wSetter2
                wKey2 = wKey1; wVer2 = wVer1; wSetter2 = wSetter1
                wKey1 = tK; wVer1 = tV; wSetter1 = tS
                return s(base, scope, newValue)
            } }
            // Slow path
            if (picCounters) PerfStats.fieldPicSetMiss++
            noteWriteMiss()
            base.writeField(scope, name, newValue)
            // Install move-to-front with a handle-aware setter; honor PIC size flag
            if (size4WritesEnabled()) {
                wKey4 = wKey3; wVer4 = wVer3; wSetter4 = wSetter3
                wKey3 = wKey2; wVer3 = wVer2; wSetter3 = wSetter2
            }
            wKey2 = wKey1; wVer2 = wVer1; wSetter2 = wSetter1
            when (base) {
                is ObjClass -> {
                    val clsScope = base.classScope
                    val capturedIdx = clsScope?.getSlotIndexOf(name)
                    if (clsScope != null && capturedIdx != null) {
                        wKey1 = key; wVer1 = ver; wSetter1 = { obj, sc, v ->
                            val scope0 = (obj as ObjClass).classScope!!
                            val r0 = scope0.getSlotRecord(capturedIdx)
                            if (!r0.isMutable)
                                sc.raiseError(ObjIllegalAssignmentException(sc, "can't reassign val $name"))
                            if (r0.value.assign(sc, v) == null) r0.value = v
                        }
                    } else {
                        wKey1 = key; wVer1 = ver; wSetter1 = { obj, sc, v -> obj.writeField(sc, name, v) }
                    }
                }
                else -> {
                    // For instances and other types, fall back to generic write (instance slot indices may differ per instance)
                    wKey1 = key; wVer1 = ver; wSetter1 = { obj, sc, v -> obj.writeField(sc, name, v) }
                }
            }
            return
        }
        base.writeField(scope, name, newValue)
    }

    private fun receiverKeyAndVersion(obj: Obj): Pair<Long, Int> = when (obj) {
        is ObjInstance -> obj.objClass.classId to obj.objClass.layoutVersion
        is ObjClass -> obj.classId to obj.layoutVersion
        else -> 0L to -1 // no caching for primitives/dynamics without stable shape
    }

    override suspend fun evalValue(scope: Scope): Obj {
        // Mirror get(), but return raw Obj to avoid transient ObjRecord on R-value paths
        val fastRval = PerfFlags.RVAL_FASTPATH
        val fieldPic = PerfFlags.FIELD_PIC
        val picCounters = PerfFlags.PIC_DEBUG_COUNTERS
        val base = if (fastRval) target.evalValue(scope) else target.get(scope).value
        if (base == ObjNull && isOptional) return ObjNull
        if (fieldPic) {
            val (key, ver) = receiverKeyAndVersion(base)
            rGetter1?.let { g -> if (key == rKey1 && ver == rVer1) {
                if (picCounters) PerfStats.fieldPicHit++
                return g(base, scope).value
            } }
            rGetter2?.let { g -> if (key == rKey2 && ver == rVer2) {
                if (picCounters) PerfStats.fieldPicHit++
                val tK = rKey2; val tV = rVer2; val tG = rGetter2
                rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                rKey1 = tK; rVer1 = tV; rGetter1 = tG
                return g(base, scope).value
            } }
            if (size4ReadsEnabled()) rGetter3?.let { g -> if (key == rKey3 && ver == rVer3) {
                if (picCounters) PerfStats.fieldPicHit++
                val tK = rKey3; val tV = rVer3; val tG = rGetter3
                rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                rKey1 = tK; rVer1 = tV; rGetter1 = tG
                return g(base, scope).value
            } }
            if (size4ReadsEnabled()) rGetter4?.let { g -> if (key == rKey4 && ver == rVer4) {
                if (picCounters) PerfStats.fieldPicHit++
                val tK = rKey4; val tV = rVer4; val tG = rGetter4
                rKey4 = rKey3; rVer4 = rVer3; rGetter4 = rGetter3
                rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                rKey1 = tK; rVer1 = tV; rGetter1 = tG
                return g(base, scope).value
            } }
            if (picCounters) PerfStats.fieldPicMiss++
            val rec = base.readField(scope, name)
            // install primary generic getter for this shape
            when (base) {
                is ObjClass -> {
                    rKey1 = base.classId; rVer1 = base.layoutVersion; rGetter1 = { obj, sc -> obj.readField(sc, name) }
                }
                is ObjInstance -> {
                    val cls = base.objClass
                    rKey1 = cls.classId; rVer1 = cls.layoutVersion; rGetter1 = { obj, sc -> obj.readField(sc, name) }
                }
                else -> {
                    rKey1 = 0L; rVer1 = -1; rGetter1 = null
                }
            }
            return rec.value
        }
        return base.readField(scope, name).value
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
    override suspend fun get(scope: Scope): ObjRecord {
        val fastRval = PerfFlags.RVAL_FASTPATH
        val base = if (fastRval) target.evalValue(scope) else target.get(scope).value
        if (base == ObjNull && isOptional) return ObjNull.asMutable
        val idx = if (fastRval) index.evalValue(scope) else index.get(scope).value
        if (fastRval) {
            // Primitive list index fast path: avoid virtual dispatch to getAt when shapes match
            if (base is ObjList && idx is ObjInt) {
                val i = idx.toInt()
                // Bounds checks are enforced by the underlying list access; exceptions propagate as before
                return base.list[i].asMutable
            }
            // String[Int] fast path
            if (base is ObjString && idx is ObjInt) {
                val i = idx.toInt()
                return ObjChar(base.value[i]).asMutable
            }
            // Map[String] fast path (common case); return ObjNull if absent
            if (base is ObjMap && idx is ObjString) {
                val v = base.map[idx] ?: ObjNull
                return v.asMutable
            }
            if (PerfFlags.INDEX_PIC) {
                // Polymorphic inline cache for other common shapes
                val (key, ver) = when (base) {
                    is ObjInstance -> base.objClass.classId to base.objClass.layoutVersion
                    is ObjClass -> base.classId to base.layoutVersion
                    else -> 0L to -1
                }
                if (key != 0L) {
                    rGetter1?.let { g -> if (key == rKey1 && ver == rVer1) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.indexPicHit++
                        return g(base, scope, idx).asMutable
                    } }
                    rGetter2?.let { g -> if (key == rKey2 && ver == rVer2) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.indexPicHit++
                        val tk = rKey2; val tv = rVer2; val tg = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx).asMutable
                    } }
                    if (PerfFlags.INDEX_PIC_SIZE_4) rGetter3?.let { g -> if (key == rKey3 && ver == rVer3) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.indexPicHit++
                        val tk = rKey3; val tv = rVer3; val tg = rGetter3
                        rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx).asMutable
                    } }
                    if (PerfFlags.INDEX_PIC_SIZE_4) rGetter4?.let { g -> if (key == rKey4 && ver == rVer4) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.indexPicHit++
                        val tk = rKey4; val tv = rVer4; val tg = rGetter4
                        rKey4 = rKey3; rVer4 = rVer3; rGetter4 = rGetter3
                        rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx).asMutable
                    } }
                    // Miss: resolve and install generic handler
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.indexPicMiss++
                    val v = base.getAt(scope, idx)
                    if (PerfFlags.INDEX_PIC_SIZE_4) {
                        rKey4 = rKey3; rVer4 = rVer3; rGetter4 = rGetter3
                        rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                    }
                    rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                    rKey1 = key; rVer1 = ver; rGetter1 = { obj, sc, ix -> obj.getAt(sc, ix) }
                    return v.asMutable
                }
            }
        }
        return base.getAt(scope, idx).asMutable
    }

    override suspend fun evalValue(scope: Scope): Obj {
        val fastRval = PerfFlags.RVAL_FASTPATH
        val base = if (fastRval) target.evalValue(scope) else target.get(scope).value
        if (base == ObjNull && isOptional) return ObjNull
        val idx = if (fastRval) index.evalValue(scope) else index.get(scope).value
        if (fastRval) {
            // Fast list[int] path
            if (base is ObjList && idx is ObjInt) {
                val i = idx.toInt()
                return base.list[i]
            }
            // String[Int] fast path
            if (base is ObjString && idx is ObjInt) {
                val i = idx.toInt()
                return ObjChar(base.value[i])
            }
            // Map[String] fast path
            if (base is ObjMap && idx is ObjString) {
                return base.map[idx] ?: ObjNull
            }
            if (PerfFlags.INDEX_PIC) {
                // PIC path analogous to get(), but returning raw Obj
                val (key, ver) = when (base) {
                    is ObjInstance -> base.objClass.classId to base.objClass.layoutVersion
                    is ObjClass -> base.classId to base.layoutVersion
                    else -> 0L to -1
                }
                if (key != 0L) {
                    rGetter1?.let { g -> if (key == rKey1 && ver == rVer1) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.indexPicHit++
                        return g(base, scope, idx)
                    } }
                    rGetter2?.let { g -> if (key == rKey2 && ver == rVer2) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.indexPicHit++
                        val tk = rKey2; val tv = rVer2; val tg = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx)
                    } }
                    if (PerfFlags.INDEX_PIC_SIZE_4) rGetter3?.let { g -> if (key == rKey3 && ver == rVer3) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.indexPicHit++
                        val tk = rKey3; val tv = rVer3; val tg = rGetter3
                        rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx)
                    } }
                    if (PerfFlags.INDEX_PIC_SIZE_4) rGetter4?.let { g -> if (key == rKey4 && ver == rVer4) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.indexPicHit++
                        val tk = rKey4; val tv = rVer4; val tg = rGetter4
                        rKey4 = rKey3; rVer4 = rVer3; rGetter4 = rGetter3
                        rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx)
                    } }
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.indexPicMiss++
                    val v = base.getAt(scope, idx)
                    if (PerfFlags.INDEX_PIC_SIZE_4) {
                        rKey4 = rKey3; rVer4 = rVer3; rGetter4 = rGetter3
                        rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                    }
                    rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                    rKey1 = key; rVer1 = ver; rGetter1 = { obj, sc, ix -> obj.getAt(sc, ix) }
                    return v
                }
            }
        }
        return base.getAt(scope, idx)
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        val fastRval = PerfFlags.RVAL_FASTPATH
        val base = if (fastRval) target.evalValue(scope) else target.get(scope).value
        if (base == ObjNull && isOptional) {
            // no-op on null receiver for optional chaining assignment
            return
        }
        val idx = if (fastRval) index.evalValue(scope) else index.get(scope).value
        if (fastRval) {
            // Mirror read fast-path with direct write for ObjList + ObjInt index
            if (base is ObjList && idx is ObjInt) {
                val i = idx.toInt()
                base.list[i] = newValue
                return
            }
            // Direct write fast path for ObjMap + ObjString
            if (base is ObjMap && idx is ObjString) {
                base.map[idx] = newValue
                return
            }
            if (PerfFlags.INDEX_PIC) {
                // Polymorphic inline cache for index write
                val (key, ver) = when (base) {
                    is ObjInstance -> base.objClass.classId to base.objClass.layoutVersion
                    is ObjClass -> base.classId to base.layoutVersion
                    else -> 0L to -1
                }
                if (key != 0L) {
                    wSetter1?.let { s -> if (key == wKey1 && ver == wVer1) { s(base, scope, idx, newValue); return } }
                    wSetter2?.let { s -> if (key == wKey2 && ver == wVer2) {
                        val tk = wKey2; val tv = wVer2; val ts = wSetter2
                        wKey2 = wKey1; wVer2 = wVer1; wSetter2 = wSetter1
                        wKey1 = tk; wVer1 = tv; wSetter1 = ts
                        s(base, scope, idx, newValue); return
                    } }
                    if (PerfFlags.INDEX_PIC_SIZE_4) wSetter3?.let { s -> if (key == wKey3 && ver == wVer3) {
                        val tk = wKey3; val tv = wVer3; val ts = wSetter3
                        wKey3 = wKey2; wVer3 = wVer2; wSetter3 = wSetter2
                        wKey2 = wKey1; wVer2 = wVer1; wSetter2 = wSetter1
                        wKey1 = tk; wVer1 = tv; wSetter1 = ts
                        s(base, scope, idx, newValue); return
                    } }
                    if (PerfFlags.INDEX_PIC_SIZE_4) wSetter4?.let { s -> if (key == wKey4 && ver == wVer4) {
                        val tk = wKey4; val tv = wVer4; val ts = wSetter4
                        wKey4 = wKey3; wVer4 = wVer3; wSetter4 = wSetter3
                        wKey3 = wKey2; wVer3 = wVer2; wSetter3 = wSetter2
                        wKey2 = wKey1; wVer2 = wVer1; wSetter2 = wSetter1
                        wKey1 = tk; wVer1 = tv; wSetter1 = ts
                        s(base, scope, idx, newValue); return
                    } }
                    // Miss: perform write and install generic handler
                    base.putAt(scope, idx, newValue)
                    if (PerfFlags.INDEX_PIC_SIZE_4) {
                        wKey4 = wKey3; wVer4 = wVer3; wSetter4 = wSetter3
                        wKey3 = wKey2; wVer3 = wVer2; wSetter3 = wSetter2
                    }
                    wKey2 = wKey1; wVer2 = wVer1; wSetter2 = wSetter1
                    wKey1 = key; wVer1 = ver; wSetter1 = { obj, sc, ix, v -> obj.putAt(sc, ix, v) }
                    return
                }
            }
        }
        base.putAt(scope, idx, newValue)
    }
}

/**
 * R-value reference that wraps a Statement (used during migration for expressions parsed as Statement).
 */
class StatementRef(private val statement: Statement) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = statement.execute(scope).asReadonly
}

/**
 * Direct function call reference: f(args) and optional f?(args).
 */
class CallRef(
    private val target: ObjRef,
    private val args: List<ParsedArgument>,
    private val tailBlock: Boolean,
    private val isOptionalInvoke: Boolean,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val fastRval = PerfFlags.RVAL_FASTPATH
        val usePool = PerfFlags.SCOPE_POOL
        val callee = if (fastRval) target.evalValue(scope) else target.get(scope).value
        if (callee == ObjNull && isOptionalInvoke) return ObjNull.asReadonly
        val callArgs = args.toArguments(scope, tailBlock)
        val result: Obj = if (usePool) {
            scope.withChildFrame(callArgs) { child ->
                callee.callOn(child)
            }
        } else {
            callee.callOn(scope.createChildScope(scope.pos, callArgs))
        }
        return result.asReadonly
    }
}

/**
 * Instance method call reference: obj.method(args) and optional obj?.method(args).
 */
class MethodCallRef(
    private val receiver: ObjRef,
    private val name: String,
    private val args: List<ParsedArgument>,
    private val tailBlock: Boolean,
    private val isOptional: Boolean,
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

    override suspend fun get(scope: Scope): ObjRecord {
        val fastRval = PerfFlags.RVAL_FASTPATH
        val methodPic = PerfFlags.METHOD_PIC
        val picCounters = PerfFlags.PIC_DEBUG_COUNTERS
        val base = if (fastRval) receiver.evalValue(scope) else receiver.get(scope).value
        if (base == ObjNull && isOptional) return ObjNull.asReadonly
        val callArgs = args.toArguments(scope, tailBlock)
        if (methodPic) {
            val (key, ver) = receiverKeyAndVersion(base)
            mInvoker1?.let { inv -> if (key == mKey1 && ver == mVer1) {
                if (picCounters) PerfStats.methodPicHit++
                noteMethodHit()
                return inv(base, scope, callArgs).asReadonly
            } }
            mInvoker2?.let { inv -> if (key == mKey2 && ver == mVer2) {
                if (picCounters) PerfStats.methodPicHit++
                noteMethodHit()
                return inv(base, scope, callArgs).asReadonly
            } }
            if (size4MethodsEnabled()) mInvoker3?.let { inv -> if (key == mKey3 && ver == mVer3) {
                if (picCounters) PerfStats.methodPicHit++
                noteMethodHit()
                // move-to-front: promote 3→1
                val tK = mKey3; val tV = mVer3; val tI = mInvoker3
                mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                mKey1 = tK; mVer1 = tV; mInvoker1 = tI
                return inv(base, scope, callArgs).asReadonly
            } }
            if (size4MethodsEnabled()) mInvoker4?.let { inv -> if (key == mKey4 && ver == mVer4) {
                if (picCounters) PerfStats.methodPicHit++
                noteMethodHit()
                // move-to-front: promote 4→1
                val tK = mKey4; val tV = mVer4; val tI = mInvoker4
                mKey4 = mKey3; mVer4 = mVer3; mInvoker4 = mInvoker3
                mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                mKey1 = tK; mVer1 = tV; mInvoker1 = tI
                return inv(base, scope, callArgs).asReadonly
            } }
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
                    val member = base.objClass.getInstanceMemberOrNull(name)
                    if (member != null) {
                        val visibility = member.visibility
                        val callable = member.value
                        mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a ->
                            val inst = obj as ObjInstance
                            if (!visibility.isPublic)
                                sc.raiseError(ObjAccessException(sc, "can't invoke non-public method $name"))
                            callable.invoke(inst.instanceScope, inst, a)
                        }
                    } else {
                        // Fallback to name-based lookup per call (uncommon)
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
            return result.asReadonly
        }
        val result = base.invokeInstanceMethod(scope, name, callArgs)
        return result.asReadonly
    }

    private fun receiverKeyAndVersion(obj: Obj): Pair<Long, Int> = when (obj) {
        is ObjInstance -> obj.objClass.classId to obj.objClass.layoutVersion
        is ObjClass -> obj.classId to obj.layoutVersion
        else -> 0L to -1
    }
}

/**
 * Reference to a local/visible variable by name (Phase A: scope lookup).
 */
class LocalVarRef(private val name: String, private val atPos: Pos) : ObjRef {
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

    override suspend fun get(scope: Scope): ObjRecord {
        scope.pos = atPos
        // 1) Try fast slot/local
        if (!PerfFlags.LOCAL_SLOT_PIC) {
            scope.getSlotIndexOf(name)?.let {
                if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.localVarPicHit++
                return scope.getSlotRecord(it)
            }
            if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.localVarPicMiss++
            // 2) Fallback to current-scope object or field on `this`
            scope[name]?.let { return it }
            return scope.thisObj.readField(scope, name)
        }
        val hit = (cachedFrameId == scope.frameId && cachedSlot >= 0 && cachedSlot < scope.slotCount())
        val slot = if (hit) cachedSlot else resolveSlot(scope)
        if (slot >= 0) {
            if (PerfFlags.PIC_DEBUG_COUNTERS) {
                if (hit) PerfStats.localVarPicHit++ else PerfStats.localVarPicMiss++
            }
            return scope.getSlotRecord(slot)
        }
        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.localVarPicMiss++
        // 2) Fallback name in scope or field on `this`
        scope[name]?.let { return it }
        return scope.thisObj.readField(scope, name)
    }

    override suspend fun evalValue(scope: Scope): Obj {
        scope.pos = atPos
        if (!PerfFlags.LOCAL_SLOT_PIC) {
            scope.getSlotIndexOf(name)?.let { return scope.getSlotRecord(it).value }
            // fallback to current-scope object or field on `this`
            scope[name]?.let { return it.value }
            return scope.thisObj.readField(scope, name).value
        }
        val hit = (cachedFrameId == scope.frameId && cachedSlot >= 0 && cachedSlot < scope.slotCount())
        val slot = if (hit) cachedSlot else resolveSlot(scope)
        if (slot >= 0) return scope.getSlotRecord(slot).value
        // Fallback name in scope or field on `this`
        scope[name]?.let { return it.value }
        return scope.thisObj.readField(scope, name).value
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        scope.pos = atPos
        if (!PerfFlags.LOCAL_SLOT_PIC) {
            scope.getSlotIndexOf(name)?.let {
                val rec = scope.getSlotRecord(it)
                if (!rec.isMutable) scope.raiseError("Cannot assign to immutable value")
                rec.value = newValue
                return
            }
            scope[name]?.let { stored ->
                if (stored.isMutable) stored.value = newValue
                else scope.raiseError("Cannot assign to immutable value")
                return
            }
            // Fallback: write to field on `this`
            scope.thisObj.writeField(scope, name, newValue)
            return
        }
        val slot = if (cachedFrameId == scope.frameId && cachedSlot >= 0 && cachedSlot < scope.slotCount()) cachedSlot else resolveSlot(scope)
        if (slot >= 0) {
            val rec = scope.getSlotRecord(slot)
            if (!rec.isMutable) scope.raiseError("Cannot assign to immutable value")
            rec.value = newValue
            return
        }
        scope[name]?.let { stored ->
            if (stored.isMutable) stored.value = newValue
            else scope.raiseError("Cannot assign to immutable value")
            return
        }
        scope.thisObj.writeField(scope, name, newValue)
        return
    }
}


/**
 * Array/list literal construction without per-access lambdas.
 */
class BoundLocalVarRef(
    private val slot: Int,
    private val atPos: Pos,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        scope.pos = atPos
        return scope.getSlotRecord(slot)
    }

    override suspend fun evalValue(scope: Scope): Obj {
        scope.pos = atPos
        return scope.getSlotRecord(slot).value
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        scope.pos = atPos
        val rec = scope.getSlotRecord(slot)
        if (!rec.isMutable) scope.raiseError("Cannot assign to immutable value")
        rec.value = newValue
    }
}

/**
 * Fast local-by-name reference meant for identifiers that the compiler knows are locals/parameters.
 * It resolves the slot once per frame and never falls back to global/module lookup.
 */
class FastLocalVarRef(
    private val name: String,
    private val atPos: Pos,
) : ObjRef {
    // Cache the exact scope frame that owns the slot, not just the current frame
    private var cachedOwnerScope: Scope? = null
    private var cachedOwnerFrameId: Long = 0L
    private var cachedSlot: Int = -1

    private fun isOwnerValidFor(current: Scope): Boolean {
        val owner = cachedOwnerScope ?: return false
        if (owner.frameId != cachedOwnerFrameId) return false
        // Ensure owner is an ancestor (or same) of current
        var s: Scope? = current
        while (s != null) {
            if (s === owner) return true
            s = s.parent
        }
        return false
    }

    private fun resolveSlotInAncestry(scope: Scope): Int {
        var s: Scope? = scope
        while (s != null) {
            val idx = s.getSlotIndexOf(name)
            if (idx != null) {
                cachedOwnerScope = s
                cachedOwnerFrameId = s.frameId
                cachedSlot = idx
                return idx
            }
            s = s.parent
        }
        return -1
    }

    override suspend fun get(scope: Scope): ObjRecord {
        scope.pos = atPos
        val ownerValid = isOwnerValidFor(scope)
        val slot = if (ownerValid && cachedSlot >= 0) cachedSlot else resolveSlotInAncestry(scope)
        val actualOwner = cachedOwnerScope
        if (slot >= 0 && actualOwner != null) {
            if (PerfFlags.PIC_DEBUG_COUNTERS) {
                if (ownerValid) PerfStats.fastLocalHit++ else PerfStats.fastLocalMiss++
            }
            return actualOwner.getSlotRecord(slot)
        }
        // Try per-frame local binding maps in the ancestry first (locals declared in frames)
        run {
            var s: Scope? = scope
            while (s != null) {
                s.localBindings[name]?.let { return it }
                s = s.parent
            }
        }
        // Try to find a direct local binding in the current ancestry (without invoking name resolution that may prefer fields)
        var s: Scope? = scope
        while (s != null) {
            s.objects[name]?.let { return it }
            s = s.parent
        }
        // Fallback to standard name lookup (locals or closure chain) if the slot owner changed across suspension
        scope[name]?.let { return it }
        // As a last resort, treat as field on `this`
        return scope.thisObj.readField(scope, name)
    }

    override suspend fun evalValue(scope: Scope): Obj {
        scope.pos = atPos
        val ownerValid = isOwnerValidFor(scope)
        val slot = if (ownerValid && cachedSlot >= 0) cachedSlot else resolveSlotInAncestry(scope)
        val actualOwner = cachedOwnerScope
        if (slot >= 0 && actualOwner != null) return actualOwner.getSlotRecord(slot).value
        // Try per-frame local binding maps in the ancestry first
        run {
            var s: Scope? = scope
            while (s != null) {
                s.localBindings[name]?.let { return it.value }
                s = s.parent
            }
        }
        // Try to find a direct local binding in the current ancestry first
        var s: Scope? = scope
        while (s != null) {
            s.objects[name]?.let { return it.value }
            s = s.parent
        }
        // Fallback to standard name lookup (locals or closure chain)
        scope[name]?.let { return it.value }
        return scope.thisObj.readField(scope, name).value
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        scope.pos = atPos
        val owner = if (isOwnerValidFor(scope)) cachedOwnerScope else null
        val slot = if (owner != null && cachedSlot >= 0) cachedSlot else resolveSlotInAncestry(scope)
        val actualOwner = cachedOwnerScope
        if (slot >= 0 && actualOwner != null) {
            val rec = actualOwner.getSlotRecord(slot)
            if (!rec.isMutable) scope.raiseError("Cannot assign to immutable value")
            rec.value = newValue
            return
        }
        // Try per-frame local binding maps in the ancestry first
        run {
            var s: Scope? = scope
            while (s != null) {
                val rec = s.localBindings[name]
                if (rec != null) {
                    if (!rec.isMutable) scope.raiseError("Cannot assign to immutable value")
                    rec.value = newValue
                    return
                }
                s = s.parent
            }
        }
        // Fallback to standard name lookup
        scope[name]?.let { stored ->
            if (stored.isMutable) stored.value = newValue
            else scope.raiseError("Cannot assign to immutable value")
            return
        }
        scope.thisObj.writeField(scope, name, newValue)
        return
    }
}

class ListLiteralRef(private val entries: List<ListEntry>) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        // Heuristic capacity hint: count element entries; spreads handled opportunistically
        val elemCount = entries.count { it is ListEntry.Element }
        val list = ArrayList<Obj>(elemCount)
        for (e in entries) {
            when (e) {
                is ListEntry.Element -> {
                    val v = if (PerfFlags.RVAL_FASTPATH) e.ref.evalValue(scope) else e.ref.get(scope).value
                    list += v
                }
                is ListEntry.Spread -> {
                    val elements = if (PerfFlags.RVAL_FASTPATH) e.ref.evalValue(scope) else e.ref.get(scope).value
                    when (elements) {
                        is ObjList -> {
                            // Grow underlying array once when possible
                            if (list is ArrayList) list.ensureCapacity(list.size + elements.list.size)
                            list.addAll(elements.list)
                        }
                        else -> scope.raiseError("Spread element must be list")
                    }
                }
            }
        }
        return ObjList(list).asReadonly
    }
}

/**
 * Range literal: left .. right or left ..< right. Right may be omitted in certain contexts.
 */
class RangeRef(
    private val left: ObjRef?,
    private val right: ObjRef?,
    private val isEndInclusive: Boolean
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val l = left?.let { if (PerfFlags.RVAL_FASTPATH) it.evalValue(scope) else it.get(scope).value } ?: ObjNull
        val r = right?.let { if (PerfFlags.RVAL_FASTPATH) it.evalValue(scope) else it.get(scope).value } ?: ObjNull
        return ObjRange(l, r, isEndInclusive = isEndInclusive).asReadonly
    }
}

/** Simple assignment: target = value */
class AssignRef(
    private val target: ObjRef,
    private val value: ObjRef,
    private val atPos: Pos,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val v = if (PerfFlags.RVAL_FASTPATH) value.evalValue(scope) else value.get(scope).value
        val rec = target.get(scope)
        if (!rec.isMutable) throw ScriptError(atPos, "cannot assign to immutable variable")
        if (rec.value.assign(scope, v) == null) {
            target.setAt(atPos, scope, v)
        }
        return v.asReadonly
    }
}

    // (duplicate LocalVarRef removed; the canonical implementation is defined earlier in this file)