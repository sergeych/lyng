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
        val rec = get(scope)
        if (rec.type == ObjRecord.Type.Delegated) {
            val receiver = rec.receiver ?: scope.thisObj
            // Use resolve to handle delegated property logic
            return scope.resolve(rec, "unknown")
        }
        // Template record: must map to instance storage
        if (rec.receiver != null && rec.declaringClass != null) {
            return rec.receiver!!.resolveRecord(scope, rec, "unknown", rec.declaringClass).value
        }
        return rec.value
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
class UnaryOpRef(internal val op: UnaryOp, internal val a: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val v = a.evalValue(scope)
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

    override suspend fun evalValue(scope: Scope): Obj {
        val v = a.evalValue(scope)
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
                return rFast
            }
        }
        return when (op) {
            UnaryOp.NOT -> v.logicalNot(scope)
            UnaryOp.NEGATE -> v.negate(scope)
            UnaryOp.BITNOT -> v.bitNot(scope)
        }
    }
}

/** R-value reference for binary operations. */
class BinaryOpRef(internal val op: BinOp, internal val left: ObjRef, internal val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        return evalValue(scope).asReadonly
    }

    override suspend fun evalValue(scope: Scope): Obj {
        val a = left.evalValue(scope)
        val b = right.evalValue(scope)

        // Primitive fast paths for common cases (guarded by PerfFlags.PRIMITIVE_FASTOPS)
        if (PerfFlags.PRIMITIVE_FASTOPS) {
            // Fast membership for common containers
            if (op == BinOp.IN || op == BinOp.NOTIN) {
                val inResult: Boolean? = when (b) {
                    is ObjList -> {
                        if (a is ObjInt) {
                            var i = 0
                            val sz = b.list.size
                            var found = false
                            while (i < sz) {
                                val v = b.list[i]
                                if (v is ObjInt && v.value == a.value) {
                                    found = true
                                    break
                                }
                                i++
                            }
                            found
                        } else {
                            b.list.contains(a)
                        }
                    }
                    is ObjSet -> b.set.contains(a)
                    is ObjMap -> b.map.containsKey(a)
                    is ObjRange -> {
                        when (a) {
                            is ObjInt -> {
                                val s = b.start as? ObjInt
                                val e = b.end as? ObjInt
                                val v = a.value
                                if (s == null && e == null) null
                                else {
                                    if (s != null && v < s.value) false
                                    else if (e != null) if (b.isEndInclusive) v <= e.value else v < e.value else true
                                }
                            }
                            is ObjChar -> {
                                val s = b.start as? ObjChar
                                val e = b.end as? ObjChar
                                val v = a.value
                                if (s == null && e == null) null
                                else {
                                    if (s != null && v < s.value) false
                                    else if (e != null) if (b.isEndInclusive) v <= e.value else v < e.value else true
                                }
                            }
                            is ObjString -> {
                                val s = b.start as? ObjString
                                val e = b.end as? ObjString
                                val v = a.value
                                if (s == null && e == null) null
                                else {
                                    if (s != null && v < s.value) false
                                    else if (e != null) if (b.isEndInclusive) v <= e.value else v < e.value else true
                                }
                            }
                            else -> null
                        }
                    }
                    is ObjString -> when (a) {
                        is ObjString -> b.value.contains(a.value)
                        is ObjChar -> b.value.contains(a.value)
                        else -> null
                    }
                    else -> null
                }
                if (inResult != null) {
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                    return if (op == BinOp.IN) {
                        if (inResult) ObjTrue else ObjFalse
                    } else {
                        if (inResult) ObjFalse else ObjTrue
                    }
                }
            }
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
                    return r
                }
            }
            // Fast integer ops when both operands are ObjInt
            if (a is ObjInt && b is ObjInt) {
                val av = a.value
                val bv = b.value
                val r: Obj? = when (op) {
                    BinOp.PLUS -> ObjInt.of(av + bv)
                    BinOp.MINUS -> ObjInt.of(av - bv)
                    BinOp.STAR -> ObjInt.of(av * bv)
                    BinOp.SLASH -> if (bv != 0L) ObjInt.of(av / bv) else null
                    BinOp.PERCENT -> if (bv != 0L) ObjInt.of(av % bv) else null
                    BinOp.BAND -> ObjInt.of(av and bv)
                    BinOp.BXOR -> ObjInt.of(av xor bv)
                    BinOp.BOR -> ObjInt.of(av or bv)
                    BinOp.SHL -> ObjInt.of(av shl (bv.toInt() and 63))
                    BinOp.SHR -> ObjInt.of(av shr (bv.toInt() and 63))
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
                    return r
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
                    return r
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
                    return r
                }
            }
            // Fast concatenation for String with Int/Char on either side
            if (op == BinOp.PLUS) {
                when {
                    a is ObjString && b is ObjInt -> {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                        return ObjString(a.value + b.value.toString())
                    }
                    a is ObjString && b is ObjChar -> {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                        return ObjString(a.value + b.value)
                    }
                    b is ObjString && a is ObjInt -> {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                        return ObjString(a.value.toString() + b.value)
                    }
                    b is ObjString && a is ObjChar -> {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.primitiveFastOpsHit++
                        return ObjString(a.value.toString() + b.value)
                    }
                }
            }
            // Fast numeric mixed ops for Int/Real combinations by promoting to double
            if ((a is ObjInt || a is ObjReal) && (b is ObjInt || b is ObjReal)) {
                val ad: Double = if (a is ObjInt) a.doubleValue else (a as ObjReal).value
                val bd: Double = if (b is ObjInt) b.doubleValue else (b as ObjReal).value
                val rNum: Obj? = when (op) {
                    BinOp.PLUS -> ObjReal.of(ad + bd)
                    BinOp.MINUS -> ObjReal.of(ad - bd)
                    BinOp.STAR -> ObjReal.of(ad * bd)
                    BinOp.SLASH -> ObjReal.of(ad / bd)
                    BinOp.PERCENT -> ObjReal.of(ad % bd)
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
                    return rNum
                }
            }
        }

        val r: Obj = when (op) {
            BinOp.OR -> a.logicalOr(scope, b)
            BinOp.AND -> a.logicalAnd(scope, b)
            BinOp.EQARROW -> ObjMapEntry(a, b)
            BinOp.EQ -> ObjBool(a.equals(scope, b))
            BinOp.NEQ -> ObjBool(!a.equals(scope, b))
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
        return r
    }
}

/** Conditional (ternary) operator reference: cond ? a : b */
class ConditionalRef(
    private val condition: ObjRef,
    private val ifTrue: ObjRef,
    private val ifFalse: ObjRef
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        return evalCondition(scope).get(scope)
    }

    override suspend fun evalValue(scope: Scope): Obj {
        return evalCondition(scope).evalValue(scope)
    }

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
    override suspend fun get(scope: Scope): ObjRecord {
        val v0 = valueRef.evalValue(scope)
        val t = typeRef.evalValue(scope)
        val target = (t as? ObjClass) ?: scope.raiseClassCastError("${t} is not the class instance")
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
                "Cannot cast ${(v as? Obj)?.objClass?.className ?: v::class.simpleName} to ${target.className}"
            )
        }
    }

    override suspend fun evalValue(scope: Scope): Obj {
        val v0 = valueRef.evalValue(scope)
        val t = typeRef.evalValue(scope)
        val target = (t as? ObjClass) ?: scope.raiseClassCastError("${t} is not the class instance")
        // unwrap qualified views
        val v = when (v0) {
            is ObjQualifiedView -> v0.instance
            else -> v0
        }
        return if (v.isInstanceOf(target)) {
            // For instances, return a qualified view to enforce ancestor-start dispatch
            if (v is ObjInstance) ObjQualifiedView(v, target) else v
        } else {
            if (isNullable) ObjNull else scope.raiseClassCastError(
                "Cannot cast ${(v as? Obj)?.objClass?.className ?: v::class.simpleName} to ${target.className}"
            )
        }
    }
}

/** Qualified `this@Type`: resolves to a view of current `this` starting dispatch from the ancestor Type. */
class QualifiedThisRef(val typeName: String, private val atPos: Pos) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val t = scope[typeName]?.value as? ObjClass
            ?: scope.raiseError("unknown type $typeName")

        var s: Scope? = scope
        while (s != null) {
            val inst = s.thisObj as? ObjInstance
            if (inst != null) {
                if (inst.objClass === t || inst.objClass.allParentsSet.contains(t)) {
                    return ObjQualifiedView(inst, t).asReadonly
                }
            }
            s = s.parent
        }

        scope.raiseClassCastError(
            "No instance of type ${t.className} found in the scope chain"
        )
    }
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
    private val isOptional: Boolean
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val (inst, startClass) = resolveQualifiedThisInstance(scope, typeName)
        if (isOptional && inst == ObjNull) return ObjNull.asMutable

        if (startClass !== inst.objClass) {
            return ObjQualifiedView(inst, startClass).readField(scope, name)
        }

        val caller = scope.currentClassCtx
        if (caller != null) {
            val mangled = caller.mangledName(name)
            inst.fieldRecordForKey(mangled)?.let { rec ->
                if (rec.visibility == Visibility.Private) {
                    return inst.resolveRecord(scope, rec, name, caller)
                }
            }
            inst.methodRecordForKey(mangled)?.let { rec ->
                if (rec.visibility == Visibility.Private) {
                    return inst.resolveRecord(scope, rec, name, caller)
                }
            }
        }

        val key = inst.objClass.publicMemberResolution[name] ?: name
        inst.fieldRecordForKey(key)?.let { rec ->
            if ((rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField) && !rec.isAbstract)
                return rec
        }
        inst.methodRecordForKey(key)?.let { rec ->
            if (!rec.isAbstract) {
                val decl = rec.declaringClass ?: inst.objClass.findDeclaringClassOf(name) ?: inst.objClass
                return inst.resolveRecord(scope, rec, name, decl)
            }
        }

        return inst.readField(scope, name)
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        val (inst, startClass) = resolveQualifiedThisInstance(scope, typeName)
        if (isOptional && inst == ObjNull) return

        if (startClass !== inst.objClass) {
            ObjQualifiedView(inst, startClass).writeField(scope, name, newValue)
            return
        }

        val caller = scope.currentClassCtx
        if (caller != null) {
            val mangled = caller.mangledName(name)
            inst.fieldRecordForKey(mangled)?.let { rec ->
                if (rec.visibility == Visibility.Private) {
                    writeDirectOrFallback(scope, inst, rec, name, newValue, caller)
                    return
                }
            }
            inst.methodRecordForKey(mangled)?.let { rec ->
                if (rec.visibility == Visibility.Private &&
                    (rec.type == ObjRecord.Type.Property || rec.type == ObjRecord.Type.Delegated)) {
                    inst.writeField(scope, name, newValue)
                    return
                }
            }
        }

        val key = inst.objClass.publicMemberResolution[name] ?: name
        inst.fieldRecordForKey(key)?.let { rec ->
            val decl = rec.declaringClass ?: inst.objClass.findDeclaringClassOf(name)
            if (canAccessMember(rec.effectiveWriteVisibility, decl, caller, name)) {
                writeDirectOrFallback(scope, inst, rec, name, newValue, decl)
                return
            }
        }
        inst.methodRecordForKey(key)?.let { rec ->
            if (rec.effectiveWriteVisibility == Visibility.Public &&
                (rec.type == ObjRecord.Type.Property || rec.type == ObjRecord.Type.Delegated)) {
                inst.writeField(scope, name, newValue)
                return
            }
        }

        inst.writeField(scope, name, newValue)
    }

    private suspend fun writeDirectOrFallback(
        scope: Scope,
        inst: ObjInstance,
        rec: ObjRecord,
        name: String,
        newValue: Obj,
        decl: ObjClass?
    ) {
        if ((rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField) && !rec.isAbstract) {
            if (!rec.isMutable && rec.value !== ObjUnset) {
                ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
            }
            if (rec.value.assign(scope, newValue) == null) rec.value = newValue
        } else {
            inst.writeField(scope, name, newValue)
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
    private val args: List<ParsedArgument>,
    private val tailBlock: Boolean,
    private val isOptional: Boolean
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = evalValue(scope).asReadonly

    override suspend fun evalValue(scope: Scope): Obj {
        val (inst, startClass) = resolveQualifiedThisInstance(scope, typeName)
        if (isOptional && inst == ObjNull) return ObjNull
        val callArgs = args.toArguments(scope, tailBlock)

        if (startClass !== inst.objClass) {
            return ObjQualifiedView(inst, startClass).invokeInstanceMethod(scope, name, callArgs, null)
        }

        val caller = scope.currentClassCtx
        if (caller != null) {
            val mangled = caller.mangledName(name)
            inst.methodRecordForKey(mangled)?.let { rec ->
                if (rec.visibility == Visibility.Private && !rec.isAbstract) {
                    if (rec.type == ObjRecord.Type.Property) {
                        if (callArgs.isEmpty()) return (rec.value as ObjProperty).callGetter(scope, inst, caller)
                    } else if (rec.type == ObjRecord.Type.Fun) {
                        return rec.value.invoke(inst.instanceScope, inst, callArgs, caller)
                    }
                }
            }
        }

        val key = inst.objClass.publicMemberResolution[name] ?: name
        inst.methodRecordForKey(key)?.let { rec ->
            if (!rec.isAbstract) {
                val decl = rec.declaringClass ?: inst.objClass.findDeclaringClassOf(name) ?: inst.objClass
                val effectiveCaller = caller ?: if (scope.thisObj === inst) inst.objClass else null
                if (!canAccessMember(rec.visibility, decl, effectiveCaller, name))
                    scope.raiseError(ObjIllegalAccessException(scope, "can't invoke method $name (declared in ${decl.className})"))
                if (rec.type == ObjRecord.Type.Property) {
                    if (callArgs.isEmpty()) return (rec.value as ObjProperty).callGetter(scope, inst, decl)
                } else if (rec.type == ObjRecord.Type.Fun) {
                    return rec.value.invoke(inst.instanceScope, inst, callArgs, decl)
                }
            }
        }

        return inst.invokeInstanceMethod(scope, name, callArgs)
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
        val x = target.evalValue(scope)
        val y = value.evalValue(scope)
        val inPlace: Obj? = when (op) {
            BinOp.PLUS -> x.plusAssign(scope, y)
            BinOp.MINUS -> x.minusAssign(scope, y)
            BinOp.STAR -> x.mulAssign(scope, y)
            BinOp.SLASH -> x.divAssign(scope, y)
            BinOp.PERCENT -> x.modAssign(scope, y)
            else -> null
        }
        if (inPlace != null) return inPlace.asReadonly
        val fast: Obj? = if (PerfFlags.PRIMITIVE_FASTOPS) {
            when {
                x is ObjInt && y is ObjInt -> {
                    val xv = x.value
                    val yv = y.value
                    when (op) {
                        BinOp.PLUS -> ObjInt.of(xv + yv)
                        BinOp.MINUS -> ObjInt.of(xv - yv)
                        BinOp.STAR -> ObjInt.of(xv * yv)
                        BinOp.SLASH -> if (yv != 0L) ObjInt.of(xv / yv) else null
                        BinOp.PERCENT -> if (yv != 0L) ObjInt.of(xv % yv) else null
                        else -> null
                    }
                }
                (x is ObjInt || x is ObjReal) && (y is ObjInt || y is ObjReal) -> {
                    val xv = if (x is ObjInt) x.doubleValue else (x as ObjReal).value
                    val yv = if (y is ObjInt) y.doubleValue else (y as ObjReal).value
                    when (op) {
                        BinOp.PLUS -> ObjReal.of(xv + yv)
                        BinOp.MINUS -> ObjReal.of(xv - yv)
                        BinOp.STAR -> ObjReal.of(xv * yv)
                        BinOp.SLASH -> ObjReal.of(xv / yv)
                        BinOp.PERCENT -> ObjReal.of(xv % yv)
                        else -> null
                    }
                }
                x is ObjString && op == BinOp.PLUS -> ObjString(x.value + y.toString())
                else -> null
            }
        } else null
        val result: Obj = fast ?: when (op) {
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
        val v = target.evalValue(scope)
        val one = ObjInt.One
        // We now treat numbers as immutable and always perform write-back via setAt.
        // This avoids issues where literals are shared and mutated in-place.
        // For post-inc: return ORIGINAL value; for pre-inc: return NEW value.
        val result = if (PerfFlags.PRIMITIVE_FASTOPS) {
            when (v) {
                is ObjInt -> if (isIncrement) ObjInt.of(v.value + 1L) else ObjInt.of(v.value - 1L)
                is ObjReal -> if (isIncrement) ObjReal.of(v.value + 1.0) else ObjReal.of(v.value - 1.0)
                else -> if (isIncrement) v.plus(scope, one) else v.minus(scope, one)
            }
        } else {
            if (isIncrement) v.plus(scope, one) else v.minus(scope, one)
        }
        target.setAt(atPos, scope, result)
        return (if (isPost) v else result).asReadonly
    }
}

/** Elvis operator reference: a ?: b */
class ElvisRef(private val left: ObjRef, private val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val a = left.evalValue(scope)
        val r = if (a != ObjNull) a else right.evalValue(scope)
        return r.asReadonly
    }
}

/** Logical OR with short-circuit: a || b */
class LogicalOrRef(private val left: ObjRef, private val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        return evalValue(scope).asReadonly
    }

    override suspend fun evalValue(scope: Scope): Obj {
        val a = left.evalValue(scope)
        if ((a as? ObjBool)?.value == true) return ObjTrue
        val b = right.evalValue(scope)
        if (PerfFlags.PRIMITIVE_FASTOPS) {
            if (a is ObjBool && b is ObjBool) {
                return if (a.value || b.value) ObjTrue else ObjFalse
            }
        }
        return a.logicalOr(scope, b)
    }
}

/** Logical AND with short-circuit: a && b */
class LogicalAndRef(private val left: ObjRef, private val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        return evalValue(scope).asReadonly
    }

    override suspend fun evalValue(scope: Scope): Obj {
        val a = left.evalValue(scope)
        if ((a as? ObjBool)?.value == false) return ObjFalse
        val b = right.evalValue(scope)
        if (PerfFlags.PRIMITIVE_FASTOPS) {
            if (a is ObjBool && b is ObjBool) {
                return if (a.value && b.value) ObjTrue else ObjFalse
            }
        }
        return a.logicalAnd(scope, b)
    }
}

/**
 * Read-only reference that always returns the same cached record.
 */
class ConstRef(private val record: ObjRecord) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = record
    override suspend fun evalValue(scope: Scope): Obj = record.value
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

    override suspend fun get(scope: Scope): ObjRecord {
        val fieldPic = PerfFlags.FIELD_PIC
        val picCounters = PerfFlags.PIC_DEBUG_COUNTERS
        val base = target.evalValue(scope)
        if (base == ObjNull && isOptional) return ObjNull.asMutable
        if (fieldPic) {
            val key: Long
            val ver: Int
            when (base) {
                is ObjInstance -> { key = base.objClass.classId; ver = base.objClass.layoutVersion }
                is ObjClass -> { key = base.classId; ver = base.layoutVersion }
                else -> { key = 0L; ver = -1 }
            }
            if (key != 0L) {
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
                                    sc.raiseError(ObjIllegalAccessException(sc, "can't access non-public field $name"))
                                r0
                            }
                        } else {
                            rKey1 = key; rVer1 = ver; rGetter1 = { obj, sc -> obj.readField(sc, name) }
                        }
                    }
                    is ObjInstance -> {
                        val cls = base.objClass
                        val effectiveKey = cls.publicMemberResolution[name]
                        if (effectiveKey != null) {
                            rKey1 = key; rVer1 = ver; rGetter1 = { obj, sc ->
                                if (obj is ObjInstance && obj.objClass === cls) {
                                    val slot = cls.fieldSlotForKey(effectiveKey)
                                    if (slot != null) {
                                        val idx = slot.slot
                                        val rec = if (idx >= 0 && idx < obj.fieldSlots.size) obj.fieldSlots[idx] else null
                                        if (rec != null &&
                                            (rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField) &&
                                            !rec.isAbstract) {
                                            rec
                                        } else obj.readField(sc, name)
                                    } else {
                                        val rec = obj.fieldRecordForKey(effectiveKey) ?: obj.instanceScope.objects[effectiveKey]
                                        if (rec != null && rec.type != ObjRecord.Type.Delegated) rec
                                        else obj.readField(sc, name)
                                    }
                                } else obj.readField(sc, name)
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
        }
        return base.readField(scope, name)
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        val fieldPic = PerfFlags.FIELD_PIC
        val picCounters = PerfFlags.PIC_DEBUG_COUNTERS
        val base = target.evalValue(scope)
        if (base == ObjNull && isOptional) {
            // no-op on null receiver for optional chaining assignment
            return
        }
        // Read→write micro fast-path: reuse transient record captured by get()
        if (fieldPic) {
            val key: Long
            val ver: Int
            when (base) {
                is ObjInstance -> { key = base.objClass.classId; ver = base.objClass.layoutVersion }
                is ObjClass -> { key = base.classId; ver = base.layoutVersion }
                else -> { key = 0L; ver = -1 }
            }
            val rec = tRecord
            if (rec != null && tKey == key && tVer == ver && tFrameId == scope.frameId) {
                // If it is a property, we must go through writeField (slow path for now)
                // or handle it here.
                if (rec.type != ObjRecord.Type.Property) {
                    // visibility/mutability checks
                    if (!rec.isMutable) scope.raiseError(ObjIllegalAssignmentException(scope, "can't reassign val $name"))
                    if (!rec.visibility.isPublic)
                        scope.raiseError(ObjIllegalAccessException(scope, "can't access non-public field $name"))
                    if (rec.value.assign(scope, newValue) == null) rec.value = newValue
                    return
                }
            }
            if (key != 0L) {
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
                    is ObjInstance -> {
                        val cls = base.objClass
                        val effectiveKey = cls.publicMemberResolution[name]
                        if (effectiveKey != null) {
                            wKey1 = key; wVer1 = ver; wSetter1 = { obj, sc, nv ->
                                if (obj is ObjInstance && obj.objClass === cls) {
                                    val slot = cls.fieldSlotForKey(effectiveKey)
                                    if (slot != null) {
                                        val idx = slot.slot
                                        val rec = if (idx >= 0 && idx < obj.fieldSlots.size) obj.fieldSlots[idx] else null
                                        if (rec != null &&
                                            rec.effectiveWriteVisibility == Visibility.Public &&
                                            rec.isMutable &&
                                            (rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField) &&
                                            !rec.isAbstract) {
                                            if (rec.value.assign(sc, nv) == null) rec.value = nv
                                        } else obj.writeField(sc, name, nv)
                                    } else {
                                        val rec = obj.fieldRecordForKey(effectiveKey) ?: obj.instanceScope.objects[effectiveKey]
                                        if (rec != null && rec.effectiveWriteVisibility == Visibility.Public && rec.isMutable &&
                                            (rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField)) {
                                            if (rec.value.assign(sc, nv) == null) rec.value = nv
                                        } else obj.writeField(sc, name, nv)
                                    }
                                } else obj.writeField(sc, name, nv)
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
        val fieldPic = PerfFlags.FIELD_PIC
        val picCounters = PerfFlags.PIC_DEBUG_COUNTERS
        val base = target.evalValue(scope)
        if (base == ObjNull && isOptional) return ObjNull
        if (fieldPic) {
            val key: Long
            val ver: Int
            when (base) {
                is ObjInstance -> { key = base.objClass.classId; ver = base.objClass.layoutVersion }
                is ObjClass -> { key = base.classId; ver = base.layoutVersion }
                else -> { key = 0L; ver = -1 }
            }
            if (key != 0L) {
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
                rKey1 = key; rVer1 = ver; rGetter1 = { obj, sc -> obj.readField(sc, name) }
                return rec.value
            }
        }
        return base.readField(scope, name).value
    }
}

/**
 * Fast path for direct `this.name` access using slot maps.
 * Falls back to normal member resolution when needed.
 */
class ThisFieldSlotRef(
    val name: String,
    private val isOptional: Boolean
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val th = scope.thisObj
        if (th == ObjNull && isOptional) return ObjNull.asMutable
        if (th !is ObjInstance) return th.readField(scope, name)

        val caller = scope.currentClassCtx
        if (caller != null) {
            val mangled = caller.mangledName(name)
            th.fieldRecordForKey(mangled)?.let { rec ->
                if (rec.visibility == Visibility.Private) {
                    return th.resolveRecord(scope, rec, name, caller)
                }
            }
            th.methodRecordForKey(mangled)?.let { rec ->
                if (rec.visibility == Visibility.Private) {
                    return th.resolveRecord(scope, rec, name, caller)
                }
            }
        }

        val key = th.objClass.publicMemberResolution[name] ?: name
        th.fieldRecordForKey(key)?.let { rec ->
            if ((rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField) && !rec.isAbstract)
                return rec
        }
        th.methodRecordForKey(key)?.let { rec ->
            if (!rec.isAbstract) {
                val decl = rec.declaringClass ?: th.objClass.findDeclaringClassOf(name) ?: th.objClass
                return th.resolveRecord(scope, rec, name, decl)
            }
        }

        return th.readField(scope, name)
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        val th = scope.thisObj
        if (th == ObjNull && isOptional) return
        if (th !is ObjInstance) {
            th.writeField(scope, name, newValue)
            return
        }

        val caller = scope.currentClassCtx
        if (caller != null) {
            val mangled = caller.mangledName(name)
            th.fieldRecordForKey(mangled)?.let { rec ->
                if (rec.visibility == Visibility.Private) {
                    writeDirectOrFallback(scope, th, rec, name, newValue, caller)
                    return
                }
            }
            th.methodRecordForKey(mangled)?.let { rec ->
                if (rec.visibility == Visibility.Private &&
                    (rec.type == ObjRecord.Type.Property || rec.type == ObjRecord.Type.Delegated)) {
                    th.writeField(scope, name, newValue)
                    return
                }
            }
        }

        val key = th.objClass.publicMemberResolution[name] ?: name
        th.fieldRecordForKey(key)?.let { rec ->
            val decl = rec.declaringClass ?: th.objClass.findDeclaringClassOf(name)
            if (canAccessMember(rec.effectiveWriteVisibility, decl, caller, name)) {
                writeDirectOrFallback(scope, th, rec, name, newValue, decl)
                return
            }
        }
        th.methodRecordForKey(key)?.let { rec ->
            if (rec.effectiveWriteVisibility == Visibility.Public &&
                (rec.type == ObjRecord.Type.Property || rec.type == ObjRecord.Type.Delegated)) {
                th.writeField(scope, name, newValue)
                return
            }
        }

        th.writeField(scope, name, newValue)
    }

    private suspend fun writeDirectOrFallback(
        scope: Scope,
        inst: ObjInstance,
        rec: ObjRecord,
        name: String,
        newValue: Obj,
        decl: ObjClass?
    ) {
        if ((rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField) && !rec.isAbstract) {
            if (!rec.isMutable && rec.value !== ObjUnset) {
                ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
            }
            if (rec.value.assign(scope, newValue) == null) rec.value = newValue
        } else {
            inst.writeField(scope, name, newValue)
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
        val base = target.evalValue(scope)
        if (base == ObjNull && isOptional) return ObjNull.asMutable
        val idx = index.evalValue(scope)
        val picCounters = PerfFlags.PIC_DEBUG_COUNTERS
        if (PerfFlags.RVAL_FASTPATH) {
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
                val key: Long
                val ver: Int
                when (base) {
                    is ObjInstance -> { key = base.objClass.classId; ver = base.objClass.layoutVersion }
                    is ObjClass -> { key = base.classId; ver = base.layoutVersion }
                    else -> { key = 0L; ver = -1 }
                }
                if (key != 0L) {
                    rGetter1?.let { g -> if (key == rKey1 && ver == rVer1) {
                        if (picCounters) PerfStats.indexPicHit++
                        return g(base, scope, idx).asMutable
                    } }
                    rGetter2?.let { g -> if (key == rKey2 && ver == rVer2) {
                        if (picCounters) PerfStats.indexPicHit++
                        val tk = rKey2; val tv = rVer2; val tg = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx).asMutable
                    } }
                    if (PerfFlags.INDEX_PIC_SIZE_4) rGetter3?.let { g -> if (key == rKey3 && ver == rVer3) {
                        if (picCounters) PerfStats.indexPicHit++
                        val tk = rKey3; val tv = rVer3; val tg = rGetter3
                        rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx).asMutable
                    } }
                    if (PerfFlags.INDEX_PIC_SIZE_4) rGetter4?.let { g -> if (key == rKey4 && ver == rVer4) {
                        if (picCounters) PerfStats.indexPicHit++
                        val tk = rKey4; val tv = rVer4; val tg = rGetter4
                        rKey4 = rKey3; rVer4 = rVer3; rGetter4 = rGetter3
                        rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx).asMutable
                    } }
                    // Miss: resolve and install generic handler
                    if (picCounters) PerfStats.indexPicMiss++
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
        val base = target.evalValue(scope)
        if (base == ObjNull && isOptional) return ObjNull
        val idx = index.evalValue(scope)
        val picCounters = PerfFlags.PIC_DEBUG_COUNTERS
        if (PerfFlags.RVAL_FASTPATH) {
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
                val key: Long
                val ver: Int
                when (base) {
                    is ObjInstance -> { key = base.objClass.classId; ver = base.objClass.layoutVersion }
                    is ObjClass -> { key = base.classId; ver = base.layoutVersion }
                    else -> { key = 0L; ver = -1 }
                }
                if (key != 0L) {
                    rGetter1?.let { g -> if (key == rKey1 && ver == rVer1) {
                        if (picCounters) PerfStats.indexPicHit++
                        return g(base, scope, idx)
                    } }
                    rGetter2?.let { g -> if (key == rKey2 && ver == rVer2) {
                        if (picCounters) PerfStats.indexPicHit++
                        val tk = rKey2; val tv = rVer2; val tg = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx)
                    } }
                    if (PerfFlags.INDEX_PIC_SIZE_4) rGetter3?.let { g -> if (key == rKey3 && ver == rVer3) {
                        if (picCounters) PerfStats.indexPicHit++
                        val tk = rKey3; val tv = rVer3; val tg = rGetter3
                        rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx)
                    } }
                    if (PerfFlags.INDEX_PIC_SIZE_4) rGetter4?.let { g -> if (key == rKey4 && ver == rVer4) {
                        if (picCounters) PerfStats.indexPicHit++
                        val tk = rKey4; val tv = rVer4; val tg = rGetter4
                        rKey4 = rKey3; rVer4 = rVer3; rGetter4 = rGetter3
                        rKey3 = rKey2; rVer3 = rVer2; rGetter3 = rGetter2
                        rKey2 = rKey1; rVer2 = rVer1; rGetter2 = rGetter1
                        rKey1 = tk; rVer1 = tv; rGetter1 = tg
                        return g(base, scope, idx)
                    } }
                    if (picCounters) PerfStats.indexPicMiss++
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
        val base = target.evalValue(scope)
        if (base == ObjNull && isOptional) {
            // no-op on null receiver for optional chaining assignment
            return
        }
        val idx = index.evalValue(scope)
        
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
        if (PerfFlags.RVAL_FASTPATH && PerfFlags.INDEX_PIC) {
            // Polymorphic inline cache for index write
            val key: Long
            val ver: Int
            when (base) {
                is ObjInstance -> { key = base.objClass.classId; ver = base.objClass.layoutVersion }
                is ObjClass -> { key = base.classId; ver = base.layoutVersion }
                else -> { key = 0L; ver = -1 }
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
        base.putAt(scope, idx, newValue)
    }
}

/**
 * R-value reference that wraps a Statement (used during migration for expressions parsed as Statement).
 */
class StatementRef(internal val statement: Statement) : ObjRef {
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
        val usePool = PerfFlags.SCOPE_POOL
        val callee = target.evalValue(scope)
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

    override suspend fun get(scope: Scope): ObjRecord {
        val methodPic = PerfFlags.METHOD_PIC
        val picCounters = PerfFlags.PIC_DEBUG_COUNTERS
        val base = receiver.evalValue(scope)
        if (base == ObjNull && isOptional) return ObjNull.asReadonly
        val callArgs = args.toArguments(scope, tailBlock)
        return performInvoke(scope, base, callArgs, methodPic, picCounters).asReadonly
    }

    override suspend fun evalValue(scope: Scope): Obj {
        val methodPic = PerfFlags.METHOD_PIC
        val picCounters = PerfFlags.PIC_DEBUG_COUNTERS
        val base = receiver.evalValue(scope)
        if (base == ObjNull && isOptional) return ObjNull
        val callArgs = args.toArguments(scope, tailBlock)
        return performInvoke(scope, base, callArgs, methodPic, picCounters)
    }

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
    private val args: List<ParsedArgument>,
    private val tailBlock: Boolean,
    private val isOptional: Boolean
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = evalValue(scope).asReadonly

    override suspend fun evalValue(scope: Scope): Obj {
        val base = scope.thisObj
        if (base == ObjNull && isOptional) return ObjNull
        val callArgs = args.toArguments(scope, tailBlock)
        if (base !is ObjInstance) return base.invokeInstanceMethod(scope, name, callArgs)

        val caller = scope.currentClassCtx
        if (caller != null) {
            val mangled = caller.mangledName(name)
            base.methodRecordForKey(mangled)?.let { rec ->
                if (rec.visibility == Visibility.Private && !rec.isAbstract) {
                    if (rec.type == ObjRecord.Type.Property) {
                        if (callArgs.isEmpty()) return (rec.value as ObjProperty).callGetter(scope, base, caller)
                    } else if (rec.type == ObjRecord.Type.Fun) {
                        return rec.value.invoke(base.instanceScope, base, callArgs, caller)
                    }
                }
            }
        }

        val key = base.objClass.publicMemberResolution[name] ?: name
        base.methodRecordForKey(key)?.let { rec ->
            if (!rec.isAbstract) {
                val decl = rec.declaringClass ?: base.objClass.findDeclaringClassOf(name) ?: base.objClass
                val effectiveCaller = caller ?: if (scope.thisObj === base) base.objClass else null
                if (!canAccessMember(rec.visibility, decl, effectiveCaller, name))
                    scope.raiseError(ObjIllegalAccessException(scope, "can't invoke method $name (declared in ${decl.className})"))
                if (rec.type == ObjRecord.Type.Property) {
                    if (callArgs.isEmpty()) return (rec.value as ObjProperty).callGetter(scope, base, decl)
                } else if (rec.type == ObjRecord.Type.Fun) {
                    return rec.value.invoke(base.instanceScope, base, callArgs, decl)
                }
            }
        }

        return base.invokeInstanceMethod(scope, name, callArgs)
    }
}

/**
 * Reference to a local/visible variable by name (Phase A: scope lookup).
 */
class LocalVarRef(val name: String, private val atPos: Pos) : ObjRef {
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

    override suspend fun get(scope: Scope): ObjRecord {
        scope.pos = atPos
        if (!PerfFlags.LOCAL_SLOT_PIC) {
            scope.getSlotIndexOf(name)?.let {
                if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.localVarPicHit++
                return scope.getSlotRecord(it)
            }
            if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.localVarPicMiss++
            // 2) Fallback to current-scope object or field on `this`
            scope[name]?.let { return it }
            try {
                return scope.thisObj.readField(scope, name)
            } catch (e: ExecutionError) {
                // Map missing symbol during unqualified lookup to SymbolNotFound (SymbolNotDefinedException)
                // to preserve legacy behavior expected by tests.
                if ((e.message ?: "").contains("no such field: $name")) scope.raiseSymbolNotFound(name)
                throw e
            }
        }
        val hit = (cachedFrameId == scope.frameId && cachedSlot >= 0 && cachedSlot < scope.slotCount())
        val slot = if (hit) cachedSlot else resolveSlot(scope)
        if (slot >= 0) {
            val rec = scope.getSlotRecord(slot)
            if (rec.declaringClass != null && !canAccessMember(rec.visibility, rec.declaringClass, scope.currentClassCtx, name)) {
                // Not visible via slot, fallback to other lookups
            } else {
                if (PerfFlags.PIC_DEBUG_COUNTERS) {
                    if (hit) PerfStats.localVarPicHit++ else PerfStats.localVarPicMiss++
                }
                return rec
            }
        }
        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.localVarPicMiss++
        // 2) Fallback name in scope or field on `this`
        scope[name]?.let { return it }
        try {
            return scope.thisObj.readField(scope, name)
        } catch (e: ExecutionError) {
            if ((e.message ?: "").contains("no such field: $name")) scope.raiseSymbolNotFound(name)
            throw e
        }
    }

    override suspend fun evalValue(scope: Scope): Obj {
        scope.pos = atPos
        scope.getSlotIndexOf(name)?.let { return scope.resolve(scope.getSlotRecord(it), name) }
        // fallback to current-scope object or field on `this`
        scope[name]?.let { return scope.resolve(it, name) }
        return try {
            scope.thisObj.readField(scope, name).value
        } catch (e: ExecutionError) {
            if ((e.message ?: "").contains("no such field: $name")) scope.raiseSymbolNotFound(name)
            throw e
        }
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        scope.pos = atPos
        if (!PerfFlags.LOCAL_SLOT_PIC) {
            scope.getSlotIndexOf(name)?.let {
                val rec = scope.getSlotRecord(it)
                scope.assign(rec, name, newValue)
                return
            }
            scope[name]?.let { stored ->
                scope.assign(stored, name, newValue)
                return
            }
            // Fallback: write to field on `this`
            scope.thisObj.writeField(scope, name, newValue)
            return
        }
        val slot = if (cachedFrameId == scope.frameId && cachedSlot >= 0 && cachedSlot < scope.slotCount()) cachedSlot else resolveSlot(scope)
        if (slot >= 0) {
            val rec = scope.getSlotRecord(slot)
            if (rec.declaringClass == null || canAccessMember(rec.visibility, rec.declaringClass, scope.currentClassCtx, name)) {
                scope.assign(rec, name, newValue)
                return
            }
        }
        scope[name]?.let { stored ->
            scope.assign(stored, name, newValue)
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
        val rec = scope.getSlotRecord(slot)
        if (rec.declaringClass != null && !canAccessMember(rec.visibility, rec.declaringClass, scope.currentClassCtx))
            scope.raiseError(ObjIllegalAccessException(scope, "private field access"))
        return rec
    }

    override suspend fun evalValue(scope: Scope): Obj {
        scope.pos = atPos
        val rec = scope.getSlotRecord(slot)
        if (rec.declaringClass != null && !canAccessMember(rec.visibility, rec.declaringClass, scope.currentClassCtx))
            scope.raiseError(ObjIllegalAccessException(scope, "private field access"))
        // We might not have the name in BoundLocalVarRef, but let's try to find it or use a placeholder
        // Actually BoundLocalVarRef is mostly used for parameters which are not delegated yet.
        // But for consistency:
        return scope.resolve(rec, "local")
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        scope.pos = atPos
        val rec = scope.getSlotRecord(slot)
        if (rec.declaringClass != null && !canAccessMember(rec.visibility, rec.declaringClass, scope.currentClassCtx))
            scope.raiseError(ObjIllegalAccessException(scope, "private field access"))
        scope.assign(rec, "local", newValue)
    }
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

    override suspend fun get(scope: Scope): ObjRecord {
        scope.pos = atPos
        val ownerValid = isOwnerValidFor(scope)
        val slot = if (ownerValid && cachedSlot >= 0) cachedSlot else resolveSlotInAncestry(scope)
        val actualOwner = cachedOwnerScope
        if (slot >= 0 && actualOwner != null) {
            val rec = actualOwner.getSlotRecord(slot)
            if (rec.declaringClass == null || canAccessMember(rec.visibility, rec.declaringClass, scope.currentClassCtx, name)) {
                if (PerfFlags.PIC_DEBUG_COUNTERS) {
                    if (ownerValid) PerfStats.fastLocalHit++ else PerfStats.fastLocalMiss++
                }
                return rec
            }
        }
        // Try per-frame local binding maps in the ancestry first (locals declared in frames)
        run {
            var s: Scope? = scope
            var guard = 0
            while (s != null) {
                s.localBindings[name]?.let { return it }
                val next = s.parent
                if (next === s) break
                s = next
                if (++guard > 4096) break
            }
        }
        // Try to find a direct local binding in the current ancestry (without invoking name resolution that may prefer fields)
        run {
            var s: Scope? = scope
            var guard = 0
            while (s != null) {
                s.objects[name]?.let { return it }
                val next = s.parent
                if (next === s) break
                s = next
                if (++guard > 4096) break
            }
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
        if (slot >= 0 && actualOwner != null) {
            val rec = actualOwner.getSlotRecord(slot)
            if (rec.declaringClass == null || canAccessMember(rec.visibility, rec.declaringClass, scope.currentClassCtx, name)) {
                return scope.resolve(rec, name)
            }
        }
        // Try per-frame local binding maps in the ancestry first
        run {
            var s: Scope? = scope
            var guard = 0
            while (s != null) {
                s.localBindings[name]?.let { 
                    return s.resolve(it, name) 
                }
                val next = s.parent
                if (next === s) break
                s = next
                if (++guard > 4096) break
            }
        }
        // Try to find a direct local binding in the current ancestry first
        run {
            var s: Scope? = scope
            var guard = 0
            while (s != null) {
                s.objects[name]?.let { 
                    return s.resolve(it, name) 
                }
                val next = s.parent
                if (next === s) break
                s = next
                if (++guard > 4096) break
            }
        }
        // Fallback to standard name lookup (locals or closure chain)
        scope[name]?.let { 
            return scope.resolve(it, name) 
        }
        return scope.thisObj.readField(scope, name).value
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        scope.pos = atPos
        val owner = if (isOwnerValidFor(scope)) cachedOwnerScope else null
        val slot = if (owner != null && cachedSlot >= 0) cachedSlot else resolveSlotInAncestry(scope)
        val actualOwner = cachedOwnerScope
        if (slot >= 0 && actualOwner != null) {
            val rec = actualOwner.getSlotRecord(slot)
            if (rec.declaringClass == null || canAccessMember(rec.visibility, rec.declaringClass, scope.currentClassCtx, name)) {
                scope.assign(rec, name, newValue)
                return
            }
        }
        // Try per-frame local binding maps in the ancestry first
        run {
            var s: Scope? = scope
            var guard = 0
            while (s != null) {
                val rec = s.localBindings[name]
                if (rec != null) {
                    s.assign(rec, name, newValue)
                    return
                }
                val next = s.parent
                if (next === s) break
                s = next
                if (++guard > 4096) break
            }
        }
        // Fallback to standard name lookup
        scope[name]?.let { stored ->
            scope.assign(stored, name, newValue)
            return
        }
        scope.thisObj.writeField(scope, name, newValue)
        return
    }
}

/**
 * Identifier reference in class context that prefers member slots on `this` after local lookup.
 * Falls back to normal scope lookup for globals/outer scopes.
 */
class ImplicitThisMemberRef(
    val name: String,
    val atPos: Pos
) : ObjRef {
    private fun resolveInstanceFieldRecord(th: ObjInstance, caller: ObjClass?): ObjRecord? {
        if (caller == null) return null
        for (cls in th.objClass.mro) {
            if (cls.className == "Obj") break
            val rec = cls.members[name] ?: continue
            if (rec.isAbstract) continue
            val decl = rec.declaringClass ?: cls
            if (!canAccessMember(rec.visibility, decl, caller, name)) continue
            val key = decl.mangledName(name)
            th.fieldRecordForKey(key)?.let { return it }
            th.instanceScope.objects[key]?.let { return it }
        }
        return null
    }

    override fun forEachVariable(block: (String) -> Unit) {
        block(name)
    }

    override fun forEachVariableWithPos(block: (String, Pos) -> Unit) {
        block(name, atPos)
    }

    override suspend fun get(scope: Scope): ObjRecord {
        scope.pos = atPos
        val caller = scope.currentClassCtx
        val th = scope.thisObj

        // 1) locals in the same `this` chain
        var s: Scope? = scope
        while (s != null && s.thisObj === th) {
            scope.tryGetLocalRecord(s, name, caller)?.let { return it }
            s = s.parent
        }

        // 2) member slots on this instance
        if (th is ObjInstance) {
            // private member access for current class context
            caller?.let { c ->
                val mangled = c.mangledName(name)
                th.fieldRecordForKey(mangled)?.let { rec ->
                    if (rec.visibility == Visibility.Private) {
                        return th.resolveRecord(scope, rec, name, c)
                    }
                }
                th.methodRecordForKey(mangled)?.let { rec ->
                    if (rec.visibility == Visibility.Private) {
                        return th.resolveRecord(scope, rec, name, c)
                    }
                }
            }

            resolveInstanceFieldRecord(th, caller)?.let { return it }

            val key = th.objClass.publicMemberResolution[name] ?: name
            th.fieldRecordForKey(key)?.let { rec ->
                if ((rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField) && !rec.isAbstract)
                    return rec
            }
            th.methodRecordForKey(key)?.let { rec ->
                if (!rec.isAbstract) {
                    val decl = rec.declaringClass ?: th.objClass.findDeclaringClassOf(name) ?: th.objClass
                    return th.resolveRecord(scope, rec, name, decl)
                }
            }
        }

        // 3) fallback to normal scope resolution (globals/outer scopes)
        scope[name]?.let { return it }
        try {
            return th.readField(scope, name)
        } catch (e: ExecutionError) {
            if ((e.message ?: "").contains("no such field: $name")) scope.raiseSymbolNotFound(name)
            throw e
        }
    }

    override suspend fun evalValue(scope: Scope): Obj {
        val rec = get(scope)
        return scope.resolve(rec, name)
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        scope.pos = atPos
        val caller = scope.currentClassCtx
        val th = scope.thisObj

        // 1) locals in the same `this` chain
        var s: Scope? = scope
        while (s != null && s.thisObj === th) {
            val rec = scope.tryGetLocalRecord(s, name, caller)
            if (rec != null) {
                scope.assign(rec, name, newValue)
                return
            }
            s = s.parent
        }

        // 2) member slots on this instance
        if (th is ObjInstance) {
            val key = th.objClass.publicMemberResolution[name] ?: name
            th.fieldRecordForKey(key)?.let { rec ->
                val decl = rec.declaringClass ?: th.objClass.findDeclaringClassOf(name)
                if (canAccessMember(rec.effectiveWriteVisibility, decl, caller, name)) {
                    if ((rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField) && !rec.isAbstract) {
                        if (!rec.isMutable && rec.value !== ObjUnset) {
                            ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
                        }
                        if (rec.value.assign(scope, newValue) == null) rec.value = newValue
                    } else {
                        th.writeField(scope, name, newValue)
                    }
                    return
                }
            }
            th.methodRecordForKey(key)?.let { rec ->
                if (rec.effectiveWriteVisibility == Visibility.Public &&
                    (rec.type == ObjRecord.Type.Property || rec.type == ObjRecord.Type.Delegated)) {
                    th.writeField(scope, name, newValue)
                    return
                }
            }

            resolveInstanceFieldRecord(th, caller)?.let { rec ->
                scope.assign(rec, name, newValue)
                return
            }
        }

        // 3) fallback to normal scope resolution
        scope[name]?.let { stored ->
            scope.assign(stored, name, newValue)
            return
        }
        th.writeField(scope, name, newValue)
    }
}

/**
 * Fast path for implicit member calls in class bodies: `foo(...)` resolves locals first,
 * then falls back to member lookup on `this`.
 */
class ImplicitThisMethodCallRef(
    private val name: String,
    private val args: List<ParsedArgument>,
    private val tailBlock: Boolean,
    private val isOptional: Boolean,
    private val atPos: Pos
) : ObjRef {
    private val memberRef = ImplicitThisMemberRef(name, atPos)

    override suspend fun get(scope: Scope): ObjRecord = evalValue(scope).asReadonly

    override suspend fun evalValue(scope: Scope): Obj {
        scope.pos = atPos
        val callee = memberRef.evalValue(scope)
        if (callee == ObjNull && isOptional) return ObjNull
        val callArgs = args.toArguments(scope, tailBlock)
        val usePool = PerfFlags.SCOPE_POOL
        return if (usePool) {
            scope.withChildFrame(callArgs) { child ->
                callee.callOn(child)
            }
        } else {
            callee.callOn(scope.createChildScope(scope.pos, callArgs))
        }
    }
}

/**
 * Direct local slot reference with known slot index and lexical depth.
 * Depth=0 means current scope, depth=1 means parent scope, etc.
 */
class LocalSlotRef(
    val name: String,
    internal val slot: Int,
    internal val depth: Int,
    private val atPos: Pos,
) : ObjRef {
    override fun forEachVariable(block: (String) -> Unit) {
        block(name)
    }

    private val fallbackRef = LocalVarRef(name, atPos)
    private var cachedFrameId: Long = 0L
    private var cachedOwner: Scope? = null
    private var cachedOwnerVerified: Boolean = false

    private fun resolveOwner(scope: Scope): Scope? {
        if (cachedOwner != null && cachedFrameId == scope.frameId && cachedOwnerVerified) {
            val cached = cachedOwner!!
            val candidate = if (depth == 0) scope else {
                var s: Scope? = scope
                var remaining = depth
                while (s != null && remaining > 0) {
                    s = s.parent
                    remaining--
                }
                s
            }
            if (candidate === cached && candidate?.getSlotIndexOf(name) == slot) return cached
        }
        var s: Scope? = scope
        var remaining = depth
        while (s != null && remaining > 0) {
            s = s.parent
            remaining--
        }
        if (s == null || s.getSlotIndexOf(name) != slot) {
            cachedOwner = null
            cachedOwnerVerified = false
            cachedFrameId = scope.frameId
            return null
        }
        cachedOwner = s
        cachedOwnerVerified = true
        cachedFrameId = scope.frameId
        return s
    }

    override suspend fun get(scope: Scope): ObjRecord {
        scope.pos = atPos
        val owner = resolveOwner(scope) ?: return fallbackRef.get(scope)
        if (slot < 0 || slot >= owner.slotCount()) return fallbackRef.get(scope)
        val rec = owner.getSlotRecord(slot)
        if (rec.declaringClass != null && !canAccessMember(rec.visibility, rec.declaringClass, scope.currentClassCtx, name)) {
            scope.raiseError(ObjIllegalAccessException(scope, "private field access"))
        }
        return rec
    }

    override suspend fun evalValue(scope: Scope): Obj {
        scope.pos = atPos
        val owner = resolveOwner(scope) ?: return fallbackRef.evalValue(scope)
        if (slot < 0 || slot >= owner.slotCount()) return fallbackRef.evalValue(scope)
        val rec = owner.getSlotRecord(slot)
        if (rec.declaringClass != null && !canAccessMember(rec.visibility, rec.declaringClass, scope.currentClassCtx, name)) {
            scope.raiseError(ObjIllegalAccessException(scope, "private field access"))
        }
        return scope.resolve(rec, name)
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        scope.pos = atPos
        val owner = resolveOwner(scope) ?: run {
            fallbackRef.setAt(pos, scope, newValue)
            return
        }
        if (slot < 0 || slot >= owner.slotCount()) {
            fallbackRef.setAt(pos, scope, newValue)
            return
        }
        val rec = owner.getSlotRecord(slot)
        if (rec.declaringClass != null && !canAccessMember(rec.visibility, rec.declaringClass, scope.currentClassCtx, name)) {
            scope.raiseError(ObjIllegalAccessException(scope, "private field access"))
        }
        scope.assign(rec, name, newValue)
    }
}

class ListLiteralRef(private val entries: List<ListEntry>) : ObjRef {
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

    override suspend fun get(scope: Scope): ObjRecord {
        return evalValue(scope).asMutable
    }

    override suspend fun evalValue(scope: Scope): Obj {
        // Heuristic capacity hint: count element entries; spreads handled opportunistically
        val elemCount = entries.count { it is ListEntry.Element }
        val list = ArrayList<Obj>(elemCount)
        for (e in entries) {
            when (e) {
                is ListEntry.Element -> {
                    list += e.ref.evalValue(scope)
                }
                is ListEntry.Spread -> {
                    val elements = e.ref.evalValue(scope)
                    when (elements) {
                        is ObjList -> {
                            // Grow underlying array once when possible
                            list.ensureCapacity(list.size + elements.list.size)
                            list.addAll(elements.list)
                        }
                        else -> scope.raiseError("Spread element must be list")
                    }
                }
            }
        }
        return ObjList(list)
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        val sourceList = (newValue as? ObjList)?.list
            ?: throw ScriptError(pos, "destructuring assignment requires a list on the right side")

        val ellipsisIdx = entries.indexOfFirst { it is ListEntry.Spread }
        if (entries.count { it is ListEntry.Spread } > 1) {
            throw ScriptError(pos, "destructuring pattern can have only one splat")
        }

        if (ellipsisIdx < 0) {
            if (sourceList.size < entries.size)
                throw ScriptError(pos, "too few elements for destructuring")
            for (i in entries.indices) {
                val entry = entries[i]
                if (entry is ListEntry.Element) {
                    entry.ref.setAt(pos, scope, sourceList[i])
                }
            }
        } else {
            val headCount = ellipsisIdx
            val tailCount = entries.size - ellipsisIdx - 1
            if (sourceList.size < headCount + tailCount)
                throw ScriptError(pos, "too few elements for destructuring")

            // head
            for (i in 0 until headCount) {
                val entry = entries[i]
                if (entry is ListEntry.Element) {
                    entry.ref.setAt(pos, scope, sourceList[i])
                }
            }

            // tail
            for (i in 0 until tailCount) {
                val entry = entries[entries.size - 1 - i]
                if (entry is ListEntry.Element) {
                    entry.ref.setAt(pos, scope, sourceList[sourceList.size - 1 - i])
                }
            }

            // ellipsis
            val spreadEntry = entries[ellipsisIdx] as ListEntry.Spread
            val spreadList = sourceList.subList(headCount, sourceList.size - tailCount)
            spreadEntry.ref.setAt(pos, scope, ObjList(spreadList.toMutableList()))
        }
    }
}

// --- Map literal support ---

sealed class MapLiteralEntry {
    data class Named(val key: String, val value: ObjRef) : MapLiteralEntry()
    data class Spread(val ref: ObjRef) : MapLiteralEntry()
}

class MapLiteralRef(private val entries: List<MapLiteralEntry>) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        return evalValue(scope).asReadonly
    }

    override suspend fun evalValue(scope: Scope): Obj {
        val result = ObjMap(mutableMapOf())
        for (e in entries) {
            when (e) {
                is MapLiteralEntry.Named -> {
                    val v = e.value.evalValue(scope)
                    result.map[ObjString(e.key)] = v
                }
                is MapLiteralEntry.Spread -> {
                    val m = e.ref.evalValue(scope)
                    if (m !is ObjMap) scope.raiseIllegalArgument("spread element in map literal must be a Map")
                    for ((k, v) in m.map) {
                        result.map[k] = v
                    }
                }
            }
        }
        return result
    }
}

/**
 * Range literal: left .. right or left ..< right. Right may be omitted in certain contexts.
 */
class RangeRef(
    internal val left: ObjRef?,
    internal val right: ObjRef?,
    internal val isEndInclusive: Boolean
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        return evalValue(scope).asReadonly
    }

    override suspend fun evalValue(scope: Scope): Obj {
        val l = left?.evalValue(scope) ?: ObjNull
        val r = right?.evalValue(scope) ?: ObjNull
        return ObjRange(l, r, isEndInclusive = isEndInclusive)
    }
}

/** Assignment if null op: target ?= value */
class AssignIfNullRef(
    private val target: ObjRef,
    private val value: ObjRef,
    private val atPos: Pos,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        return evalValue(scope).asReadonly
    }

    override suspend fun evalValue(scope: Scope): Obj {
        val current = target.evalValue(scope)
        if (current != ObjNull) return current
        val newValue = value.evalValue(scope)
        target.setAt(atPos, scope, newValue)
        return newValue
    }
}

/** Simple assignment: target = value */
class AssignRef(
    internal val target: ObjRef,
    internal val value: ObjRef,
    private val atPos: Pos,
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        return evalValue(scope).asReadonly
    }

    override suspend fun evalValue(scope: Scope): Obj {
        val v = value.evalValue(scope)
        // For properties, we should not call get() on target because it invokes the getter.
        // Instead, we call setAt directly.
        if (target is FieldRef ||
            target is IndexRef ||
            target is LocalVarRef ||
            target is FastLocalVarRef ||
            target is BoundLocalVarRef ||
            target is LocalSlotRef ||
            target is ThisFieldSlotRef ||
            target is QualifiedThisFieldSlotRef ||
            target is ImplicitThisMemberRef) {
             target.setAt(atPos, scope, v)
        } else {
            val rec = target.get(scope)
            if (!rec.isMutable) throw ScriptError(atPos, "cannot assign to immutable variable")
            if (rec.value.assign(scope, v) == null) {
                target.setAt(atPos, scope, v)
            }
        }
        return v
    }
}

    // (duplicate LocalVarRef removed; the canonical implementation is defined earlier in this file)
