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
        val v = a.get(scope).value
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
        val a = left.get(scope).value
        val b = right.get(scope).value
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
        val y = value.get(scope).value
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
        val a = left.get(scope).value
        val r = if (a != ObjNull) a else right.get(scope).value
        return r.asReadonly
    }
}

/** Logical OR with short-circuit: a || b */
class LogicalOrRef(private val left: ObjRef, private val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val a = left.get(scope).value
        if ((a as? ObjBool)?.value == true) return ObjTrue.asReadonly
        val b = right.get(scope).value
        return a.logicalOr(scope, b).asReadonly
    }
}

/** Logical AND with short-circuit: a && b */
class LogicalAndRef(private val left: ObjRef, private val right: ObjRef) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord {
        val a = left.get(scope).value
        if ((a as? ObjBool)?.value == false) return ObjFalse.asReadonly
        val b = right.get(scope).value
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
    override suspend fun get(scope: Scope): ObjRecord {
        val base = target.get(scope).value
        return if (base == ObjNull && isOptional) ObjNull.asMutable else base.readField(scope, name)
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        val base = target.get(scope).value
        if (base == ObjNull && isOptional) {
            // no-op on null receiver for optional chaining assignment
            return
        }
        base.writeField(scope, name, newValue)
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
        val base = target.get(scope).value
        if (base == ObjNull && isOptional) return ObjNull.asMutable
        val idx = index.get(scope).value
        return base.getAt(scope, idx).asMutable
    }

    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        val base = target.get(scope).value
        if (base == ObjNull && isOptional) {
            // no-op on null receiver for optional chaining assignment
            return
        }
        val idx = index.get(scope).value
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
        val callee = target.get(scope).value
        if (callee == ObjNull && isOptionalInvoke) return ObjNull.asReadonly
        val callArgs = args.toArguments(scope, tailBlock)
        val result = callee.callOn(scope.createChildScope(scope.pos, callArgs))
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
    override suspend fun get(scope: Scope): ObjRecord {
        val base = receiver.get(scope).value
        if (base == ObjNull && isOptional) return ObjNull.asReadonly
        val callArgs = args.toArguments(scope, tailBlock)
        val result = base.invokeInstanceMethod(scope, name, callArgs)
        return result.asReadonly
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
            scope.getSlotIndexOf(name)?.let { return scope.getSlotRecord(it) }
            return scope[name] ?: scope.raiseError("symbol not defined: '$name'")
        }
        val slot = if (cachedFrameId == scope.frameId && cachedSlot >= 0 && cachedSlot < scope.slotCount()) cachedSlot else resolveSlot(scope)
        if (slot >= 0) return scope.getSlotRecord(slot)
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
        val owner = if (isOwnerValidFor(scope)) cachedOwnerScope else null
        val slot = if (owner != null && cachedSlot >= 0) cachedSlot else resolveSlotInAncestry(scope)
        val actualOwner = cachedOwnerScope
        if (slot < 0 || actualOwner == null) scope.raiseError("local '$name' is not available in this scope")
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
        val list = mutableListOf<Obj>()
        for (e in entries) {
            when (e) {
                is ListEntry.Element -> {
                    list += e.ref.get(scope).value
                }
                is ListEntry.Spread -> {
                    val elements = e.ref.get(scope).value
                    when (elements) {
                        is ObjList -> list.addAll(elements.list)
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
        val l = left?.get(scope)?.value ?: ObjNull
        val r = right?.get(scope)?.value ?: ObjNull
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
        val v = value.get(scope).value
        val rec = target.get(scope)
        if (!rec.isMutable) throw ScriptError(atPos, "cannot assign to immutable variable")
        if (rec.value.assign(scope, v) == null) {
            target.setAt(atPos, scope, v)
        }
        return v.asReadonly
    }
}
