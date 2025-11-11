/*
 * Copyright 2025 Sergey S. Chernov
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
 */

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
enum class UnaryOp { NOT, NEGATE }

/** Binary operations supported by ObjRef. */
enum class BinOp {
    OR, AND,
    EQARROW, EQ, NEQ, REF_EQ, REF_NEQ, MATCH, NOTMATCH,
    LTE, LT, GTE, GT,
    IN, NOTIN,
    IS, NOTIS,
    SHUTTLE,
    PLUS, MINUS, STAR, SLASH, PERCENT
}

/** R-value reference for unary operations. */
class UnaryOpRef(private val op: UnaryOp, private val a: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val v = if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) a.evalValue(scope) else a.get(scope).value
        val r = when (op) {
            UnaryOp.NOT -> v.logicalNot(scope)
            UnaryOp.NEGATE -> v.negate(scope)
        }
        return r.asReadonly
    }
}

/** R-value reference for binary operations. */
class BinaryOpRef(private val op: BinOp, private val left: ObjRef, private val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val a = if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) left.evalValue(scope) else left.get(scope).value
        val b = if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) right.evalValue(scope) else right.get(scope).value

        // Primitive fast paths for common cases (guarded by PerfFlags.PRIMITIVE_FASTOPS)
        if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS) {
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
                    if (net.sergeych.lyng.PerfFlags.PIC_DEBUG_COUNTERS) net.sergeych.lyng.PerfStats.primitiveFastOpsHit++
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
                    BinOp.EQ -> if (av == bv) ObjTrue else ObjFalse
                    BinOp.NEQ -> if (av != bv) ObjTrue else ObjFalse
                    BinOp.LT -> if (av < bv) ObjTrue else ObjFalse
                    BinOp.LTE -> if (av <= bv) ObjTrue else ObjFalse
                    BinOp.GT -> if (av > bv) ObjTrue else ObjFalse
                    BinOp.GTE -> if (av >= bv) ObjTrue else ObjFalse
                    else -> null
                }
                if (r != null) {
                    if (net.sergeych.lyng.PerfFlags.PIC_DEBUG_COUNTERS) net.sergeych.lyng.PerfStats.primitiveFastOpsHit++
                    return r.asReadonly
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
                    if (net.sergeych.lyng.PerfFlags.PIC_DEBUG_COUNTERS) net.sergeych.lyng.PerfStats.primitiveFastOpsHit++
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
            BinOp.PLUS -> a.plus(scope, b)
            BinOp.MINUS -> a.minus(scope, b)
            BinOp.STAR -> a.mul(scope, b)
            BinOp.SLASH -> a.div(scope, b)
            BinOp.PERCENT -> a.mod(scope, b)
        }
        return r.asReadonly
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
        val y = if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) value.evalValue(scope) else value.get(scope).value
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
        val a = if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) left.evalValue(scope) else left.get(scope).value
        val r = if (a != ObjNull) a else if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) right.evalValue(scope) else right.get(scope).value
        return r.asReadonly
    }
}

/** Logical OR with short-circuit: a || b */
class LogicalOrRef(private val left: ObjRef, private val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val a = if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) left.evalValue(scope) else left.get(scope).value
        if ((a as? ObjBool)?.value == true) return ObjTrue.asReadonly
        val b = if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) right.evalValue(scope) else right.get(scope).value
        if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS) {
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
        val fastRval = net.sergeych.lyng.PerfFlags.RVAL_FASTPATH
        val fastPrim = net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS
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

    override suspend fun get(scope: Scope): ObjRecord {
        val fastRval = net.sergeych.lyng.PerfFlags.RVAL_FASTPATH
        val fieldPic = net.sergeych.lyng.PerfFlags.FIELD_PIC
        val picCounters = net.sergeych.lyng.PerfFlags.PIC_DEBUG_COUNTERS
        val base = if (fastRval) target.evalValue(scope) else target.get(scope).value
        if (base == ObjNull && isOptional) return ObjNull.asMutable
        if (fieldPic) {
            val (key, ver) = receiverKeyAndVersion(base)
            rGetter1?.let { g -> if (key == rKey1 && ver == rVer1) {
                if (picCounters) net.sergeych.lyng.PerfStats.fieldPicHit++
                val rec0 = g(base, scope)
                if (base is ObjClass) {
                    val idx0 = base.classScope?.getSlotIndexOf(name)
                    if (idx0 != null) { tKey = key; tVer = ver; tFrameId = scope.frameId; tRecord = rec0 } else { tRecord = null }
                } else { tRecord = null }
                return rec0
            } }
            rGetter2?.let { g -> if (key == rKey2 && ver == rVer2) {
                if (picCounters) net.sergeych.lyng.PerfStats.fieldPicHit++
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
            rGetter3?.let { g -> if (key == rKey3 && ver == rVer3) {
                if (picCounters) net.sergeych.lyng.PerfStats.fieldPicHit++
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
            rGetter4?.let { g -> if (key == rKey4 && ver == rVer4) {
                if (picCounters) net.sergeych.lyng.PerfStats.fieldPicHit++
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
            if (picCounters) net.sergeych.lyng.PerfStats.fieldPicMiss++
            val rec = base.readField(scope, name)
            // Install move-to-front with a handle-aware getter (shift 1→2→3→4; put new at 1)
            rKey4 = rKey3; rVer4 = rVer3; rGetter4 = rGetter3
            rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
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
        val fieldPic = net.sergeych.lyng.PerfFlags.FIELD_PIC
        val picCounters = net.sergeych.lyng.PerfFlags.PIC_DEBUG_COUNTERS
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
                if (picCounters) net.sergeych.lyng.PerfStats.fieldPicSetHit++
                return s(base, scope, newValue)
            } }
            wSetter2?.let { s -> if (key == wKey2 && ver == wVer2) {
                if (picCounters) net.sergeych.lyng.PerfStats.fieldPicSetHit++
                // move-to-front: promote 2→1
                val tK = wKey2; val tV = wVer2; val tS = wSetter2
                wKey2 = wKey1; wVer2 = wVer1; wSetter2 = wSetter1
                wKey1 = tK; wVer1 = tV; wSetter1 = tS
                return s(base, scope, newValue)
            } }
            wSetter3?.let { s -> if (key == wKey3 && ver == wVer3) {
                if (picCounters) net.sergeych.lyng.PerfStats.fieldPicSetHit++
                // move-to-front: promote 3→1
                val tK = wKey3; val tV = wVer3; val tS = wSetter3
                wKey3 = wKey2; wVer3 = wVer2; wSetter3 = wSetter2
                wKey2 = wKey1; wVer2 = wVer1; wSetter2 = wSetter1
                wKey1 = tK; wVer1 = tV; wSetter1 = tS
                return s(base, scope, newValue)
            } }
            wSetter4?.let { s -> if (key == wKey4 && ver == wVer4) {
                if (picCounters) net.sergeych.lyng.PerfStats.fieldPicSetHit++
                // move-to-front: promote 4→1
                val tK = wKey4; val tV = wVer4; val tS = wSetter4
                wKey4 = wKey3; wVer4 = wVer3; wSetter4 = wSetter3
                wKey3 = wKey2; wVer3 = wVer2; wSetter3 = wSetter2
                wKey2 = wKey1; wVer2 = wVer1; wSetter2 = wSetter1
                wKey1 = tK; wVer1 = tV; wSetter1 = tS
                return s(base, scope, newValue)
            } }
            // Slow path
            if (picCounters) net.sergeych.lyng.PerfStats.fieldPicSetMiss++
            base.writeField(scope, name, newValue)
            // Install move-to-front with a handle-aware setter (shift 1→2→3→4; put new at 1)
            wKey4 = wKey3; wVer4 = wVer3; wSetter4 = wSetter3
            wKey3 = wKey2; wVer3 = wVer2; wSetter3 = wSetter2
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
}

/**
 * Reference to index access (a[i]) with optional chaining.
 */
class IndexRef(
    private val target: ObjRef,
    private val index: ObjRef,
    private val isOptional: Boolean,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val fastRval = net.sergeych.lyng.PerfFlags.RVAL_FASTPATH
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
        }
        return base.getAt(scope, idx).asMutable
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        val fastRval = net.sergeych.lyng.PerfFlags.RVAL_FASTPATH
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
        val fastRval = net.sergeych.lyng.PerfFlags.RVAL_FASTPATH
        val usePool = net.sergeych.lyng.PerfFlags.SCOPE_POOL
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

    override suspend fun get(scope: Scope): ObjRecord {
        val fastRval = net.sergeych.lyng.PerfFlags.RVAL_FASTPATH
        val methodPic = net.sergeych.lyng.PerfFlags.METHOD_PIC
        val picCounters = net.sergeych.lyng.PerfFlags.PIC_DEBUG_COUNTERS
        val base = if (fastRval) receiver.evalValue(scope) else receiver.get(scope).value
        if (base == ObjNull && isOptional) return ObjNull.asReadonly
        val callArgs = args.toArguments(scope, tailBlock)
        if (methodPic) {
            val (key, ver) = receiverKeyAndVersion(base)
            mInvoker1?.let { inv -> if (key == mKey1 && ver == mVer1) {
                if (picCounters) net.sergeych.lyng.PerfStats.methodPicHit++
                return inv(base, scope, callArgs).asReadonly
            } }
            mInvoker2?.let { inv -> if (key == mKey2 && ver == mVer2) {
                if (picCounters) net.sergeych.lyng.PerfStats.methodPicHit++
                return inv(base, scope, callArgs).asReadonly
            } }
            mInvoker3?.let { inv -> if (key == mKey3 && ver == mVer3) {
                if (picCounters) net.sergeych.lyng.PerfStats.methodPicHit++
                // move-to-front: promote 3→1
                val tK = mKey3; val tV = mVer3; val tI = mInvoker3
                mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                mKey1 = tK; mVer1 = tV; mInvoker1 = tI
                return inv(base, scope, callArgs).asReadonly
            } }
            mInvoker4?.let { inv -> if (key == mKey4 && ver == mVer4) {
                if (picCounters) net.sergeych.lyng.PerfStats.methodPicHit++
                // move-to-front: promote 4→1
                val tK = mKey4; val tV = mVer4; val tI = mInvoker4
                mKey4 = mKey3; mVer4 = mVer3; mInvoker4 = mInvoker3
                mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                mKey1 = tK; mVer1 = tV; mInvoker1 = tI
                return inv(base, scope, callArgs).asReadonly
            } }
            // Slow path
            if (picCounters) net.sergeych.lyng.PerfStats.methodPicMiss++
            val result = base.invokeInstanceMethod(scope, name, callArgs)
            // Install move-to-front with a handle-aware invoker: shift 1→2→3→4, put new at 1
            mKey4 = mKey3; mVer4 = mVer3; mInvoker4 = mInvoker3
            mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
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
        if (!PerfFlags.LOCAL_SLOT_PIC) {
            scope.getSlotIndexOf(name)?.let { 
                if (net.sergeych.lyng.PerfFlags.PIC_DEBUG_COUNTERS) net.sergeych.lyng.PerfStats.localVarPicHit++
                return scope.getSlotRecord(it) 
            }
            if (net.sergeych.lyng.PerfFlags.PIC_DEBUG_COUNTERS) net.sergeych.lyng.PerfStats.localVarPicMiss++
            return scope[name] ?: scope.raiseError("symbol not defined: '$name'")
        }
        val hit = (cachedFrameId == scope.frameId && cachedSlot >= 0 && cachedSlot < scope.slotCount())
        val slot = if (hit) cachedSlot else resolveSlot(scope)
        if (slot >= 0) {
            if (net.sergeych.lyng.PerfFlags.PIC_DEBUG_COUNTERS) {
                if (hit) net.sergeych.lyng.PerfStats.localVarPicHit++ else net.sergeych.lyng.PerfStats.localVarPicMiss++
            }
            return scope.getSlotRecord(slot)
        }
        if (net.sergeych.lyng.PerfFlags.PIC_DEBUG_COUNTERS) net.sergeych.lyng.PerfStats.localVarPicMiss++
        return scope[name] ?: scope.raiseError("symbol not defined: '$name'")
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
            val stored = scope[name] ?: scope.raiseError("symbol not defined: '$name'")
            if (stored.isMutable) stored.value = newValue
            else scope.raiseError("Cannot assign to immutable value")
            return
        }
        val slot = if (cachedFrameId == scope.frameId && cachedSlot >= 0 && cachedSlot < scope.slotCount()) cachedSlot else resolveSlot(scope)
        if (slot >= 0) {
            val rec = scope.getSlotRecord(slot)
            if (!rec.isMutable) scope.raiseError("Cannot assign to immutable value")
            rec.value = newValue
            return
        }
        val stored = scope[name] ?: scope.raiseError("symbol not defined: '$name'")
        if (stored.isMutable) stored.value = newValue
        else scope.raiseError("Cannot assign to immutable value")
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
        if (slot < 0 || actualOwner == null) scope.raiseError("local '$name' is not available in this scope")
        if (net.sergeych.lyng.PerfFlags.PIC_DEBUG_COUNTERS) {
            if (ownerValid) net.sergeych.lyng.PerfStats.fastLocalHit++ else net.sergeych.lyng.PerfStats.fastLocalMiss++
        }
        return actualOwner.getSlotRecord(slot)
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        scope.pos = atPos
        val owner = if (isOwnerValidFor(scope)) cachedOwnerScope else null
        val slot = if (owner != null && cachedSlot >= 0) cachedSlot else resolveSlotInAncestry(scope)
        val actualOwner = cachedOwnerScope
        if (slot < 0 || actualOwner == null) scope.raiseError("local '$name' is not available in this scope")
        val rec = actualOwner.getSlotRecord(slot)
        if (!rec.isMutable) scope.raiseError("Cannot assign to immutable value")
        rec.value = newValue
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
                    val v = if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) e.ref.evalValue(scope) else e.ref.get(scope).value
                    list += v
                }
                is ListEntry.Spread -> {
                    val elements = if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) e.ref.evalValue(scope) else e.ref.get(scope).value
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
        val l = left?.let { if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) it.evalValue(scope) else it.get(scope).value } ?: ObjNull
        val r = right?.let { if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) it.evalValue(scope) else it.get(scope).value } ?: ObjNull
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
        val v = if (net.sergeych.lyng.PerfFlags.RVAL_FASTPATH) value.evalValue(scope) else value.get(scope).value
        val rec = target.get(scope)
        if (!rec.isMutable) throw ScriptError(atPos, "cannot assign to immutable variable")
        if (rec.value.assign(scope, v) == null) {
            target.setAt(atPos, scope, v)
        }
        return v.asReadonly
    }
}
