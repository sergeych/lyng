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

package net.sergeych.lyng.bytecode

import net.sergeych.lyng.*
import net.sergeych.lyng.obj.*

class CmdVm {
    var result: Obj? = null

    suspend fun execute(
        fn: CmdFunction,
        scope0: Scope,
        args: Arguments,
        binder: (suspend (CmdFrame, Arguments) -> Unit)? = null
    ): Obj {
        result = null
        val frame = CmdFrame(this, fn, scope0, args.list)
        frame.applyCaptureRecords()
        binder?.invoke(frame, args)
        val cmds = fn.cmds
        while (true) {
            try {
                while (result == null) {
                    val cmd = cmds[frame.ip++]
                    if (!cmd.performFast(frame))
                        cmd.perform(frame)
                }
                break
            } catch (e: Throwable) {
                val throwable = frame.normalizeThrowable(e)
                if (!frame.handleException(throwable)) {
                    frame.cancelIterators()
                    throw throwable
                }
            }
        }
        frame.cancelIterators()
        return result ?: ObjVoid
    }

    suspend fun execute(fn: CmdFunction, scope0: Scope, args: List<Obj>): Obj {
        return execute(fn, scope0, Arguments.from(args))
    }
}

sealed class Cmd {
    open fun performFast(frame: CmdFrame): Boolean = false

    open suspend fun perform(frame: CmdFrame) {
        error("slow command not supported: ${this::class.simpleName}")
    }
}

class CmdNop : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        return
    }
}

class CmdMoveObj(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val value = frame.slotToObj(src)
        if (frame.writeThroughPropertyLikeSlot(dst, value)) {
            return
        }
        if (frame.shouldBypassImmutableWrite(dst)) {
            frame.setObjUnchecked(dst, value)
        } else {
            frame.setObj(dst, value)
        }
        return
    }
}

class CmdMoveInt(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val value = frame.getInt(src)
        if (frame.writeThroughPropertyLikeSlot(dst, ObjInt.of(value))) {
            return
        }
        if (frame.shouldBypassImmutableWrite(dst)) {
            frame.setIntUnchecked(dst, value)
        } else {
            frame.setInt(dst, value)
        }
        return
    }
}

class CmdMoveIntLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(src))
        return true
    }
}

class CmdMoveReal(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val value = frame.getReal(src)
        if (frame.writeThroughPropertyLikeSlot(dst, ObjReal.of(value))) {
            return
        }
        if (frame.shouldBypassImmutableWrite(dst)) {
            frame.setRealUnchecked(dst, value)
        } else {
            frame.setReal(dst, value)
        }
        return
    }
}

class CmdMoveRealLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalReal(dst, frame.getLocalReal(src))
        return true
    }
}

class CmdMoveBool(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val value = frame.getBool(src)
        if (frame.writeThroughPropertyLikeSlot(dst, if (value) ObjTrue else ObjFalse)) {
            return
        }
        if (frame.shouldBypassImmutableWrite(dst)) {
            frame.setBoolUnchecked(dst, value)
        } else {
            frame.setBool(dst, value)
        }
        return
    }
}

class CmdMoveBoolLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalBool(src))
        return true
    }
}

class CmdConstObj(internal val constId: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        when (val c = frame.fn.constants[constId]) {
            is BytecodeConst.ObjRef -> {
                val obj = c.value
                when (obj) {
                    is ObjInt -> frame.setInt(dst, obj.value)
                    is ObjReal -> frame.setReal(dst, obj.value)
                    is ObjBool -> frame.setBool(dst, obj.value)
                    else -> frame.setObj(dst, obj)
                }
            }

            is BytecodeConst.StringVal -> frame.setObj(dst, ObjString(c.value))
            else -> error("CONST_OBJ expects ObjRef/StringVal at $constId")
        }
        return true
    }
}

class CmdConstInt(internal val constId: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val c = frame.fn.constants[constId] as? BytecodeConst.IntVal
            ?: error("CONST_INT expects IntVal at $constId")
        frame.setInt(dst, c.value)
        return true
    }
}

class CmdConstIntLocal(internal val constId: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val c = frame.fn.constants[constId] as? BytecodeConst.IntVal
            ?: error("CONST_INT expects IntVal at $constId")
        frame.setLocalInt(dst, c.value)
        return true
    }
}

class CmdConstReal(internal val constId: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val c = frame.fn.constants[constId] as? BytecodeConst.RealVal
            ?: error("CONST_REAL expects RealVal at $constId")
        frame.setReal(dst, c.value)
        return true
    }
}

class CmdConstBool(internal val constId: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val c = frame.fn.constants[constId] as? BytecodeConst.Bool
            ?: error("CONST_BOOL expects Bool at $constId")
        frame.setBool(dst, c.value)
        return true
    }
}

class CmdLoadThis(internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setObj(dst, frame.ensureScope().thisObj)
        return
    }
}

class CmdLoadThisVariant(
    internal val typeId: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val typeConst = frame.fn.constants.getOrNull(typeId) as? BytecodeConst.StringVal
            ?: error("LOAD_THIS_VARIANT expects StringVal at $typeId")
        val typeName = typeConst.value
        val scope = frame.ensureScope()
        if (scope.thisVariants.isEmpty() || scope.thisVariants.firstOrNull() !== scope.thisObj) {
            scope.setThisVariants(scope.thisObj, scope.thisVariants)
        }
        val receiver = scope.thisVariants.firstOrNull { it.isInstanceOf(typeName) }
            ?: run {
                if (scope.thisObj.isInstanceOf(typeName)) return@run scope.thisObj
                val typeClass = scope[typeName]?.value as? net.sergeych.lyng.obj.ObjClass
                var s: Scope? = scope
                while (s != null) {
                    val candidate = s.thisObj
                    if (candidate.isInstanceOf(typeName)) return@run candidate
                    if (typeClass != null) {
                        val inst = candidate as? net.sergeych.lyng.obj.ObjInstance
                        if (inst != null && (inst.objClass === typeClass || inst.objClass.allParentsSet.contains(
                                typeClass
                            ))
                        ) {
                            return@run inst
                        }
                    }
                    s = s.parent
                }
                val variants = scope.thisVariants.joinToString { it.objClass.className }
                scope.raiseClassCastError("Cannot cast ${scope.thisObj.objClass.className} to $typeName (variants: $variants)")
            }
        frame.setObj(dst, receiver)
        return
    }
}

class CmdMakeRange(
    internal val startSlot: Int,
    internal val endSlot: Int,
    internal val inclusiveSlot: Int,
    internal val descendingSlot: Int,
    internal val stepSlot: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val start = frame.slotToObj(startSlot)
        val end = frame.slotToObj(endSlot)
        val inclusive = frame.slotToObj(inclusiveSlot).toBool()
        val descending = frame.slotToObj(descendingSlot).toBool()
        val stepObj = frame.slotToObj(stepSlot)
        val step = if (stepObj.isNull) null else stepObj
        frame.storeObjResult(
            dst,
            ObjRange(start, end, isEndInclusive = inclusive, isDescending = descending, step = step)
        )
        return
    }
}

class CmdConstNull(internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setObj(dst, ObjNull)
        return
    }
}

class CmdBoxObj(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setObj(dst, frame.slotToObj(src))
        return
    }
}

class CmdUnboxIntObj(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val value = frame.slotToObj(src) as ObjInt
        frame.setInt(dst, value.value)
        return
    }
}

class CmdUnboxIntObjLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        when (frame.frame.getSlotTypeCode(src)) {
            SlotType.INT.code -> frame.setLocalInt(dst, frame.frame.getInt(src))
            else -> {
                val value = frame.frame.getRawObj(src) as ObjInt
                frame.setLocalInt(dst, value.value)
            }
        }
        return true
    }
}

class CmdUnboxRealObj(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val value = frame.slotToObj(src) as ObjReal
        frame.setReal(dst, value.value)
        return
    }
}

class CmdUnboxRealObjLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        when (frame.frame.getSlotTypeCode(src)) {
            SlotType.REAL.code -> frame.setLocalReal(dst, frame.frame.getReal(src))
            else -> {
                val value = frame.frame.getRawObj(src) as ObjReal
                frame.setLocalReal(dst, value.value)
            }
        }
        return true
    }
}

class CmdObjToBool(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(src).toBool())
        return
    }
}

class CmdGetObjClass(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val cls = frame.slotToObj(src).objClass
        frame.setObj(dst, cls)
        return
    }
}

class CmdCheckIs(internal val objSlot: Int, internal val typeSlot: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val obj = frame.slotToObj(objSlot)
        val typeObj = frame.slotToObj(typeSlot)
        val result = when {
            (obj is ObjTypeExpr || obj is ObjClass) && (typeObj is ObjTypeExpr || typeObj is ObjClass) -> {
                val leftDecl = typeDeclFromObj(frame.ensureScope(), obj) ?: return frame.setBool(dst, false)
                val rightDecl = typeDeclFromObj(frame.ensureScope(), typeObj) ?: return frame.setBool(dst, false)
                typeDeclIsSubtype(frame.ensureScope(), leftDecl, rightDecl)
            }

            typeObj is ObjTypeExpr -> matchesTypeDecl(frame.ensureScope(), obj, typeObj.typeDecl)
            typeObj is ObjClass -> obj.isInstanceOf(typeObj)
            else -> false
        }
        frame.setBool(dst, result)
        return
    }
}

class CmdAssertIs(internal val objSlot: Int, internal val typeSlot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val obj = frame.slotToObj(objSlot)
        val typeObj = frame.slotToObj(typeSlot)
        when (typeObj) {
            is ObjClass -> {
                if (!obj.isInstanceOf(typeObj)) {
                    frame.ensureScope().raiseClassCastError(
                        "Cannot cast ${obj.objClass.className} to ${typeObj.className}"
                    )
                }
            }

            is ObjTypeExpr -> {
                if (!matchesTypeDecl(frame.ensureScope(), obj, typeObj.typeDecl)) {
                    frame.ensureScope().raiseClassCastError(
                        "Cannot cast ${obj.objClass.className} to ${typeObj.typeDecl}"
                    )
                }
            }

            else -> frame.ensureScope().raiseClassCastError(
                "${typeObj.inspect(frame.ensureScope())} is not the class instance"
            )
        }
        return
    }
}

class CmdMakeQualifiedView(
    internal val objSlot: Int,
    internal val typeSlot: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val obj0 = frame.slotToObj(objSlot)
        val typeObj = frame.slotToObj(typeSlot)
        val base = when (obj0) {
            is ObjQualifiedView -> obj0.instance
            else -> obj0
        }
        val result = when (typeObj) {
            is ObjClass -> {
                if (base is ObjInstance && base.isInstanceOf(typeObj)) {
                    ObjQualifiedView(base, typeObj)
                } else {
                    base
                }
            }

            is ObjTypeExpr -> base
            else -> frame.ensureScope().raiseClassCastError(
                "${typeObj.inspect(frame.ensureScope())} is not the class instance"
            )
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdRangeIntBounds(
    internal val src: Int,
    internal val startSlot: Int,
    internal val endSlot: Int,
    internal val descendingSlot: Int,
    internal val okSlot: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val obj = frame.slotToObj(src)
        val range = obj as? ObjRange
        if (range == null || !range.isIntRange) {
            frame.setBool(okSlot, false)
            return
        }
        if (range.isDescending) {
            frame.setBool(okSlot, false)
            return
        }
        val start = (range.start as ObjInt).value
        val end = (range.end as ObjInt).value
        frame.setInt(startSlot, start)
        frame.setInt(
            endSlot, if (range.isDescending) {
                if (range.isEndInclusive) end - 1 else end
            } else {
                if (range.isEndInclusive) end + 1 else end
            }
        )
        frame.setBool(descendingSlot, range.isDescending)
        frame.setBool(okSlot, true)
        return
    }
}

class CmdResolveScopeSlot(internal val scopeSlot: Int, internal val addrSlot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.resolveScopeSlotAddr(scopeSlot, addrSlot)
        return
    }
}

class CmdLoadObjAddr(internal val addrSlot: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val value = frame.getAddrObj(addrSlot)
        if (frame.shouldBypassImmutableWrite(dst)) {
            frame.setObjUnchecked(dst, value)
        } else {
            frame.setObj(dst, value)
        }
        return
    }
}

class CmdStoreObjAddr(internal val src: Int, internal val addrSlot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setAddrObj(addrSlot, frame.slotToObj(src))
        return
    }
}

class CmdLoadIntAddr(internal val addrSlot: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val value = frame.getAddrInt(addrSlot)
        if (frame.shouldBypassImmutableWrite(dst)) {
            frame.setIntUnchecked(dst, value)
        } else {
            frame.setInt(dst, value)
        }
        return
    }
}

class CmdStoreIntAddr(internal val src: Int, internal val addrSlot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setAddrInt(addrSlot, frame.getInt(src))
        return
    }
}

class CmdLoadRealAddr(internal val addrSlot: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val value = frame.getAddrReal(addrSlot)
        if (frame.shouldBypassImmutableWrite(dst)) {
            frame.setRealUnchecked(dst, value)
        } else {
            frame.setReal(dst, value)
        }
        return
    }
}

class CmdStoreRealAddr(internal val src: Int, internal val addrSlot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setAddrReal(addrSlot, frame.getReal(src))
        return
    }
}

class CmdLoadBoolAddr(internal val addrSlot: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val value = frame.getAddrBool(addrSlot)
        if (frame.shouldBypassImmutableWrite(dst)) {
            frame.setBoolUnchecked(dst, value)
        } else {
            frame.setBool(dst, value)
        }
        return
    }
}

class CmdStoreBoolAddr(internal val src: Int, internal val addrSlot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setAddrBool(addrSlot, frame.getBool(src))
        return
    }
}

class CmdDelegatedGetLocal(
    internal val delegateSlot: Int,
    internal val nameId: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val nameConst = frame.fn.constants.getOrNull(nameId) as? BytecodeConst.StringVal
            ?: error("DELEGATED_GET_LOCAL expects StringVal at $nameId")
        val delegate = frame.slotToObj(delegateSlot)
        val scope = frame.ensureScope()
        val rec = ObjRecord(ObjNull, isMutable = false, type = ObjRecord.Type.Delegated)
        rec.delegate = delegate
        val resolved = ObjVoid.resolveRecord(scope, rec, nameConst.value, null)
        frame.storeObjResult(dst, resolved.value)
        return
    }
}

class CmdDelegatedSetLocal(
    internal val delegateSlot: Int,
    internal val nameId: Int,
    internal val valueSlot: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val nameConst = frame.fn.constants.getOrNull(nameId) as? BytecodeConst.StringVal
            ?: error("DELEGATED_SET_LOCAL expects StringVal at $nameId")
        val delegate = frame.slotToObj(delegateSlot)
        val scope = frame.ensureScope()
        val value = frame.slotToObj(valueSlot)
        delegate.invokeInstanceMethod(scope, "setValue", Arguments(ObjNull, ObjString(nameConst.value), value))
        return
    }
}

class CmdBindDelegateLocal(
    internal val delegateSlot: Int,
    internal val nameId: Int,
    internal val accessId: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val nameConst = frame.fn.constants.getOrNull(nameId) as? BytecodeConst.StringVal
            ?: error("BIND_DELEGATE_LOCAL expects StringVal at $nameId")
        val accessConst = frame.fn.constants.getOrNull(accessId) as? BytecodeConst.StringVal
            ?: error("BIND_DELEGATE_LOCAL expects StringVal at $accessId")
        val delegate = frame.slotToObj(delegateSlot)
        val scope = frame.ensureScope()
        val bound = try {
            delegate.invokeInstanceMethod(
                scope,
                "bind",
                Arguments(ObjString(nameConst.value), ObjString(accessConst.value), ObjNull)
            )
        } catch (_: Exception) {
            delegate
        }
        frame.storeObjResult(dst, bound)
        return
    }
}

class CmdIntToReal(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, frame.getReal(src))
        return
    }
}

class CmdIntToRealLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val value = frame.getLocalObjRealValue(src)
        frame.setLocalReal(dst, value)
        return true
    }
}

class CmdRealToInt(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getReal(src).toLong())
        return
    }
}

class CmdRealToIntLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalReal(src).toLong())
        return true
    }
}

class CmdBoolToInt(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, if (frame.getBool(src)) 1L else 0L)
        return
    }
}

class CmdBoolToIntLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, if (frame.getLocalBool(src)) 1L else 0L)
        return true
    }
}

class CmdIntToBool(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getBool(src))
        return
    }
}

class CmdIntToBoolLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(src) != 0L)
        return true
    }
}

class CmdAddInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) + frame.getInt(b))
        return
    }
}

class CmdAddIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(a) + frame.getLocalInt(b))
        return true
    }
}

class CmdSubInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) - frame.getInt(b))
        return
    }
}

class CmdSubIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(a) - frame.getLocalInt(b))
        return true
    }
}

class CmdMulInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) * frame.getInt(b))
        return
    }
}

class CmdMulIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(a) * frame.getLocalInt(b))
        return true
    }
}

class CmdDivInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) / frame.getInt(b))
        return
    }
}

class CmdDivIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(a) / frame.getLocalInt(b))
        return true
    }
}

class CmdModInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) % frame.getInt(b))
        return
    }
}

class CmdModIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(a) % frame.getLocalInt(b))
        return true
    }
}

class CmdNegInt(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, -frame.getInt(src))
        return
    }
}

class CmdNegIntLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, -frame.getLocalInt(src))
        return true
    }
}

class CmdIncInt(internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(slot, frame.getInt(slot) + 1L)
        return
    }
}

class CmdIncIntLocal(internal val slot: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(slot, frame.getLocalInt(slot) + 1L)
        return true
    }
}

class CmdDecInt(internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(slot, frame.getInt(slot) - 1L)
        return
    }
}

class CmdDecIntLocal(internal val slot: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(slot, frame.getLocalInt(slot) - 1L)
        return true
    }
}

class CmdAddReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, frame.getReal(a) + frame.getReal(b))
        return
    }
}

class CmdAddRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalReal(dst, frame.getLocalReal(a) + frame.getLocalReal(b))
        return true
    }
}

class CmdSubReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, frame.getReal(a) - frame.getReal(b))
        return
    }
}

class CmdSubRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalReal(dst, frame.getLocalReal(a) - frame.getLocalReal(b))
        return true
    }
}

class CmdMulReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, frame.getReal(a) * frame.getReal(b))
        return
    }
}

class CmdMulRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalReal(dst, frame.getLocalReal(a) * frame.getLocalReal(b))
        return true
    }
}

class CmdDivReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, frame.getReal(a) / frame.getReal(b))
        return
    }
}

class CmdDivRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalReal(dst, frame.getLocalReal(a) / frame.getLocalReal(b))
        return true
    }
}

class CmdNegReal(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, -frame.getReal(src))
        return
    }
}

class CmdNegRealLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalReal(dst, -frame.getLocalReal(src))
        return true
    }
}

class CmdAndInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) and frame.getInt(b))
        return
    }
}

class CmdAndIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(a) and frame.getLocalInt(b))
        return true
    }
}

class CmdOrInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) or frame.getInt(b))
        return
    }
}

class CmdOrIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(a) or frame.getLocalInt(b))
        return true
    }
}

class CmdXorInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) xor frame.getInt(b))
        return
    }
}

class CmdXorIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(a) xor frame.getLocalInt(b))
        return true
    }
}

class CmdShlInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) shl frame.getInt(b).toInt())
        return
    }
}

class CmdShlIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(a) shl frame.getLocalInt(b).toInt())
        return true
    }
}

class CmdShrInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) shr frame.getInt(b).toInt())
        return
    }
}

class CmdShrIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(a) shr frame.getLocalInt(b).toInt())
        return true
    }
}

class CmdUshrInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) ushr frame.getInt(b).toInt())
        return
    }
}

class CmdUshrIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(a) ushr frame.getLocalInt(b).toInt())
        return true
    }
}

class CmdInvInt(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(src).inv())
        return
    }
}

class CmdInvIntLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalInt(dst, frame.getLocalInt(src).inv())
        return true
    }
}

class CmdCmpEqInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) == frame.getInt(b))
        return
    }
}

class CmdCmpEqIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a) == frame.getLocalInt(b))
        return true
    }
}

class CmdCmpNeqInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) != frame.getInt(b))
        return
    }
}

class CmdCmpNeqIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a) != frame.getLocalInt(b))
        return true
    }
}

class CmdCmpLtInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) < frame.getInt(b))
        return
    }
}

class CmdCmpLtIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a) < frame.getLocalInt(b))
        return true
    }
}

class CmdCmpLteInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) <= frame.getInt(b))
        return
    }
}

class CmdCmpLteIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a) <= frame.getLocalInt(b))
        return true
    }
}

class CmdCmpGtInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) > frame.getInt(b))
        return
    }
}

class CmdCmpGtIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a) > frame.getLocalInt(b))
        return true
    }
}

class CmdCmpGteInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) >= frame.getInt(b))
        return
    }
}

class CmdCmpGteIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a) >= frame.getLocalInt(b))
        return true
    }
}

class CmdCmpEqReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) == frame.getReal(b))
        return
    }
}

class CmdCmpEqRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) == frame.getLocalReal(b))
        return true
    }
}

class CmdCmpNeqReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) != frame.getReal(b))
        return
    }
}

class CmdCmpNeqRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) != frame.getLocalReal(b))
        return true
    }
}

class CmdCmpLtReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) < frame.getReal(b))
        return
    }
}

class CmdCmpLtRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) < frame.getLocalReal(b))
        return true
    }
}

class CmdCmpLteReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) <= frame.getReal(b))
        return
    }
}

class CmdCmpLteRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) <= frame.getLocalReal(b))
        return true
    }
}

class CmdCmpGtReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) > frame.getReal(b))
        return
    }
}

class CmdCmpGtRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) > frame.getLocalReal(b))
        return true
    }
}

class CmdCmpGteReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) >= frame.getReal(b))
        return
    }
}

class CmdCmpGteRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) >= frame.getLocalReal(b))
        return true
    }
}

class CmdCmpEqBool(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getBool(a) == frame.getBool(b))
        return
    }
}

class CmdCmpEqBoolLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalBool(a) == frame.getLocalBool(b))
        return true
    }
}

class CmdCmpNeqBool(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getBool(a) != frame.getBool(b))
        return
    }
}

class CmdCmpNeqBoolLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalBool(a) != frame.getLocalBool(b))
        return true
    }
}

class CmdCmpEqIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() == frame.getReal(b))
        return
    }
}

class CmdCmpEqIntRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a).toDouble() == frame.getLocalReal(b))
        return true
    }
}

class CmdCmpEqRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) == frame.getInt(b).toDouble())
        return
    }
}

class CmdCmpEqRealIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) == frame.getLocalInt(b).toDouble())
        return true
    }
}

class CmdCmpLtIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() < frame.getReal(b))
        return
    }
}

class CmdCmpLtIntRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a).toDouble() < frame.getLocalReal(b))
        return true
    }
}

class CmdCmpLtRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) < frame.getInt(b).toDouble())
        return
    }
}

class CmdCmpLtRealIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) < frame.getLocalInt(b).toDouble())
        return true
    }
}

class CmdCmpLteIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() <= frame.getReal(b))
        return
    }
}

class CmdCmpLteIntRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a).toDouble() <= frame.getLocalReal(b))
        return true
    }
}

class CmdCmpLteRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) <= frame.getInt(b).toDouble())
        return
    }
}

class CmdCmpLteRealIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) <= frame.getLocalInt(b).toDouble())
        return true
    }
}

class CmdCmpGtIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() > frame.getReal(b))
        return
    }
}

class CmdCmpGtIntRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a).toDouble() > frame.getLocalReal(b))
        return true
    }
}

class CmdCmpGtRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) > frame.getInt(b).toDouble())
        return
    }
}

class CmdCmpGtRealIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) > frame.getLocalInt(b).toDouble())
        return true
    }
}

class CmdCmpGteIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() >= frame.getReal(b))
        return
    }
}

class CmdCmpGteIntRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a).toDouble() >= frame.getLocalReal(b))
        return true
    }
}

class CmdCmpGteRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) >= frame.getInt(b).toDouble())
        return
    }
}

class CmdCmpGteRealIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) >= frame.getLocalInt(b).toDouble())
        return true
    }
}

class CmdCmpNeqIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() != frame.getReal(b))
        return
    }
}

class CmdCmpNeqIntRealLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalInt(a).toDouble() != frame.getLocalReal(b))
        return true
    }
}

class CmdCmpNeqRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) != frame.getInt(b).toDouble())
        return
    }
}

class CmdCmpNeqRealIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalReal(a) != frame.getLocalInt(b).toDouble())
        return true
    }
}

class CmdCmpEqObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        frame.setBool(dst, left.equals(frame.ensureScope(), right))
        return
    }
}

class CmdCmpNeqObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        frame.setBool(dst, !left.equals(frame.ensureScope(), right))
        return
    }
}

class CmdCmpRefEqObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(a) === frame.slotToObj(b))
        return
    }
}

class CmdCmpRefNeqObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(a) !== frame.slotToObj(b))
        return
    }
}

class CmdCmpEqStr(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjString && right is ObjString) {
            frame.setBool(dst, left.value == right.value)
            return
        }
        frame.setBool(dst, left.equals(frame.ensureScope(), right))
        return
    }
}

class CmdCmpEqStrLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.frame.getRawObj(a) as ObjString
        val right = frame.frame.getRawObj(b) as ObjString
        frame.setLocalBool(dst, left.value == right.value)
        return true
    }
}

class CmdCmpNeqStr(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjString && right is ObjString) {
            frame.setBool(dst, left.value != right.value)
            return
        }
        frame.setBool(dst, !left.equals(frame.ensureScope(), right))
        return
    }
}

class CmdCmpNeqStrLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.frame.getRawObj(a) as ObjString
        val right = frame.frame.getRawObj(b) as ObjString
        frame.setLocalBool(dst, left.value != right.value)
        return true
    }
}

class CmdCmpLtStr(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjString && right is ObjString) {
            frame.setBool(dst, left.value < right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) < 0)
        return
    }
}

class CmdCmpLtStrLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.frame.getRawObj(a) as ObjString
        val right = frame.frame.getRawObj(b) as ObjString
        frame.setLocalBool(dst, left.value < right.value)
        return true
    }
}

class CmdCmpLteStr(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjString && right is ObjString) {
            frame.setBool(dst, left.value <= right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) <= 0)
        return
    }
}

class CmdCmpLteStrLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.frame.getRawObj(a) as ObjString
        val right = frame.frame.getRawObj(b) as ObjString
        frame.setLocalBool(dst, left.value <= right.value)
        return true
    }
}

class CmdCmpGtStr(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjString && right is ObjString) {
            frame.setBool(dst, left.value > right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) > 0)
        return
    }
}

class CmdCmpGtStrLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.frame.getRawObj(a) as ObjString
        val right = frame.frame.getRawObj(b) as ObjString
        frame.setLocalBool(dst, left.value > right.value)
        return true
    }
}

class CmdCmpGteStr(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjString && right is ObjString) {
            frame.setBool(dst, left.value >= right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) >= 0)
        return
    }
}

class CmdCmpGteStrLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.frame.getRawObj(a) as ObjString
        val right = frame.frame.getRawObj(b) as ObjString
        frame.setLocalBool(dst, left.value >= right.value)
        return true
    }
}

class CmdCmpEqIntObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjInt && right is ObjInt) {
            frame.setBool(dst, left.value == right.value)
            return
        }
        frame.setBool(dst, left.equals(frame.ensureScope(), right))
        return
    }
}

class CmdCmpEqIntObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.INT.code -> frame.frame.getInt(a)
            else -> (frame.frame.getRawObj(a) as ObjInt).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.INT.code -> frame.frame.getInt(b)
            else -> (frame.frame.getRawObj(b) as ObjInt).value
        }
        frame.setLocalBool(dst, left == right)
        return true
    }
}

class CmdCmpNeqIntObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjInt && right is ObjInt) {
            frame.setBool(dst, left.value != right.value)
            return
        }
        frame.setBool(dst, !left.equals(frame.ensureScope(), right))
        return
    }
}

class CmdCmpNeqIntObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.INT.code -> frame.frame.getInt(a)
            else -> (frame.frame.getRawObj(a) as ObjInt).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.INT.code -> frame.frame.getInt(b)
            else -> (frame.frame.getRawObj(b) as ObjInt).value
        }
        frame.setLocalBool(dst, left != right)
        return true
    }
}

class CmdCmpLtIntObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjInt && right is ObjInt) {
            frame.setBool(dst, left.value < right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) < 0)
        return
    }
}

class CmdCmpLtIntObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.INT.code -> frame.frame.getInt(a)
            else -> (frame.frame.getRawObj(a) as ObjInt).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.INT.code -> frame.frame.getInt(b)
            else -> (frame.frame.getRawObj(b) as ObjInt).value
        }
        frame.setLocalBool(dst, left < right)
        return true
    }
}

class CmdCmpLteIntObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjInt && right is ObjInt) {
            frame.setBool(dst, left.value <= right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) <= 0)
        return
    }
}

class CmdCmpLteIntObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.INT.code -> frame.frame.getInt(a)
            else -> (frame.frame.getRawObj(a) as ObjInt).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.INT.code -> frame.frame.getInt(b)
            else -> (frame.frame.getRawObj(b) as ObjInt).value
        }
        frame.setLocalBool(dst, left <= right)
        return true
    }
}

class CmdCmpGtIntObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjInt && right is ObjInt) {
            frame.setBool(dst, left.value > right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) > 0)
        return
    }
}

class CmdCmpGtIntObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.INT.code -> frame.frame.getInt(a)
            else -> (frame.frame.getRawObj(a) as ObjInt).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.INT.code -> frame.frame.getInt(b)
            else -> (frame.frame.getRawObj(b) as ObjInt).value
        }
        frame.setLocalBool(dst, left > right)
        return true
    }
}

class CmdCmpGteIntObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjInt && right is ObjInt) {
            frame.setBool(dst, left.value >= right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) >= 0)
        return
    }
}

class CmdCmpGteIntObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.INT.code -> frame.frame.getInt(a)
            else -> (frame.frame.getRawObj(a) as ObjInt).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.INT.code -> frame.frame.getInt(b)
            else -> (frame.frame.getRawObj(b) as ObjInt).value
        }
        frame.setLocalBool(dst, left >= right)
        return true
    }
}

class CmdCmpEqRealObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjReal && right is ObjReal) {
            frame.setBool(dst, left.value == right.value)
            return
        }
        frame.setBool(dst, left.equals(frame.ensureScope(), right))
        return
    }
}

class CmdCmpEqRealObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.REAL.code -> frame.frame.getReal(a)
            else -> (frame.frame.getRawObj(a) as ObjReal).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.REAL.code -> frame.frame.getReal(b)
            else -> (frame.frame.getRawObj(b) as ObjReal).value
        }
        frame.setLocalBool(dst, left == right)
        return true
    }
}

class CmdCmpNeqRealObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjReal && right is ObjReal) {
            frame.setBool(dst, left.value != right.value)
            return
        }
        frame.setBool(dst, !left.equals(frame.ensureScope(), right))
        return
    }
}

class CmdCmpNeqRealObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.REAL.code -> frame.frame.getReal(a)
            else -> (frame.frame.getRawObj(a) as ObjReal).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.REAL.code -> frame.frame.getReal(b)
            else -> (frame.frame.getRawObj(b) as ObjReal).value
        }
        frame.setLocalBool(dst, left != right)
        return true
    }
}

class CmdCmpLtRealObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjReal && right is ObjReal) {
            frame.setBool(dst, left.value < right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) < 0)
        return
    }
}

class CmdCmpLtRealObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.REAL.code -> frame.frame.getReal(a)
            else -> (frame.frame.getRawObj(a) as ObjReal).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.REAL.code -> frame.frame.getReal(b)
            else -> (frame.frame.getRawObj(b) as ObjReal).value
        }
        frame.setLocalBool(dst, left < right)
        return true
    }
}

class CmdCmpLteRealObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjReal && right is ObjReal) {
            frame.setBool(dst, left.value <= right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) <= 0)
        return
    }
}

class CmdCmpLteRealObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.REAL.code -> frame.frame.getReal(a)
            else -> (frame.frame.getRawObj(a) as ObjReal).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.REAL.code -> frame.frame.getReal(b)
            else -> (frame.frame.getRawObj(b) as ObjReal).value
        }
        frame.setLocalBool(dst, left <= right)
        return true
    }
}

class CmdCmpGtRealObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjReal && right is ObjReal) {
            frame.setBool(dst, left.value > right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) > 0)
        return
    }
}

class CmdCmpGtRealObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.REAL.code -> frame.frame.getReal(a)
            else -> (frame.frame.getRawObj(a) as ObjReal).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.REAL.code -> frame.frame.getReal(b)
            else -> (frame.frame.getRawObj(b) as ObjReal).value
        }
        frame.setLocalBool(dst, left > right)
        return true
    }
}

class CmdCmpGteRealObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        if (left is ObjReal && right is ObjReal) {
            frame.setBool(dst, left.value >= right.value)
            return
        }
        frame.setBool(dst, left.compareTo(frame.ensureScope(), right) >= 0)
        return
    }
}

class CmdCmpGteRealObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = when (frame.frame.getSlotTypeCode(a)) {
            SlotType.REAL.code -> frame.frame.getReal(a)
            else -> (frame.frame.getRawObj(a) as ObjReal).value
        }
        val right = when (frame.frame.getSlotTypeCode(b)) {
            SlotType.REAL.code -> frame.frame.getReal(b)
            else -> (frame.frame.getRawObj(b) as ObjReal).value
        }
        frame.setLocalBool(dst, left >= right)
        return true
    }
}

class CmdNotBool(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, !frame.getBool(src))
        return
    }
}

class CmdNotBoolLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, !frame.getLocalBool(src))
        return true
    }
}

class CmdAndBool(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getBool(a) && frame.getBool(b))
        return
    }
}

class CmdAndBoolLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalBool(a) && frame.getLocalBool(b))
        return true
    }
}

class CmdOrBool(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getBool(a) || frame.getBool(b))
        return
    }
}

class CmdOrBoolLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.setLocalBool(dst, frame.getLocalBool(a) || frame.getLocalBool(b))
        return true
    }
}

class CmdCmpLtObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(a).compareTo(frame.ensureScope(), frame.slotToObj(b)) < 0)
        return
    }
}

class CmdCmpLteObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(a).compareTo(frame.ensureScope(), frame.slotToObj(b)) <= 0)
        return
    }
}

class CmdCmpGtObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(a).compareTo(frame.ensureScope(), frame.slotToObj(b)) > 0)
        return
    }
}

class CmdCmpGteObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(a).compareTo(frame.ensureScope(), frame.slotToObj(b)) >= 0)
        return
    }
}

class CmdAddObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val result = frame.slotToObj(a).plus(frame.ensureScope(), frame.slotToObj(b))
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdSubObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val result = frame.slotToObj(a).minus(frame.ensureScope(), frame.slotToObj(b))
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdMulObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val result = frame.slotToObj(a).mul(frame.ensureScope(), frame.slotToObj(b))
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdDivObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val result = frame.slotToObj(a).div(frame.ensureScope(), frame.slotToObj(b))
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdModObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val result = frame.slotToObj(a).mod(frame.ensureScope(), frame.slotToObj(b))
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdAddIntObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a) as ObjInt
        val right = frame.slotToObj(b) as ObjInt
        frame.storeObjResult(dst, ObjInt.of(left.value + right.value))
        return
    }
}

class CmdAddIntObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.getLocalObjIntValue(a)
        val right = frame.getLocalObjIntValue(b)
        frame.storeObjResult(dst, ObjInt.of(left + right))
        return true
    }
}

class CmdSubIntObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a) as ObjInt
        val right = frame.slotToObj(b) as ObjInt
        frame.storeObjResult(dst, ObjInt.of(left.value - right.value))
        return
    }
}

class CmdSubIntObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.getLocalObjIntValue(a)
        val right = frame.getLocalObjIntValue(b)
        frame.storeObjResult(dst, ObjInt.of(left - right))
        return true
    }
}

class CmdMulIntObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a) as ObjInt
        val right = frame.slotToObj(b) as ObjInt
        frame.storeObjResult(dst, ObjInt.of(left.value * right.value))
        return
    }
}

class CmdMulIntObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.getLocalObjIntValue(a)
        val right = frame.getLocalObjIntValue(b)
        frame.storeObjResult(dst, ObjInt.of(left * right))
        return true
    }
}

class CmdDivIntObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a) as ObjInt
        val right = frame.slotToObj(b) as ObjInt
        frame.storeObjResult(dst, ObjInt.of(left.value / right.value))
        return
    }
}

class CmdDivIntObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.getLocalObjIntValue(a)
        val right = frame.getLocalObjIntValue(b)
        frame.storeObjResult(dst, ObjInt.of(left / right))
        return true
    }
}

class CmdModIntObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a) as ObjInt
        val right = frame.slotToObj(b) as ObjInt
        frame.storeObjResult(dst, ObjInt.of(left.value % right.value))
        return
    }
}

class CmdModIntObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.getLocalObjIntValue(a)
        val right = frame.getLocalObjIntValue(b)
        frame.storeObjResult(dst, ObjInt.of(left % right))
        return true
    }
}

class CmdAddRealObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a) as ObjReal
        val right = frame.slotToObj(b) as ObjReal
        frame.storeObjResult(dst, ObjReal.of(left.value + right.value))
        return
    }
}

class CmdAddRealObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.getLocalObjRealValue(a)
        val right = frame.getLocalObjRealValue(b)
        frame.storeObjResult(dst, ObjReal.of(left + right))
        return true
    }
}

class CmdSubRealObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a) as ObjReal
        val right = frame.slotToObj(b) as ObjReal
        frame.storeObjResult(dst, ObjReal.of(left.value - right.value))
        return
    }
}

class CmdSubRealObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.getLocalObjRealValue(a)
        val right = frame.getLocalObjRealValue(b)
        frame.storeObjResult(dst, ObjReal.of(left - right))
        return true
    }
}

class CmdMulRealObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a) as ObjReal
        val right = frame.slotToObj(b) as ObjReal
        frame.storeObjResult(dst, ObjReal.of(left.value * right.value))
        return
    }
}

class CmdMulRealObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.getLocalObjRealValue(a)
        val right = frame.getLocalObjRealValue(b)
        frame.storeObjResult(dst, ObjReal.of(left * right))
        return true
    }
}

class CmdDivRealObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a) as ObjReal
        val right = frame.slotToObj(b) as ObjReal
        frame.storeObjResult(dst, ObjReal.of(left.value / right.value))
        return
    }
}

class CmdDivRealObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.getLocalObjRealValue(a)
        val right = frame.getLocalObjRealValue(b)
        frame.storeObjResult(dst, ObjReal.of(left / right))
        return true
    }
}

class CmdModRealObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a) as ObjReal
        val right = frame.slotToObj(b) as ObjReal
        frame.storeObjResult(dst, ObjReal.of(left.value % right.value))
        return
    }
}

class CmdModRealObjLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        val left = frame.getLocalObjRealValue(a)
        val right = frame.getLocalObjRealValue(b)
        frame.storeObjResult(dst, ObjReal.of(left % right))
        return true
    }
}

class CmdContainsObj(internal val target: Int, internal val value: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val targetObj = frame.slotToObj(target)
        val valueObj = frame.slotToObj(value)
        val result = if ((targetObj is ObjTypeExpr || targetObj is ObjClass) &&
            (valueObj is ObjTypeExpr || valueObj is ObjClass)
        ) {
            val leftDecl = typeDeclFromObj(frame.ensureScope(), valueObj)
            val rightDecl = typeDeclFromObj(frame.ensureScope(), targetObj)
            if (leftDecl != null && rightDecl != null) {
                typeDeclIsSubtype(frame.ensureScope(), leftDecl, rightDecl)
            } else {
                false
            }
        } else {
            targetObj.contains(frame.ensureScope(), valueObj)
        }
        frame.setBool(dst, result)
        return
    }
}

class CmdAssignOpObj(
    internal val opId: Int,
    internal val targetSlot: Int,
    internal val valueSlot: Int,
    internal val dst: Int,
    internal val nameId: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val target = frame.slotToObj(targetSlot)
        val value = frame.slotToObj(valueSlot)
        val result = when (BinOp.values().getOrNull(opId)) {
            BinOp.PLUS -> target.plusAssign(frame.ensureScope(), value)
            BinOp.MINUS -> target.minusAssign(frame.ensureScope(), value)
            BinOp.STAR -> target.mulAssign(frame.ensureScope(), value)
            BinOp.SLASH -> target.divAssign(frame.ensureScope(), value)
            BinOp.PERCENT -> target.modAssign(frame.ensureScope(), value)
            else -> null
        }
        if (result == null) {
            val name = (frame.fn.constants.getOrNull(nameId) as? BytecodeConst.StringVal)?.value
            if (name != null) frame.ensureScope().raiseIllegalAssignment("symbol is readonly: $name")
            frame.ensureScope().raiseIllegalAssignment("symbol is readonly")
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdJmp(internal val target: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        frame.ip = target
        return true
    }
}

class CmdJmpIfTrue(internal val cond: Int, internal val target: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.getBool(cond)) {
            frame.ip = target
        }
        return
    }
}

class CmdJmpIfTrueLocal(internal val cond: Int, internal val target: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        if (frame.getLocalBool(cond)) {
            frame.ip = target
        }
        return true
    }
}

class CmdJmpIfFalse(internal val cond: Int, internal val target: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (!frame.getBool(cond)) {
            frame.ip = target
        }
        return
    }
}

class CmdJmpIfFalseLocal(internal val cond: Int, internal val target: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        if (!frame.getLocalBool(cond)) {
            frame.ip = target
        }
        return true
    }
}

class CmdJmpIfEqInt(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.getInt(a) == frame.getInt(b)) {
            frame.ip = target
        }
        return
    }
}

class CmdJmpIfEqIntLocal(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        if (frame.getLocalInt(a) == frame.getLocalInt(b)) {
            frame.ip = target
        }
        return true
    }
}

class CmdJmpIfNeqInt(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.getInt(a) != frame.getInt(b)) {
            frame.ip = target
        }
        return
    }
}

class CmdJmpIfNeqIntLocal(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        if (frame.getLocalInt(a) != frame.getLocalInt(b)) {
            frame.ip = target
        }
        return true
    }
}

class CmdJmpIfLtInt(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.getInt(a) < frame.getInt(b)) {
            frame.ip = target
        }
        return
    }
}

class CmdJmpIfLtIntLocal(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        if (frame.getLocalInt(a) < frame.getLocalInt(b)) {
            frame.ip = target
        }
        return true
    }
}

class CmdJmpIfLteInt(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.getInt(a) <= frame.getInt(b)) {
            frame.ip = target
        }
        return
    }
}

class CmdJmpIfLteIntLocal(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        if (frame.getLocalInt(a) <= frame.getLocalInt(b)) {
            frame.ip = target
        }
        return true
    }
}

class CmdJmpIfGtInt(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.getInt(a) > frame.getInt(b)) {
            frame.ip = target
        }
        return
    }
}

class CmdJmpIfGtIntLocal(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        if (frame.getLocalInt(a) > frame.getLocalInt(b)) {
            frame.ip = target
        }
        return true
    }
}

class CmdJmpIfGteInt(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.getInt(a) >= frame.getInt(b)) {
            frame.ip = target
        }
        return
    }
}

class CmdJmpIfGteIntLocal(internal val a: Int, internal val b: Int, internal val target: Int) : Cmd() {
    override fun performFast(frame: CmdFrame): Boolean {
        if (frame.getLocalInt(a) >= frame.getLocalInt(b)) {
            frame.ip = target
        }
        return true
    }
}

class CmdRet(internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.vm.result = frame.slotToObj(slot)
        return
    }
}

class CmdRetLabel(internal val labelId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val labelConst = frame.fn.constants.getOrNull(labelId) as? BytecodeConst.StringVal
            ?: error("RET_LABEL expects StringVal at $labelId")
        val value = frame.slotToObj(slot)
        if (frame.fn.returnLabels.contains(labelConst.value)) {
            frame.vm.result = value
        } else {
            throw ReturnException(value, labelConst.value)
        }
        return
    }
}

class CmdRetVoid : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.vm.result = ObjVoid
        return
    }
}

class CmdThrow(internal val posId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val posConst = frame.fn.constants.getOrNull(posId) as? BytecodeConst.PosVal
            ?: error("THROW expects PosVal at $posId")
        frame.throwObj(posConst.pos, frame.slotToObj(slot))
        return
    }
}

class CmdRethrowPending : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.rethrowPending()
        return
    }
}

class CmdPushScope(internal val planId: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val planConst = frame.fn.constants[planId] as? BytecodeConst.SlotPlan
            ?: error("PUSH_SCOPE expects SlotPlan at $planId")
        frame.pushScope(planConst.plan, planConst.captures)
        return
    }
}

class CmdPopScope : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.popScope()
        return
    }
}

class CmdPushSlotPlan(internal val planId: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val planConst = frame.fn.constants[planId] as? BytecodeConst.SlotPlan
            ?: error("PUSH_SLOT_PLAN expects SlotPlan at $planId")
        frame.pushSlotPlan(planConst.plan)
        return
    }
}

class CmdPopSlotPlan : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.popSlotPlan()
        return
    }
}

class CmdPushTry(internal val exceptionSlot: Int, internal val catchIp: Int, internal val finallyIp: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.pushTry(exceptionSlot, catchIp, finallyIp)
        return
    }
}

class CmdPopTry : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.popTry()
        return
    }
}

class CmdClearPendingThrowable : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.clearPendingThrowable()
        return
    }
}

class CmdDeclLocal(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.LocalDecl
            ?: error("DECL_LOCAL expects LocalDecl at $constId")
        if (slot < frame.fn.scopeSlotCount) {
            val target = frame.scopeTarget(slot)
            val index = frame.ensureScopeSlot(target, slot)
            val raw = target.getSlotRecord(index).value
            val value = when (raw) {
                is FrameSlotRef -> raw.read()
                is RecordSlotRef -> raw.read()
                else -> raw
            }.byValueCopy()
            target.updateSlotFor(
                decl.name,
                ObjRecord(
                    value,
                    decl.isMutable,
                    decl.visibility,
                    isTransient = decl.isTransient,
                    type = ObjRecord.Type.Other,
                    typeDecl = decl.typeDecl
                )
            )
            return
        }
        val localIndex = slot - frame.fn.scopeSlotCount
        if (localIndex < 0) return
        val record = ObjRecord(
            FrameSlotRef(frame.frame, localIndex),
            decl.isMutable,
            decl.visibility,
            isTransient = decl.isTransient,
            type = ObjRecord.Type.Other,
            typeDecl = decl.typeDecl
        )
        val moduleScope = frame.scope as? ModuleScope
        if (moduleScope != null) {
            moduleScope.objects[decl.name] = record
            moduleScope.localBindings[decl.name] = record
        } else if (frame.fn.name == "<script>") {
            val target = frame.ensureScope()
            target.objects[decl.name] = record
            target.localBindings[decl.name] = record
        }
        return
    }
}

class CmdDeclDelegated(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.DelegatedDecl
            ?: error("DECL_DELEGATED expects DelegatedDecl at $constId")
        val initValue = frame.slotToObj(slot)
        val accessType = ObjString(if (decl.isMutable) "Var" else "Val")
        val finalDelegate = try {
            initValue.invokeInstanceMethod(
                frame.ensureScope(),
                "bind",
                Arguments(ObjString(decl.name), accessType, ObjNull)
            )
        } catch (_: Exception) {
            initValue
        }
        if (slot < frame.fn.scopeSlotCount) {
            val target = frame.scopeTarget(slot)
            frame.ensureScopeSlot(target, slot)
            target.updateSlotFor(
                decl.name,
                ObjRecord(
                    ObjNull,
                    decl.isMutable,
                    decl.visibility,
                    isTransient = decl.isTransient,
                    type = ObjRecord.Type.Delegated
                ).also { it.delegate = finalDelegate }
            )
        } else {
            val moduleScope = frame.scope as? ModuleScope
            if (moduleScope != null) {
                moduleScope.updateSlotFor(
                    decl.name,
                    ObjRecord(
                        ObjNull,
                        decl.isMutable,
                        decl.visibility,
                        isTransient = decl.isTransient,
                        type = ObjRecord.Type.Delegated
                    ).also { it.delegate = finalDelegate }
                )
            }
        }
        frame.setObjUnchecked(slot, finalDelegate)
        return
    }
}

class CmdDeclEnum(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.EnumDecl
            ?: error("DECL_ENUM expects EnumDecl at $constId")
        val scope = frame.ensureScope()
        val enumClass = ObjEnumClass.createSimpleEnum(decl.qualifiedName, decl.entries)
        scope.addItem(decl.declaredName, false, enumClass, recordType = ObjRecord.Type.Enum)
        if (decl.lifted) {
            for (entry in decl.entries) {
                val rec = enumClass.getInstanceMemberOrNull(entry, includeAbstract = false, includeStatic = true)
                if (rec != null) {
                    scope.addItem(entry, false, rec.value)
                }
            }
        }
        frame.setObjUnchecked(slot, enumClass)
        return
    }
}

class CmdDeclFunction(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.FunctionDecl
            ?: error("DECL_FUNCTION expects FunctionDecl at $constId")
        val captureNames = captureNamesForFunctionDecl(decl.spec)
        val captureRecords = buildFunctionCaptureRecords(frame, captureNames)
        val result = executeFunctionDecl(frame.ensureScope(), decl.spec, captureRecords)
        frame.setObjUnchecked(slot, result)
        val wrapperName = decl.spec.extensionWrapperName
        if (wrapperName != null) {
            val wrapperRecord = frame.ensureScope()[wrapperName]
            if (wrapperRecord != null) {
                val localIndex = resolveLocalSlotIndex(frame.fn, wrapperName, preferCapture = false)
                if (localIndex != null) {
                    frame.setObjUnchecked(frame.fn.scopeSlotCount + localIndex, wrapperRecord.value)
                }
            }
        }
        return
    }
}

private fun captureNamesForFunctionDecl(spec: net.sergeych.lyng.FunctionDeclSpec): List<String> {
    val declaredCaptures = spec.captureSlots.map { it.name }
    return mergeCaptureNames(declaredCaptures, captureNamesForStatement(spec.fnBody))
}

private fun captureNamesForStatement(stmt: Statement?): List<String> {
    if (stmt == null) return emptyList()
    val bytecode = when (stmt) {
        is BytecodeStatement -> stmt.bytecodeFunction()
        is BytecodeBodyProvider -> stmt.bytecodeBody()?.bytecodeFunction()
        else -> null
    } ?: return emptyList()
    return captureNamesForBytecode(bytecode)
}

private fun captureNamesForBytecode(bytecode: CmdFunction): List<String> {
    val names = bytecode.localSlotNames
    val captures = bytecode.localSlotCaptures
    val ordered = LinkedHashSet<String>()
    for (i in names.indices) {
        if (captures.getOrNull(i) != true) continue
        val name = names[i] ?: continue
        ordered.add(name)
    }
    collectNestedModuleCaptureNames(bytecode, ordered)
    return ordered.toList()
}

private fun collectNestedModuleCaptureNames(bytecode: CmdFunction, out: LinkedHashSet<String>) {
    for (constant in bytecode.constants) {
        val lambda = constant as? BytecodeConst.LambdaFn ?: continue
        val table = lambda.captureTableId?.let { bytecode.constants.getOrNull(it) as? BytecodeConst.CaptureTable }
        if (table != null) {
            for ((index, entry) in table.entries.withIndex()) {
                if (entry.ownerKind != CaptureOwnerFrameKind.MODULE) continue
                val name = lambda.captureNames.getOrNull(index) ?: continue
                out.add(name)
            }
        }
        collectNestedModuleCaptureNames(lambda.fn, out)
    }
}

private fun findInheritedCaptureRecord(scope: Scope, name: String): ObjRecord? {
    val inheritedNames = scope.captureNames ?: return null
    val inheritedRecords = scope.captureRecords ?: return null
    val inheritedIndex = inheritedNames.indexOf(name)
    if (inheritedIndex < 0) return null
    return inheritedRecords.getOrNull(inheritedIndex)
}

private fun freezeImmutableCaptureRecord(record: ObjRecord): ObjRecord {
    val value = record.value as Obj?
    if (record.isMutable || record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || value is ObjProperty) {
        return record
    }
    return when (value) {
        is FrameSlotRef -> value.resolvedCaptureValueOrNull()?.let { record.copy(value = it) } ?: record
        is RecordSlotRef -> value.resolvedCaptureValueOrNull()?.let { record.copy(value = it) } ?: record
        is ScopeSlotRef -> value.resolvedCaptureValueOrNull()?.let { record.copy(value = it) } ?: record
        null -> record
        else -> record.copy()
    }
}

private fun isTransientCapturePlaceholder(value: Obj?): Boolean {
    return when (value) {
        null, ObjVoid -> true
        is FrameSlotRef -> value.resolvedCaptureValueOrNull().let { it == null || it === ObjVoid }
        is RecordSlotRef -> value.resolvedCaptureValueOrNull().let { it == null || it === ObjVoid }
        is ScopeSlotRef -> value.resolvedCaptureValueOrNull().let { it == null || it === ObjVoid }
        else -> false
    }
}

private fun resolveStableCaptureRecord(scope: Scope, name: String): ObjRecord? {
    val direct = scope.chainLookupIgnoreClosure(name, followClosure = true) ?: scope.get(name)
    if (direct != null && !isTransientCapturePlaceholder(direct.value as Obj?)) {
        return direct
    }
    var parent = scope.parent
    while (parent != null) {
        val candidate = parent.chainLookupIgnoreClosure(name, followClosure = true) ?: parent.get(name)
        if (candidate != null && !isTransientCapturePlaceholder(candidate.value as Obj?)) {
            return candidate
        }
        parent = parent.parent
    }
    return direct
}

private fun buildFunctionCaptureRecords(frame: CmdFrame, captureNames: List<String>): List<ObjRecord>? {
    if (captureNames.isEmpty()) return null
    val records = ArrayList<ObjRecord>(captureNames.size)
    for (name in captureNames) {
        val localIndex = resolveLocalSlotIndex(frame.fn, name, preferCapture = true)
        if (localIndex != null) {
            val isMutable = frame.fn.localSlotMutables.getOrNull(localIndex) ?: false
            val isDelegated = frame.fn.localSlotDelegated.getOrNull(localIndex) ?: false
            if (isDelegated) {
                val delegate = frame.frame.getObj(localIndex)
                records += ObjRecord(ObjNull, isMutable, type = ObjRecord.Type.Delegated).also {
                    it.delegate = delegate
                }
            } else {
                val raw = frame.frame.getRawObj(localIndex)
                val captureRecord = if (isTransientCapturePlaceholder(raw)) {
                    resolveStableCaptureRecord(frame.scope.parent ?: frame.scope, name)
                } else {
                    resolveStableCaptureRecord(frame.scope, name)
                }
                if (captureRecord != null) {
                    records += freezeImmutableCaptureRecord(captureRecord)
                    continue
                }
                records += freezeImmutableCaptureRecord(ObjRecord(FrameSlotRef(frame.frame, localIndex), isMutable))
            }
            continue
        }
        val scopeSlot = frame.fn.scopeSlotNames.indexOfFirst { it == name }
        if (scopeSlot >= 0) {
            val target = frame.scopeTarget(scopeSlot)
            val index = frame.fn.scopeSlotIndices[scopeSlot]
            records += freezeImmutableCaptureRecord(target.getSlotRecord(index))
            continue
        }
        val scopeCaptures = frame.scope.captureRecords
        val scopeCaptureNames = frame.scope.captureNames
        if (scopeCaptures != null && scopeCaptureNames != null) {
            val idx = scopeCaptureNames.indexOf(name)
            if (idx >= 0) {
                val rec = scopeCaptures.getOrNull(idx)
                if (rec != null) {
                    records += rec
                    continue
                }
            }
        }
        val scoped = frame.scope.chainLookupIgnoreClosure(name, followClosure = true) ?: frame.scope.get(name)
        if (scoped != null) {
            records += freezeImmutableCaptureRecord(scoped)
            continue
        }
        frame.ensureScope().raiseSymbolNotFound("capture $name not found")
    }
    return records
}

class CmdDeclClass(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.ClassDecl
            ?: error("DECL_CLASS expects ClassDecl at $constId")
        val bodyCaptureNames = mergeCaptureNames(
            captureNamesForStatement(decl.spec.bodyInit),
            captureNamesFromFrame(frame)
        )
        val bodyCaptureRecords = buildFunctionCaptureRecords(frame, bodyCaptureNames)
        val result = executeClassDecl(frame.ensureScope(), decl.spec, bodyCaptureRecords, bodyCaptureNames)
        frame.setObjUnchecked(slot, result)
        val name = decl.spec.declaredName ?: return
        val moduleScope = frame.scope as? ModuleScope ?: return
        val record = ObjRecord(
            result,
            isMutable = false,
            visibility = net.sergeych.lyng.Visibility.Public,
            type = ObjRecord.Type.Other
        )
        moduleScope.updateSlotFor(name, record)
        moduleScope.objects[name] = record
        moduleScope.localBindings[name] = record
        return
    }
}

private fun mergeCaptureNames(primary: List<String>, fallback: List<String>): List<String> {
    if (fallback.isEmpty()) return primary
    if (primary.isEmpty()) return fallback
    val ordered = LinkedHashSet<String>(primary.size + fallback.size)
    ordered.addAll(primary)
    ordered.addAll(fallback)
    return ordered.toList()
}

private fun captureNamesFromFrame(frame: CmdFrame): List<String> {
    val ordered = LinkedHashSet<String>()
    for (name in frame.fn.localSlotNames) {
        if (name != null) ordered.add(name)
    }
    return ordered.toList()
}

class CmdDeclClassField(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.ClassFieldDecl
            ?: error("DECL_CLASS_FIELD expects ClassFieldDecl at $constId")
        val scope = frame.ensureScope()
        val cls = scope.thisObj as? ObjClass
            ?: scope.raiseIllegalState("class field init requires class scope")
        val value = frame.slotToObj(slot).byValueCopy()
        cls.createClassField(
            decl.name,
            value,
            decl.isMutable,
            decl.visibility,
            decl.writeVisibility,
            Pos.builtIn,
            isTransient = decl.isTransient
        )
        scope.addItem(
            decl.name,
            decl.isMutable,
            value,
            decl.visibility,
            decl.writeVisibility,
            recordType = ObjRecord.Type.Field,
            isTransient = decl.isTransient
        )
        return
    }
}

class CmdDeclClassDelegated(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.ClassDelegatedDecl
            ?: error("DECL_CLASS_DELEGATED expects ClassDelegatedDecl at $constId")
        val scope = frame.ensureScope()
        val cls = scope.thisObj as? ObjClass
            ?: scope.raiseIllegalState("class delegated init requires class scope")
        val initValue = frame.slotToObj(slot)
        val accessTypeStr = if (decl.isMutable) "Var" else "Val"
        val accessType = ObjString(accessTypeStr)
        val finalDelegate = try {
            initValue.invokeInstanceMethod(
                scope,
                "bind",
                Arguments(ObjString(decl.name), accessType, scope.thisObj)
            )
        } catch (_: Exception) {
            initValue
        }
        cls.createClassField(
            decl.name,
            ObjUnset,
            decl.isMutable,
            decl.visibility,
            decl.writeVisibility,
            Pos.builtIn,
            isTransient = decl.isTransient,
            type = ObjRecord.Type.Delegated
        ).apply {
            delegate = finalDelegate
        }
        scope.addItem(
            decl.name,
            decl.isMutable,
            ObjUnset,
            decl.visibility,
            decl.writeVisibility,
            recordType = ObjRecord.Type.Delegated,
            isTransient = decl.isTransient
        ).apply {
            delegate = finalDelegate
        }
        frame.storeObjResult(slot, finalDelegate)
        return
    }
}

class CmdDeclClassInstanceInit(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.ClassInstanceInitDecl
            ?: error("DECL_CLASS_INSTANCE_INIT expects ClassInstanceInitDecl at $constId")
        val scope = frame.ensureScope()
        val cls = scope.thisObj as? ObjClass
            ?: scope.raiseIllegalState("class instance init requires class scope")
        cls.instanceInitializers += decl.initStatement
        frame.storeObjResult(slot, ObjVoid)
        return
    }
}

class CmdDeclClassInstanceField(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.ClassInstanceFieldDecl
            ?: error("DECL_CLASS_INSTANCE_FIELD expects ClassInstanceFieldDecl at $constId")
        val scope = frame.ensureScope()
        val cls = scope.thisObj as? ObjClass
            ?: scope.raiseIllegalState("class instance field requires class scope")
        cls.createField(
            decl.name,
            ObjNull,
            isMutable = decl.isMutable,
            visibility = decl.visibility,
            writeVisibility = decl.writeVisibility,
            pos = decl.pos,
            declaringClass = cls,
            isAbstract = decl.isAbstract,
            isClosed = decl.isClosed,
            isOverride = decl.isOverride,
            isTransient = decl.isTransient,
            typeDecl = decl.typeDecl,
            type = ObjRecord.Type.Field,
            fieldId = decl.fieldId
        )
        if (!decl.isAbstract) {
            decl.initStatement?.let { cls.instanceInitializers += it }
        }
        frame.storeObjResult(slot, ObjVoid)
        return
    }
}

class CmdDeclClassInstanceProperty(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.ClassInstancePropertyDecl
            ?: error("DECL_CLASS_INSTANCE_PROPERTY expects ClassInstancePropertyDecl at $constId")
        val scope = frame.ensureScope()
        val cls = scope.thisObj as? ObjClass
            ?: scope.raiseIllegalState("class instance property requires class scope")
        cls.addProperty(
            name = decl.name,
            visibility = decl.visibility,
            writeVisibility = decl.writeVisibility,
            declaringClass = cls,
            isAbstract = decl.isAbstract,
            isClosed = decl.isClosed,
            isOverride = decl.isOverride,
            pos = decl.pos,
            prop = decl.prop,
            methodId = decl.methodId
        )
        if (!decl.isAbstract) {
            decl.initStatement?.let { cls.instanceInitializers += it }
        }
        frame.storeObjResult(slot, ObjVoid)
        return
    }
}

class CmdDeclClassInstanceDelegated(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.ClassInstanceDelegatedDecl
            ?: error("DECL_CLASS_INSTANCE_DELEGATED expects ClassInstanceDelegatedDecl at $constId")
        val scope = frame.ensureScope()
        val cls = scope.thisObj as? ObjClass
            ?: scope.raiseIllegalState("class instance delegated requires class scope")
        cls.createField(
            decl.name,
            ObjUnset,
            isMutable = decl.isMutable,
            visibility = decl.visibility,
            writeVisibility = decl.writeVisibility,
            pos = decl.pos,
            declaringClass = cls,
            isAbstract = decl.isAbstract,
            isClosed = decl.isClosed,
            isOverride = decl.isOverride,
            isTransient = decl.isTransient,
            type = ObjRecord.Type.Delegated,
            methodId = decl.methodId
        )
        if (!decl.isAbstract) {
            decl.initStatement?.let { cls.instanceInitializers += it }
        }
        frame.storeObjResult(slot, ObjVoid)
        return
    }
}

class CmdDeclInstanceField(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.InstanceFieldDecl
            ?: error("DECL_INSTANCE_FIELD expects InstanceFieldDecl at $constId")
        val scope = frame.ensureScope()
        val value = frame.slotToObj(slot).byValueCopy()
        scope.addItem(
            decl.name,
            decl.isMutable,
            value,
            decl.visibility,
            decl.writeVisibility,
            recordType = ObjRecord.Type.Field,
            isAbstract = decl.isAbstract,
            isClosed = decl.isClosed,
            isOverride = decl.isOverride,
            isTransient = decl.isTransient
        )
        if (slot >= frame.fn.scopeSlotCount) {
            val localIndex = slot - frame.fn.scopeSlotCount
            val isMutable = frame.fn.localSlotMutables.getOrNull(localIndex) ?: true
            if (isMutable) {
                frame.storeObjResult(slot, ObjVoid)
            }
        }
        return
    }
}

class CmdDeclInstanceProperty(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.InstancePropertyDecl
            ?: error("DECL_INSTANCE_PROPERTY expects InstancePropertyDecl at $constId")
        val scope = frame.ensureScope()
        val prop = frame.storedSlotObj(slot)
        scope.addItem(
            decl.name,
            decl.isMutable,
            prop,
            decl.visibility,
            decl.writeVisibility,
            recordType = ObjRecord.Type.Property,
            isAbstract = decl.isAbstract,
            isClosed = decl.isClosed,
            isOverride = decl.isOverride,
            isTransient = decl.isTransient
        )
        if (slot >= frame.fn.scopeSlotCount) {
            val localIndex = slot - frame.fn.scopeSlotCount
            val isMutable = frame.fn.localSlotMutables.getOrNull(localIndex) ?: true
            if (isMutable) {
                frame.storeObjResult(slot, ObjVoid)
            }
        }
        return
    }
}

class CmdDeclInstanceDelegated(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.InstanceDelegatedDecl
            ?: error("DECL_INSTANCE_DELEGATED expects InstanceDelegatedDecl at $constId")
        val scope = frame.ensureScope()
        var initValue = frame.slotToObj(slot)
        val debugName = if (slot < frame.fn.scopeSlotCount) {
            frame.fn.scopeSlotNames.getOrNull(slot)
        } else {
            val localIndex = slot - frame.fn.scopeSlotCount
            frame.fn.localSlotNames.getOrNull(localIndex)
        }
        if (initValue === ObjUnset) {
            if (debugName != null) {
                val resolved = scope.get(debugName)
                if (resolved != null && resolved.value !== ObjUnset) {
                    initValue = resolved.value
                }
            }
        }
        val accessType = ObjString(decl.accessTypeLabel)
        val finalDelegate = try {
            initValue.invokeInstanceMethod(
                scope,
                "bind",
                Arguments(ObjString(decl.memberName), accessType, scope.thisObj)
            )
        } catch (_: Exception) {
            initValue
        }
        scope.addItem(
            decl.storageName,
            decl.isMutable,
            ObjUnset,
            decl.visibility,
            decl.writeVisibility,
            recordType = ObjRecord.Type.Delegated,
            isAbstract = decl.isAbstract,
            isClosed = decl.isClosed,
            isOverride = decl.isOverride,
            isTransient = decl.isTransient
        ).apply {
            delegate = finalDelegate
        }
        if (slot >= frame.fn.scopeSlotCount) {
            val localIndex = slot - frame.fn.scopeSlotCount
            val isMutable = frame.fn.localSlotMutables.getOrNull(localIndex) ?: true
            if (isMutable) {
                frame.storeObjResult(slot, ObjVoid)
            }
        }
        return
    }
}

class CmdDeclDestructure(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.DestructureDecl
            ?: error("DECL_DESTRUCTURE expects DestructureDecl at $constId")
        val value = frame.slotToObj(slot)
        assignDestructurePattern(frame, decl.pattern, value, decl.pos)
        return
    }
}

class CmdAssignDestructure(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.DestructureAssign
            ?: error("ASSIGN_DESTRUCTURE expects DestructureAssign at $constId")
        val value = frame.slotToObj(slot)
        assignDestructurePattern(frame, decl.pattern, value, decl.pos)
        frame.storeObjResult(slot, value)
        return
    }
}

private suspend fun assignDestructurePattern(frame: CmdFrame, pattern: ListLiteralRef, value: Obj, pos: Pos) {
    val sourceList = (value as? ObjList)?.list
        ?: throw ScriptError(pos, "destructuring assignment requires a list on the right side")

    val entries = pattern.entries()
    val ellipsisIdx = entries.indexOfFirst { it is ListEntry.Spread }
    if (entries.count { it is ListEntry.Spread } > 1) {
        throw ScriptError(pos, "destructuring pattern can have only one splat")
    }

    if (ellipsisIdx < 0) {
        if (sourceList.size < entries.size) {
            throw ScriptError(pos, "too few elements for destructuring")
        }
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry is ListEntry.Element) {
                assignDestructureTarget(frame, entry.ref, sourceList[i], pos)
            }
        }
        return
    }

    val headCount = ellipsisIdx
    val tailCount = entries.size - ellipsisIdx - 1
    if (sourceList.size < headCount + tailCount) {
        throw ScriptError(pos, "too few elements for destructuring")
    }

    for (i in 0 until headCount) {
        val entry = entries[i]
        if (entry is ListEntry.Element) {
            assignDestructureTarget(frame, entry.ref, sourceList[i], pos)
        }
    }

    for (i in 0 until tailCount) {
        val entry = entries[entries.size - 1 - i]
        if (entry is ListEntry.Element) {
            assignDestructureTarget(frame, entry.ref, sourceList[sourceList.size - 1 - i], pos)
        }
    }

    val spreadEntry = entries[ellipsisIdx] as ListEntry.Spread
    val spreadList = sourceList.subList(headCount, sourceList.size - tailCount)
    assignDestructureTarget(frame, spreadEntry.ref, ObjList(spreadList.toMutableList()), pos)
}

private suspend fun assignDestructureTarget(frame: CmdFrame, ref: ObjRef, value: Obj, pos: Pos) {
    when (ref) {
        is ListLiteralRef -> {
            assignDestructurePattern(frame, ref, value, pos)
            return
        }

        is LocalSlotRef -> {
            val index = resolveLocalSlotIndex(frame.fn, ref.name, preferCapture = ref.captureOwnerScopeId != null)
            if (index != null) {
                frame.frame.setObj(index, value)
                return
            }
            val scopeSlot = frame.fn.scopeSlotNames.indexOfFirst { it == ref.name }
            if (scopeSlot >= 0) {
                val target = frame.scopeTarget(scopeSlot)
                val slotIndex = frame.ensureScopeSlot(target, scopeSlot)
                target.setSlotValue(slotIndex, value)
                return
            }
        }

        is LocalVarRef -> {
            val index = resolveLocalSlotIndex(frame.fn, ref.name, preferCapture = false)
            if (index != null) {
                frame.frame.setObj(index, value)
                return
            }
            val scopeSlot = frame.fn.scopeSlotNames.indexOfFirst { it == ref.name }
            if (scopeSlot >= 0) {
                val target = frame.scopeTarget(scopeSlot)
                val slotIndex = frame.ensureScopeSlot(target, scopeSlot)
                target.setSlotValue(slotIndex, value)
                return
            }
        }

        is FastLocalVarRef -> {
            val index = resolveLocalSlotIndex(frame.fn, ref.name, preferCapture = false)
            if (index != null) {
                frame.frame.setObj(index, value)
                return
            }
            val scopeSlot = frame.fn.scopeSlotNames.indexOfFirst { it == ref.name }
            if (scopeSlot >= 0) {
                val target = frame.scopeTarget(scopeSlot)
                val slotIndex = frame.ensureScopeSlot(target, scopeSlot)
                target.setSlotValue(slotIndex, value)
                return
            }
        }

        else -> {}
    }
    ref.setAt(pos, frame.ensureScope(), value)
}

private fun resolveLocalSlotIndex(fn: CmdFunction, name: String, preferCapture: Boolean): Int? {
    val names = fn.localSlotNames
    if (preferCapture) {
        for (i in names.indices) {
            if (names[i] == name && fn.localSlotCaptures.getOrNull(i) == true) return i
        }
    } else {
        for (i in names.indices) {
            if (names[i] == name && fn.localSlotCaptures.getOrNull(i) != true) return i
        }
    }
    for (i in names.indices) {
        if (names[i] == name) return i
    }
    return null
}

class CmdDeclExtProperty(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.ExtensionPropertyDecl
            ?: error("DECL_EXT_PROPERTY expects ExtensionPropertyDecl at $constId")
        val type = frame.ensureScope().resolveExtensionReceiverClass(decl.extTypeName)
        frame.ensureScope().addExtension(
            type,
            decl.property.name,
            ObjRecord(
                decl.property,
                isMutable = false,
                visibility = decl.visibility,
                writeVisibility = decl.setterVisibility,
                declaringClass = null,
                type = ObjRecord.Type.Property
            )
        )
        val getterName = extensionPropertyGetterName(decl.extTypeName, decl.property.name)
        val getterWrapper = ObjExtensionPropertyGetterCallable(decl.property.name, decl.property)
        frame.ensureScope().addItem(getterName, false, getterWrapper, decl.visibility, recordType = ObjRecord.Type.Fun)
        val getterLocal = resolveLocalSlotIndex(frame.fn, getterName, preferCapture = false)
        if (getterLocal != null) {
            frame.setObjUnchecked(frame.fn.scopeSlotCount + getterLocal, getterWrapper)
        }
        if (decl.property.setter != null) {
            val setterName = extensionPropertySetterName(decl.extTypeName, decl.property.name)
            val setterWrapper = ObjExtensionPropertySetterCallable(decl.property.name, decl.property)
            frame.ensureScope()
                .addItem(setterName, false, setterWrapper, decl.visibility, recordType = ObjRecord.Type.Fun)
            val setterLocal = resolveLocalSlotIndex(frame.fn, setterName, preferCapture = false)
            if (setterLocal != null) {
                frame.setObjUnchecked(frame.fn.scopeSlotCount + setterLocal, setterWrapper)
            }
        }
        frame.setObj(slot, decl.property)
        return
    }
}

class CmdCallDirect(
    internal val id: Int,
    internal val argBase: Int,
    internal val argCount: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val ref = frame.fn.constants.getOrNull(id) as? BytecodeConst.ObjRef
            ?: error("CALL_DIRECT expects ObjRef at $id")
        val callee = ref.value
        val args = frame.buildArguments(argBase, argCount)
        if (callee is Statement) {
            val bytecodeBody = (callee as? BytecodeBodyProvider)?.bytecodeBody()
            if (callee !is BytecodeStatement && callee !is BytecodeCallable && bytecodeBody == null) {
                frame.ensureScope().raiseIllegalState("bytecode runtime cannot call non-bytecode Statement")
            }
        }
        val result = if (PerfFlags.SCOPE_POOL) {
            frame.ensureScope().withChildFrame(args) { child -> callee.callOn(child) }
        } else {
            callee.callOn(frame.ensureScope().createChildScope(frame.ensureScope().pos, args = args))
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdCallSlot(
    internal val calleeSlot: Int,
    internal val argBase: Int,
    internal val argCount: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val callee = frame.slotToObj(calleeSlot)
        if (callee === ObjUnset) {
            val name = if (calleeSlot < frame.fn.scopeSlotCount) {
                frame.fn.scopeSlotNames[calleeSlot]
            } else {
                val localIndex = calleeSlot - frame.fn.scopeSlotCount
                frame.fn.localSlotNames.getOrNull(localIndex)
            }
            val message = name?.let { "property '$it' is unset (not initialized)" }
                ?: "property is unset (not initialized) in ${frame.fn.name} at slot $calleeSlot"
            frame.ensureScope().raiseUnset(message)
        }
        val args = frame.buildArguments(argBase, argCount)
        val canPool = PerfFlags.SCOPE_POOL && callee !is Statement
        val result = if (canPool) {
            frame.ensureScope().withChildFrame(args) { child -> callee.callOn(child) }
        } else {
            val scope = frame.ensureScope()
            if (callee is Statement) {
                val bytecodeBody = (callee as? BytecodeBodyProvider)?.bytecodeBody()
                if (callee !is BytecodeStatement && callee !is BytecodeCallable && bytecodeBody == null) {
                    scope.raiseIllegalState("bytecode runtime cannot call non-bytecode Statement")
                }
            }
            callee.callOn(scope.createChildScope(scope.pos, args = args))
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdCallBridgeSlot(
    internal val calleeSlot: Int,
    internal val argBase: Int,
    internal val argCount: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val callee = frame.slotToObj(calleeSlot)
        if (callee === ObjUnset) {
            val name = if (calleeSlot < frame.fn.scopeSlotCount) {
                frame.fn.scopeSlotNames[calleeSlot]
            } else {
                val localIndex = calleeSlot - frame.fn.scopeSlotCount
                frame.fn.localSlotNames.getOrNull(localIndex)
            }
            val message = name?.let { "property '$it' is unset (not initialized)" }
                ?: "property is unset (not initialized) in ${frame.fn.name} at slot $calleeSlot"
            frame.ensureScope().raiseUnset(message)
        }
        if (callee !is net.sergeych.lyng.obj.ObjExternCallable) {
            frame.ensureScope().raiseIllegalState("CALL_BRIDGE_SLOT expects extern callable")
        }
        val args = frame.buildArguments(argBase, argCount)
        val result = if (PerfFlags.SCOPE_POOL) {
            frame.ensureScope().withChildFrame(args) { child -> callee.callOn(child) }
        } else {
            val scope = frame.ensureScope()
            callee.callOn(scope.createChildScope(scope.pos, args = args))
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdListLiteral(
    internal val planId: Int,
    internal val baseSlot: Int,
    internal val count: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val plan = frame.fn.constants.getOrNull(planId) as? BytecodeConst.ListLiteralPlan
            ?: error("LIST_LITERAL expects ListLiteralPlan at $planId")
        val list = ArrayList<Obj>(count)
        for (i in 0 until count) {
            val value = frame.slotToObj(baseSlot + i)
            if (plan.spreads.getOrNull(i) == true) {
                when (value) {
                    is ObjList -> {
                        list.ensureCapacity(list.size + value.list.size)
                        list.addAll(value.list)
                    }

                    else -> frame.ensureScope().raiseError("Spread element must be list")
                }
            } else {
                list.add(value)
            }
        }
        frame.storeObjResult(dst, ObjList(list))
        return
    }
}

class CmdListFillInt(
    internal val sizeSlot: Int,
    internal val callableSlot: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val size = frame.getInt(sizeSlot).toInt()
        if (size < 0) frame.ensureScope().raiseIllegalArgument("list size must be non-negative")
        val callable = frame.storedSlotObj(callableSlot)
        val scope = frame.ensureScope()
        val result = ObjList(LongArray(size))
        for (i in 0 until size) {
            val value = if (callable is BytecodeLambdaCallable && callable.supportsImplicitIntFillFastPath()) {
                callable.invokeImplicitIntArg(scope, i.toLong())
            } else {
                callable.callOn(scope.createChildScope(scope.pos, args = Arguments(ObjInt.of(i.toLong()))))
            }
            val intValue = (value as? ObjInt)?.value ?: scope.raiseClassCastError("expected Int fill result")
            result.setIntAtFast(i, intValue)
        }
        frame.storeObjResult(dst, result)
        return
    }
}

private fun decodeMemberId(id: Int): Pair<Int, Boolean> {
    return if (id <= -2) {
        Pair(-id - 2, true)
    } else {
        Pair(id, false)
    }
}

private suspend fun resolveDynamicFieldValue(scope: Scope, receiver: Obj, name: String, rec: ObjRecord): Obj {
    if (rec.type == ObjRecord.Type.Delegated || rec.value is ObjProperty || rec.type == ObjRecord.Type.Property) {
        val recv = rec.receiver ?: receiver
        return recv.resolveRecord(scope, rec, name, rec.declaringClass).value
    }
    if (rec.receiver != null && rec.declaringClass != null) {
        return rec.receiver!!.resolveRecord(scope, rec, name, rec.declaringClass).value
    }
    if (rec.type == ObjRecord.Type.Fun && !rec.isAbstract) {
        val recv = rec.receiver ?: receiver
        return invokeForFieldReadOrReturnCallable(scope, recv, rec, rec.declaringClass)
    }
    return rec.value
}

class CmdGetMemberSlot(
    internal val recvSlot: Int,
    internal val fieldId: Int,
    internal val methodId: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val scope = frame.ensureScope()
        val receiver = frame.slotToObj(recvSlot)
        val inst = receiver as? ObjInstance
        val cls = receiver as? ObjClass
        val (fieldIdResolved, fieldOnObjClass) = decodeMemberId(fieldId)
        val (methodIdResolved, methodOnObjClass) = decodeMemberId(methodId)
        val fieldRec = if (fieldIdResolved >= 0) {
            when {
                inst != null -> inst.fieldRecordForId(fieldIdResolved)
                    ?: inst.objClass.fieldRecordForId(fieldIdResolved)

                cls != null && fieldOnObjClass -> cls.objClass.fieldRecordForId(fieldIdResolved)
                cls != null -> cls.fieldRecordForId(fieldIdResolved)
                else -> receiver.objClass.fieldRecordForId(fieldIdResolved)
            }
        } else null
        val rec = fieldRec ?: run {
            if (methodIdResolved >= 0) {
                when {
                    inst != null -> inst.methodRecordForId(methodIdResolved) ?: inst.objClass.methodRecordForId(
                        methodIdResolved
                    )

                    cls != null && methodOnObjClass -> cls.objClass.methodRecordForId(methodIdResolved)
                    cls != null -> cls.methodRecordForId(methodIdResolved)
                    else -> receiver.objClass.methodRecordForId(methodIdResolved)
                }
            } else null
        } ?: run {
            val receiverClass = when {
                cls != null && fieldOnObjClass -> cls.objClass
                cls != null -> cls
                else -> receiver.objClass
            }
            val fieldName = if (fieldIdResolved >= 0) {
                receiverClass.fieldSlotMap().entries.firstOrNull { it.value.slot == fieldIdResolved }?.key
            } else null
            val methodName = if (methodIdResolved >= 0) {
                receiverClass.methodSlotMap().entries.firstOrNull { it.value.slot == methodIdResolved }?.key
            } else null
            val memberName = fieldName ?: methodName
            val message = if (memberName != null) {
                "no such member: $memberName on ${receiverClass.className}"
            } else {
                "no such member slot (fieldId=$fieldIdResolved, methodId=$methodIdResolved) on ${receiverClass.className}"
            }
            scope.raiseError(message)
        }
        val rawName = rec.memberName ?: "<member>"
        val name = if (receiver is ObjInstance && rawName.contains("::")) {
            rawName.substringAfterLast("::")
        } else {
            rawName
        }

        suspend fun autoCallIfMethod(resolved: ObjRecord, recv: Obj): Obj {
            return if (resolved.type == ObjRecord.Type.Fun && !resolved.isAbstract) {
                resolved.value.invoke(
                    frame.ensureScope(),
                    resolved.receiver ?: recv,
                    Arguments.EMPTY,
                    resolved.declaringClass
                )
            } else {
                when (val value = resolved.value) {
                    is FrameSlotRef -> value.read()
                    is RecordSlotRef -> value.read(frame.ensureScope(), name)
                    is ScopeSlotRef -> value.read()
                    else -> value
                }
            }
        }
        if (receiver is ObjQualifiedView) {
            val resolved = receiver.readField(frame.ensureScope(), name)
            frame.storeObjResult(dst, autoCallIfMethod(resolved, receiver))
            return
        }
        val resolved = receiver.resolveRecord(frame.ensureScope(), rec, name, rec.declaringClass)
        frame.storeObjResult(dst, autoCallIfMethod(resolved, receiver))
        return
    }
}

class CmdSetMemberSlot(
    internal val recvSlot: Int,
    internal val fieldId: Int,
    internal val methodId: Int,
    internal val valueSlot: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val scope = frame.ensureScope()
        val receiver = frame.slotToObj(recvSlot)
        val inst = receiver as? ObjInstance
        val cls = receiver as? ObjClass
        val (fieldIdResolved, fieldOnObjClass) = decodeMemberId(fieldId)
        val (methodIdResolved, methodOnObjClass) = decodeMemberId(methodId)
        val fieldRec = if (fieldIdResolved >= 0) {
            when {
                inst != null -> inst.fieldRecordForId(fieldIdResolved)
                    ?: inst.objClass.fieldRecordForId(fieldIdResolved)

                cls != null && fieldOnObjClass -> cls.objClass.fieldRecordForId(fieldIdResolved)
                cls != null -> cls.fieldRecordForId(fieldIdResolved)
                else -> receiver.objClass.fieldRecordForId(fieldIdResolved)
            }
        } else null
        val rec = fieldRec ?: run {
            if (methodIdResolved >= 0) {
                when {
                    inst != null -> inst.methodRecordForId(methodIdResolved) ?: inst.objClass.methodRecordForId(
                        methodIdResolved
                    )

                    cls != null && methodOnObjClass -> cls.objClass.methodRecordForId(methodIdResolved)
                    cls != null -> cls.methodRecordForId(methodIdResolved)
                    else -> receiver.objClass.methodRecordForId(methodIdResolved)
                }
            } else null
        } ?: run {
            val receiverClass = when {
                cls != null && fieldOnObjClass -> cls.objClass
                cls != null -> cls
                else -> receiver.objClass
            }
            val fieldName = if (fieldIdResolved >= 0) {
                receiverClass.fieldSlotMap().entries.firstOrNull { it.value.slot == fieldIdResolved }?.key
            } else null
            val methodName = if (methodIdResolved >= 0) {
                receiverClass.methodSlotMap().entries.firstOrNull { it.value.slot == methodIdResolved }?.key
            } else null
            val memberName = fieldName ?: methodName
            val message = if (memberName != null) {
                "no such member: $memberName on ${receiverClass.className}"
            } else {
                "no such member slot (fieldId=$fieldIdResolved, methodId=$methodIdResolved) on ${receiverClass.className}"
            }
            scope.raiseError(message)
        }
        val rawName = rec.memberName ?: "<member>"
        val name = if (receiver is ObjInstance && rawName.contains("::")) {
            rawName.substringAfterLast("::")
        } else {
            rawName
        }
        if (receiver is ObjQualifiedView) {
            receiver.writeField(frame.ensureScope(), name, frame.slotToObj(valueSlot))
            return
        }
        receiver.writeField(frame.ensureScope(), name, frame.slotToObj(valueSlot))
        return
    }
}

class CmdGetClassScope(
    internal val classSlot: Int,
    internal val nameId: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val nameConst = frame.fn.constants.getOrNull(nameId) as? BytecodeConst.StringVal
            ?: error("GET_CLASS_SCOPE expects StringVal at $nameId")
        val scope = frame.ensureScope()
        val cls = frame.slotToObj(classSlot) as? ObjClass
            ?: scope.raiseSymbolNotFound(nameConst.value)
        val name = nameConst.value
        var rec: ObjRecord? = null
        var decl: ObjClass? = null
        for (c in cls.mro) {
            if (c.className == "Obj") break
            val candidate = c.classScope?.objects?.get(name) ?: c.members[name]
            if (candidate == null || candidate.isAbstract) continue
            val declared = candidate.declaringClass ?: c
            if (!canAccessMember(candidate.visibility, declared, scope.currentClassCtx, name)) {
                scope.raiseError(
                    ObjIllegalAccessException(
                        scope,
                        "can't access field ${name}: not visible (declared in ${declared.className}, caller ${scope.currentClassCtx?.className ?: "?"})"
                    )
                )
            }
            rec = candidate
            decl = declared
            break
        }
        val resolved = rec ?: scope.raiseSymbolNotFound(name)
        val declClass = decl ?: cls
        val resolvedRec = cls.resolveRecord(scope, resolved, name, declClass)
        val value = resolvedRec.value
        frame.storeObjResult(dst, value)
        return
    }
}

class CmdSetClassScope(
    internal val classSlot: Int,
    internal val nameId: Int,
    internal val valueSlot: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val nameConst = frame.fn.constants.getOrNull(nameId) as? BytecodeConst.StringVal
            ?: error("SET_CLASS_SCOPE expects StringVal at $nameId")
        val scope = frame.ensureScope()
        val cls = frame.slotToObj(classSlot) as? ObjClass
            ?: scope.raiseSymbolNotFound(nameConst.value)
        cls.writeField(scope, nameConst.value, frame.slotToObj(valueSlot))
        return
    }
}

class CmdGetDynamicMember(
    internal val recvSlot: Int,
    internal val nameId: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val nameConst = frame.fn.constants.getOrNull(nameId) as? BytecodeConst.StringVal
            ?: error("GET_DYNAMIC_MEMBER expects StringVal at $nameId")
        val scope = frame.ensureScope()
        val receiver = frame.slotToObj(recvSlot)
        val rec = receiver.readField(scope, nameConst.value)
        val value = resolveDynamicFieldValue(scope, receiver, nameConst.value, rec)
        frame.storeObjResult(dst, value)
        return
    }
}

class CmdSetDynamicMember(
    internal val recvSlot: Int,
    internal val nameId: Int,
    internal val valueSlot: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val nameConst = frame.fn.constants.getOrNull(nameId) as? BytecodeConst.StringVal
            ?: error("SET_DYNAMIC_MEMBER expects StringVal at $nameId")
        val scope = frame.ensureScope()
        val receiver = frame.slotToObj(recvSlot)
        receiver.writeField(scope, nameConst.value, frame.slotToObj(valueSlot))
        return
    }
}

class CmdCallDynamicMember(
    internal val recvSlot: Int,
    internal val nameId: Int,
    internal val argBase: Int,
    internal val argCount: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val nameConst = frame.fn.constants.getOrNull(nameId) as? BytecodeConst.StringVal
            ?: error("CALL_DYNAMIC_MEMBER expects StringVal at $nameId")
        val scope = frame.ensureScope()
        val receiver = frame.slotToObj(recvSlot)
        val callArgs = frame.buildArguments(argBase, argCount)
        val result = receiver.invokeInstanceMethod(scope, nameConst.value, callArgs)
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdCallMemberSlot(
    internal val recvSlot: Int,
    internal val methodId: Int,
    internal val argBase: Int,
    internal val argCount: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val receiver = frame.slotToObj(recvSlot)
        val inst = receiver as? ObjInstance
        val cls = receiver as? ObjClass
        val (methodIdResolved, methodOnObjClass) = decodeMemberId(methodId)
        val rec = inst?.methodRecordForId(methodIdResolved)
            ?: when {
                cls != null && methodOnObjClass -> cls.objClass.methodRecordForId(methodIdResolved)
                cls != null -> cls.methodRecordForId(methodIdResolved)
                else -> receiver.objClass.methodRecordForId(methodIdResolved)
            }
            ?: frame.ensureScope().raiseError("member id $methodId not found on ${receiver.objClass.className}")
        val callArgs = frame.buildArguments(argBase, argCount)
        val rawName = rec.memberName ?: "<member>"
        val name = if (receiver is ObjInstance && rawName.contains("::")) {
            rawName.substringAfterLast("::")
        } else {
            rawName
        }
        if (receiver is ObjQualifiedView) {
            val result = receiver.invokeInstanceMethod(frame.ensureScope(), name, callArgs)
            frame.storeObjResult(dst, result)
            return
        }
        val scope = frame.ensureScope()
        val decl = rec.declaringClass ?: receiver.objClass
        if (!canAccessMember(rec.visibility, decl, scope.currentClassCtx, name)) {
            scope.raiseError(
                ObjIllegalAccessException(
                    scope,
                    "can't invoke ${name}: not visible (declared in ${decl.className}, caller ${scope.currentClassCtx?.className ?: "?"})"
                )
            )
        }
        val result = when (rec.type) {
            ObjRecord.Type.Property -> {
                if (callArgs.isEmpty()) (rec.value as ObjProperty).callGetter(scope, receiver, decl)
                else scope.raiseError("property $name cannot be called with arguments")
            }

            ObjRecord.Type.Fun -> {
                val callScope = inst?.instanceScope ?: scope
                rec.value.invoke(callScope, receiver, callArgs, decl)
            }

            ObjRecord.Type.Delegated -> {
                val delegate = when (receiver) {
                    is ObjInstance -> {
                        val storageName = decl.mangledName(name)
                        var del = receiver.instanceScope[storageName]?.delegate ?: rec.delegate
                        if (del == null) {
                            for (c in receiver.objClass.mro) {
                                del = receiver.instanceScope[c.mangledName(name)]?.delegate
                                if (del != null) break
                            }
                        }
                        del
                            ?: scope.raiseError("Internal error: delegated member $name has no delegate (tried $storageName)")
                    }

                    is ObjClass -> rec.delegate
                        ?: scope.raiseError("Internal error: delegated member $name has no delegate")

                    else -> rec.delegate ?: scope.raiseError("Internal error: delegated member $name has no delegate")
                }
                val allArgs = (listOf(receiver, ObjString(name)) + callArgs.list).toTypedArray()
                delegate.invokeInstanceMethod(scope, "invoke", Arguments(*allArgs), onNotFoundResult = {
                    val propVal = delegate.invokeInstanceMethod(scope, "getValue", Arguments(receiver, ObjString(name)))
                    propVal.invoke(scope, receiver, callArgs, decl)
                })
            }

            else -> frame.ensureScope().raiseError("member $name is not callable")
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdGetIndex(
    internal val targetSlot: Int,
    internal val indexSlot: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val target = frame.storedSlotObj(targetSlot)
        val index = frame.storedSlotObj(indexSlot)
        if (target is ObjList && target::class == ObjList::class && index is ObjInt) {
            val i = index.toInt()
            objListBoundsViolationMessageOrNull(target.sizeFast(), i)?.let {
                frame.ensureScope().raiseIndexOutOfBounds(it)
            }
            frame.storeObjResult(dst, target.getObjAtFast(i))
            return
        }
        val result = target.getAt(frame.ensureScope(), index)
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdSetIndex(
    internal val targetSlot: Int,
    internal val indexSlot: Int,
    internal val valueSlot: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val target = frame.storedSlotObj(targetSlot)
        val index = frame.storedSlotObj(indexSlot)
        val value = frame.slotToObj(valueSlot)
        if (target is ObjList && target::class == ObjList::class && index is ObjInt) {
            val i = index.toInt()
            objListBoundsViolationMessageOrNull(target.sizeFast(), i)?.let {
                frame.ensureScope().raiseIndexOutOfBounds(it)
            }
            target.setObjAtFast(i, value)
            return
        }
        target.putAt(frame.ensureScope(), index, value)
        return
    }
}

class CmdGetIndexInt(
    internal val targetSlot: Int,
    internal val indexSlot: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val target = frame.storedSlotObj(targetSlot)
        val index = frame.getInt(indexSlot).toInt()
        if (target is ObjList && target::class == ObjList::class) {
            objListBoundsViolationMessageOrNull(target.sizeFast(), index)?.let {
                frame.ensureScope().raiseIndexOutOfBounds(it)
            }
            target.getIntAtFast(index)?.let {
                frame.setInt(dst, it)
                return
            }
        }
        val result = target.getAt(frame.ensureScope(), ObjInt.of(index.toLong()))
        if (result is ObjInt) {
            frame.setInt(dst, result.value)
            return
        }
        frame.ensureScope().raiseClassCastError("expected Int list element")
    }
}

class CmdSetIndexInt(
    internal val targetSlot: Int,
    internal val indexSlot: Int,
    internal val valueSlot: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val target = frame.storedSlotObj(targetSlot)
        val index = frame.getInt(indexSlot).toInt()
        if (target is ObjList && target::class == ObjList::class) {
            objListBoundsViolationMessageOrNull(target.sizeFast(), index)?.let {
                frame.ensureScope().raiseIndexOutOfBounds(it)
            }
            target.setIntAtFast(index, frame.getInt(valueSlot))
            return
        }
        val value = ObjInt.of(frame.getInt(valueSlot))
        target.putAt(frame.ensureScope(), ObjInt.of(index.toLong()), value)
        return
    }
}

class CmdMakeLambda(internal val id: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val lambdaConst = frame.fn.constants.getOrNull(id) as? BytecodeConst.LambdaFn
            ?: error("MAKE_LAMBDA_FN expects LambdaFn at $id")
        val scope = frame.ensureScope()
        val captureRecords = lambdaConst.captureTableId?.let { frame.buildCaptureRecords(it, lambdaConst.captureNames) }
        val stmt = BytecodeLambdaCallable(
            fn = lambdaConst.fn,
            closureScope = scope,
            captureRecords = captureRecords,
            captureNames = lambdaConst.captureNames,
            paramSlotPlan = lambdaConst.paramSlotPlan,
            argsDeclaration = lambdaConst.argsDeclaration,
            preferredThisType = lambdaConst.preferredThisType,
            returnLabels = lambdaConst.returnLabels,
            pos = lambdaConst.pos
        )
        val callable: Obj = if (lambdaConst.wrapAsExtensionCallable) {
            ObjExtensionMethodCallable("<lambda>", stmt)
        } else {
            stmt
        }
        frame.storeObjResult(dst, callable.asReadonly.value)
        return
    }
}

class BytecodeLambdaCallable(
    private val fn: CmdFunction,
    private val closureScope: Scope,
    private val captureRecords: List<ObjRecord>?,
    private val captureNames: List<String>,
    private val paramSlotPlan: Map<String, Int>,
    private val argsDeclaration: ArgsDeclaration?,
    private val preferredThisType: String?,
    private val returnLabels: Set<String>,
    override val pos: Pos,
) : Statement(), BytecodeCallable {
    private fun freezeRecord(record: ObjRecord): ObjRecord {
        if (record.isMutable) return record
        val raw = record.value as Obj?
        return when (raw) {
            is net.sergeych.lyng.FrameSlotRef -> raw.resolvedCaptureValueOrNull()?.let { record.copy(value = it) } ?: record
            is net.sergeych.lyng.RecordSlotRef -> raw.resolvedCaptureValueOrNull()?.let { record.copy(value = it) } ?: record
            is net.sergeych.lyng.ScopeSlotRef -> raw.resolvedCaptureValueOrNull()?.let { record.copy(value = it) } ?: record
            null -> record
            else -> record.copy()
        }
    }

    private fun resolveCaptureRecords(base: Scope): List<ObjRecord>? {
        if (captureNames.isEmpty()) return null
        return captureNames.map { name ->
            base.chainLookupIgnoreClosure(
                name,
                followClosure = true,
                caller = base.currentClassCtx
            ) ?: base.raiseSymbolNotFound("symbol $name not found")
        }
    }

    fun rebindClosure(newClosureScope: Scope): BytecodeLambdaCallable {
        return BytecodeLambdaCallable(
            fn = fn,
            closureScope = newClosureScope,
            captureRecords = captureRecords ?: resolveCaptureRecords(newClosureScope),
            captureNames = captureNames,
            paramSlotPlan = paramSlotPlan,
            argsDeclaration = argsDeclaration,
            preferredThisType = preferredThisType,
            returnLabels = returnLabels,
            pos = pos
        )
    }

    fun freezeForLaunch(newClosureScope: Scope): BytecodeLambdaCallable {
        val frozenCaptures = captureRecords?.map(::freezeRecord)
            ?: resolveCaptureRecords(newClosureScope)?.map(::freezeRecord)
        return BytecodeLambdaCallable(
            fn = fn,
            closureScope = newClosureScope,
            captureRecords = frozenCaptures,
            captureNames = captureNames,
            paramSlotPlan = paramSlotPlan,
            argsDeclaration = argsDeclaration,
            preferredThisType = preferredThisType,
            returnLabels = returnLabels,
            pos = pos
        )
    }

    fun supportsImplicitIntFillFastPath(): Boolean = argsDeclaration == null

    suspend fun invokeImplicitIntArg(scope: Scope, arg: Long): Obj {
        val context = scope.applyClosureForBytecode(closureScope, preferredThisType).also {
            it.args = Arguments.EMPTY
        }
        if (captureRecords != null) {
            context.captureRecords = captureRecords
            context.captureNames = captureNames
        } else if (captureNames.isNotEmpty()) {
            closureScope.raiseIllegalState("bytecode lambda capture records missing")
        }
        val binder: suspend (CmdFrame, Arguments) -> Unit = { frame, _ ->
            paramSlotPlan["it"]?.let { itSlot ->
                frame.frame.setInt(itSlot, arg)
            }
        }
        return try {
            CmdVm().execute(fn, context, Arguments.EMPTY, binder)
        } catch (e: ReturnException) {
            if (e.label == null || returnLabels.contains(e.label)) e.result
            else throw e
        }
    }

    override suspend fun execute(scope: Scope): Obj {
        val context = scope.applyClosureForBytecode(closureScope, preferredThisType).also {
            it.args = scope.args
        }
        if (captureRecords != null) {
            context.captureRecords = captureRecords
            context.captureNames = captureNames
        } else if (captureNames.isNotEmpty()) {
            closureScope.raiseIllegalState("bytecode lambda capture records missing")
        }
        if (argsDeclaration == null) {
            // Bound in the bytecode entry binder.
        } else {
            // args bound into frame slots in the bytecode entry binder
        }
        return try {
            val declaredNames = fn.constants
                .mapNotNull { it as? BytecodeConst.LocalDecl }
                .mapTo(mutableSetOf()) { it.name }
            val binder: suspend (CmdFrame, Arguments) -> Unit = { frame, arguments ->
                val slotPlan = fn.localSlotPlanByName()
                if (argsDeclaration == null) {
                    val l = arguments.list
                    val itValue: Obj = when (l.size) {
                        0 -> ObjVoid
                        1 -> l[0]
                        else -> ObjList(l.toMutableList())
                    }
                    val itSlot = slotPlan["it"]
                    if (itSlot != null) {
                        when (itValue) {
                            is ObjInt -> frame.frame.setInt(itSlot, itValue.value)
                            is ObjReal -> frame.frame.setReal(itSlot, itValue.value)
                            is ObjBool -> frame.frame.setBool(itSlot, itValue.value)
                            else -> frame.frame.setObj(itSlot, itValue)
                        }
                    }
                } else {
                    argsDeclaration.assignToFrame(
                        context,
                        arguments,
                        slotPlan,
                        frame.frame
                    )
                }
                val localNames = frame.fn.localSlotNames
                for (i in localNames.indices) {
                    val name = localNames[i] ?: continue
                    if (declaredNames.contains(name)) continue
                    val slotType = frame.getLocalSlotTypeCode(i)
                    if (slotType != SlotType.UNKNOWN.code && slotType != SlotType.OBJ.code) {
                        continue
                    }
                    if (slotType == SlotType.OBJ.code && frame.frame.getRawObj(i) != null) {
                        continue
                    }
                    val record = context.getLocalRecordDirect(name)
                        ?: context.parent?.get(name)
                        ?: context.get(name)
                        ?: continue
                    val value =
                        if (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || record.value is ObjProperty) {
                            context.resolve(record, name)
                        } else {
                            when (val direct = record.value) {
                                is FrameSlotRef -> direct.read()
                                is RecordSlotRef -> direct.read(context, name)
                                is ScopeSlotRef -> direct.read()
                                else -> direct
                            }
                        }
                    frame.frame.setObj(i, value)
                }
            }
            CmdVm().execute(fn, context, scope.args, binder)
        } catch (e: ReturnException) {
            if (e.label == null || returnLabels.contains(e.label)) e.result else throw e
        }
    }
}

class CmdIterPush(internal val iterSlot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.pushIterator(frame.slotToObj(iterSlot))
        return
    }
}

class CmdIterPop : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.popIterator()
        return
    }
}

class CmdIterCancel : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.cancelTopIterator()
        return
    }
}

class CmdFrame(
    val vm: CmdVm,
    val fn: CmdFunction,
    scope0: Scope,
    args: List<Obj>,
) {
    companion object {
        private const val ARG_PLAN_FLAG = 0x8000
        private const val ARG_PLAN_MASK = 0x7FFF
    }

    var ip: Int = 0
    var scope: Scope = scope0
    private val moduleScope: Scope = resolveModuleScope(scope0)
    private val scopeSlotNames: Set<String> = fn.scopeSlotNames.filterNotNull().toSet()
    private var lastScopePosIp = -1

    internal val scopeStack = ArrayDeque<Scope>()
    internal val scopeVirtualStack = ArrayDeque<Boolean>()
    internal val slotPlanStack = ArrayDeque<Map<String, Int?>>()
    internal val slotPlanScopeStack = ArrayDeque<Boolean>()
    private val captureStack = ArrayDeque<List<String>>()
    private var scopeDepth = 0
    private var virtualDepth = 0
    private val iterStack = ArrayDeque<Obj>()

    internal data class TryHandler(
        val exceptionSlot: Int,
        val catchIp: Int,
        val finallyIp: Int,
        val iterDepthAtPush: Int,
        var inCatch: Boolean = false
    )

    internal val tryStack = ArrayDeque<TryHandler>()
    private var pendingThrowable: Throwable? = null

    internal val frame: BytecodeFrame
    private val addrScopes: Array<Scope?> = arrayOfNulls(fn.addrCount)
    private val addrIndices: IntArray = IntArray(fn.addrCount)
    private val addrScopeSlots: IntArray = IntArray(fn.addrCount)

    init {
        frame = resolveFrame(scope0, fn, args)
        for (i in args.indices) {
            if (i >= frame.slotCount - frame.argBase) break
            frame.setObj(frame.argBase + i, args[i])
        }
    }

    private fun resolveFrame(scope: Scope, fn: CmdFunction, args: List<Obj>): BytecodeFrame {
        val moduleScope = scope as? ModuleScope
        if (moduleScope != null && args.isEmpty()) {
            return moduleScope.ensureModuleFrame(fn)
        }
        return BytecodeFrame(fn.localCount, args.size)
    }

    internal fun getLocalSlotTypeCode(localIndex: Int): Byte = frame.getSlotTypeCode(localIndex)
    internal fun readLocalObj(localIndex: Int): Obj {
        return when (frame.getSlotTypeCode(localIndex)) {
            SlotType.INT.code -> ObjInt.of(frame.getInt(localIndex))
            SlotType.REAL.code -> ObjReal.of(frame.getReal(localIndex))
            SlotType.BOOL.code -> if (frame.getBool(localIndex)) ObjTrue else ObjFalse
            SlotType.OBJ.code -> {
                val obj = frame.getObj(localIndex)
                when (obj) {
                    is FrameSlotRef -> obj.read()
                    is RecordSlotRef -> obj.read()
                    else -> obj
                }
            }

            else -> {
                val obj = frame.getObj(localIndex)
                when (obj) {
                    is FrameSlotRef -> obj.read()
                    is RecordSlotRef -> obj.read()
                    else -> obj
                }
            }
        }
    }

    internal fun isFastLocalSlot(slot: Int): Boolean {
        if (slot < fn.scopeSlotCount) return false
        val localIndex = slot - fn.scopeSlotCount
        return fn.localSlotCaptures.getOrNull(localIndex) != true
    }

    internal fun getLocalObjIntValue(localIndex: Int): Long {
        return when (frame.getSlotTypeCode(localIndex)) {
            SlotType.INT.code -> frame.getInt(localIndex)
            SlotType.OBJ.code -> (frame.getObj(localIndex) as ObjInt).value
            else -> error("expected ObjInt/INT in local slot $localIndex")
        }
    }

    internal fun getLocalObjRealValue(localIndex: Int): Double {
        return when (frame.getSlotTypeCode(localIndex)) {
            SlotType.REAL.code -> frame.getReal(localIndex)
            SlotType.INT.code -> frame.getInt(localIndex).toDouble()
            SlotType.OBJ.code -> (frame.getObj(localIndex) as ObjReal).value
            else -> error("expected ObjReal/REAL in local slot $localIndex")
        }
    }

    internal fun applyCaptureRecords() {
        val captureRecords = scope.captureRecords ?: return
        val captureNames = scope.captureNames ?: return
        val localNames = fn.localSlotNames
        if (localNames.isEmpty()) return
        for (i in captureNames.indices) {
            val name = captureNames[i]
            val record = captureRecords.getOrNull(i) ?: continue
            var localIndex = -1
            for (idx in localNames.indices) {
                if (localNames[idx] != name) continue
                if (fn.localSlotCaptures.getOrNull(idx) != true) continue
                localIndex = idx
                break
            }
            if (localIndex < 0) {
                for (idx in localNames.indices) {
                    if (localNames[idx] != name) continue
                    localIndex = idx
                    break
                }
            }
            if (localIndex < 0) continue
            if (record.type == ObjRecord.Type.Delegated) {
                frame.setObj(localIndex, record.delegate ?: ObjNull)
            } else {
                val value = record.value
                if (!record.isMutable && value is FrameSlotRef) {
                    val resolved = value.peekValue()
                    if (resolved != null) {
                        if (value.refersTo(frame, localIndex)) continue
                        frame.setObj(localIndex, value.read())
                    } else {
                        frame.setObj(localIndex, value)
                    }
                } else if (!record.isMutable && value is RecordSlotRef) {
                    val resolved = value.peekValue()
                    if (resolved != null) {
                        frame.setObj(localIndex, value.read())
                    } else {
                        frame.setObj(localIndex, value)
                    }
                } else if (!record.isMutable && value is ScopeSlotRef) {
                    val resolved = value.peekValue()
                    if (resolved != null) {
                        frame.setObj(localIndex, value.read())
                    } else {
                        frame.setObj(localIndex, value)
                    }
                } else if (!record.isMutable) {
                    frame.setObj(localIndex, value)
                } else if (value is FrameSlotRef) {
                    if (value.refersTo(frame, localIndex)) continue
                    frame.setObj(localIndex, value)
                } else {
                    frame.setObj(localIndex, RecordSlotRef(record))
                }
            }
            for (idx in localNames.indices) {
                if (idx == localIndex) continue
                if (localNames[idx] != name) continue
                if (fn.localSlotCaptures.getOrNull(idx) == true) continue
                if (record.type == ObjRecord.Type.Delegated) {
                    frame.setObj(idx, record.delegate ?: ObjNull)
                } else {
                    val value = record.value
                    if (!record.isMutable && value is FrameSlotRef) {
                        val resolved = value.peekValue()
                        if (resolved != null) {
                            frame.setObj(idx, value.read())
                        } else {
                            frame.setObj(idx, value)
                        }
                    } else if (!record.isMutable && value is RecordSlotRef) {
                        val resolved = value.peekValue()
                        if (resolved != null) {
                            frame.setObj(idx, value.read())
                        } else {
                            frame.setObj(idx, value)
                        }
                    } else if (!record.isMutable && value is ScopeSlotRef) {
                        val resolved = value.peekValue()
                        if (resolved != null) {
                            frame.setObj(idx, value.read())
                        } else {
                            frame.setObj(idx, value)
                        }
                    } else if (!record.isMutable) {
                        frame.setObj(idx, value)
                    } else if (value is FrameSlotRef) {
                        frame.setObj(idx, value)
                    } else {
                        frame.setObj(idx, RecordSlotRef(record))
                    }
                }
            }
        }
    }

    internal fun buildCaptureRecords(captureTableId: Int, captureNames: List<String>? = null): List<ObjRecord> {
        val table = fn.constants.getOrNull(captureTableId) as? BytecodeConst.CaptureTable
            ?: error("Capture table $captureTableId missing")
        return table.entries.mapIndexed { index, entry ->
            when (entry.ownerKind) {
                CaptureOwnerFrameKind.LOCAL -> {
                    val localIndex = entry.slotIndex - fn.scopeSlotCount
                    if (localIndex < 0) {
                        error("Invalid local capture slot ${entry.slotIndex}")
                    }
                    val name = captureNames?.getOrNull(index)
                    if (name != null) {
                        val inherited = findInheritedCaptureRecord(scope, name)
                        if (inherited != null) {
                            val copied = ObjRecord(
                                value = inherited.value,
                                isMutable = inherited.isMutable,
                                visibility = inherited.visibility,
                                isTransient = inherited.isTransient,
                                type = inherited.type
                            )
                            copied.delegate = inherited.delegate
                            return@mapIndexed copied
                        }
                    }
                    val isMutable = fn.localSlotMutables.getOrNull(localIndex) ?: false
                    val isDelegated = fn.localSlotDelegated.getOrNull(localIndex) ?: false
                    if (isDelegated) {
                        val delegate = frame.getObj(localIndex)
                        ObjRecord(ObjNull, isMutable, type = ObjRecord.Type.Delegated).also {
                            it.delegate = delegate
                        }
                    } else {
                        val raw = frame.getRawObj(localIndex)
                        if (raw == null && name != null) {
                            val record = findNamedExistingRecord(scope, name)
                            if (record != null) {
                                val value = record.value
                                return@mapIndexed when (value) {
                                    is FrameSlotRef -> ObjRecord(value, isMutable)
                                    is RecordSlotRef -> ObjRecord(value, isMutable)
                                    else -> ObjRecord(value, isMutable)
                                }
                            }
                            if (hasNamedScopeBinding(scope, name)) {
                                throw ScriptError(
                                    ensureScope().pos,
                                    "captured binding '$name' is not available in the execution scope; prepare the script imports/module bindings explicitly"
                                )
                            }
                        }
                        when (raw) {
                            is FrameSlotRef -> ObjRecord(raw, isMutable)
                            is RecordSlotRef -> ObjRecord(raw, isMutable)
                            else -> ObjRecord(FrameSlotRef(frame, localIndex), isMutable)
                        }
                    }
                }

                CaptureOwnerFrameKind.MODULE -> {
                    val slotId = entry.slotIndex
                    val target = moduleScope
                    val name = captureNames?.getOrNull(index)
                    if (name != null) {
                        findNamedExistingRecord(target, name)?.let { return@mapIndexed it }
                        // Fallback to current scope in case the module scope isn't in the parent chain
                        // or doesn't carry the imported symbol yet.
                        findNamedExistingRecord(scope, name)?.let { return@mapIndexed it }
                        findInheritedCaptureRecord(scope, name)?.let { return@mapIndexed it }
                    }
                    if (slotId < target.slotCount) {
                        val existing = target.getSlotRecord(slotId)
                        if (name == null || existing.value !== ObjUnset || hasResolvedNamedScopeBinding(target, name)) {
                            return@mapIndexed existing
                        }
                    }
                    if (name != null) {
                        throw ScriptError(
                            ensureScope().pos,
                            "module capture '$name' is not available in the execution scope; prepare the script imports/module bindings explicitly"
                        )
                    }
                    throw ScriptError(
                        ensureScope().pos,
                        "missing module capture slot $slotId"
                    )
                }
            }
        }
    }

    private fun shouldSyncLocalCaptures(captures: List<String>): Boolean {
        if (captures.isEmpty()) return false
        val localNames = fn.localSlotNames
        if (localNames.isEmpty()) return false
        for (capture in captures) {
            for (local in localNames) {
                if (local == null) continue
                if (local == capture) return true
            }
        }
        return false
    }

    private fun resolveModuleScope(scope: Scope): Scope {
        val moduleSlotName = fn.scopeSlotNames.indices
            .firstOrNull { fn.scopeSlotIsModule.getOrNull(it) == true }
            ?.let { fn.scopeSlotNames[it] }
        if (moduleSlotName != null) {
            findModuleScope(scope)?.let { return it }
            val bySlot = findScopeWithSlot(scope, moduleSlotName)
            if (bySlot is ModuleScope) return bySlot
            val bySlotParent = bySlot?.parent
            if (bySlotParent is ModuleScope) return bySlotParent
            val byRecord = findScopeWithRecord(scope, moduleSlotName)
            if (byRecord is ModuleScope) return byRecord
            val byRecordParent = byRecord?.parent
            if (byRecordParent is ModuleScope) return byRecordParent
            return scope
        }
        findModuleScope(scope)?.let { return it }
        return scope
    }

    private fun findScopeWithSlot(scope: Scope, slotName: String): Scope? {
        val visited = HashSet<Scope>(16)
        val queue = ArrayDeque<Scope>()
        queue.add(scope)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            if (current.getSlotIndexOf(slotName) != null) return current
            current.parent?.let { queue.add(it) }
            if (current is BytecodeClosureScope) {
                queue.add(current.closureScope)
            } else if (current is ApplyScope) {
                queue.add(current.applied)
            }
        }
        return null
    }

    private fun findScopeWithRecord(scope: Scope, name: String): Scope? {
        val visited = HashSet<Scope>(16)
        val queue = ArrayDeque<Scope>()
        queue.add(scope)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            if (current.getLocalRecordDirect(name) != null) return current
            current.parent?.let { queue.add(it) }
            if (current is BytecodeClosureScope) {
                queue.add(current.closureScope)
            } else if (current is ApplyScope) {
                queue.add(current.applied)
            }
        }
        return null
    }

    private fun findModuleScope(scope: Scope): Scope? {
        val visited = HashSet<Scope>(16)
        val queue = ArrayDeque<Scope>()
        queue.add(scope)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            if (current is ModuleScope) return current
            if (current.parent is ModuleScope) return current.parent
            current.parent?.let { queue.add(it) }
            if (current is BytecodeClosureScope) {
                queue.add(current.closureScope)
            } else if (current is ApplyScope) {
                queue.add(current.applied)
            }
        }
        return null
    }

    fun ensureScope(): Scope {
        val pos = currentErrorPos()
        if (pos != null && lastScopePosIp != ip) {
            scope.pos = pos
            lastScopePosIp = ip
        }
        return scope
    }

    suspend fun normalizeThrowable(t: Throwable): Throwable {
        if (t is ExecutionError || t is ReturnException || t is LoopBreakContinueException) return t
        val parentScope = ensureScope()
        val pos = (t as? ScriptError)?.pos ?: currentErrorPos() ?: parentScope.pos
        val throwScope = parentScope.createChildScope(pos = pos)
        val message = when (t) {
            is ScriptError -> t.errorMessage
            else -> t.message ?: t.toString()
        }
        val errorObject = ObjUnknownException(throwScope, message).apply { getStackTrace() }
        return ExecutionError(errorObject, pos, message, t)
    }

    suspend fun handleException(t: Throwable): Boolean {
        val handler = tryStack.lastOrNull() ?: return false
        vmIterDebug {
            "handleException fn=${fn.name} throwable=${t::class.simpleName} message=${t.message} catchIp=${handler.catchIp} finallyIp=${handler.finallyIp} iterDepth=${iterStack.size}"
        }
        val finallyIp = handler.finallyIp
        if (t is ReturnException || t is LoopBreakContinueException) {
            if (finallyIp >= 0) {
                cancelIteratorsToDepth(handler.iterDepthAtPush, "handleException:returnOrLoop->finally")
                pendingThrowable = t
                ip = finallyIp
                return true
            }
            return false
        }
        if (handler.inCatch) {
            if (finallyIp >= 0) {
                cancelIteratorsToDepth(handler.iterDepthAtPush, "handleException:inCatch->finally")
                pendingThrowable = t
                ip = finallyIp
                return true
            }
            return false
        }
        handler.inCatch = true
        pendingThrowable = t
        if (handler.catchIp >= 0) {
            cancelIteratorsToDepth(handler.iterDepthAtPush, "handleException:toCatch")
            val caughtObj = when (t) {
                is ExecutionError -> t.errorObject
                else -> ObjUnknownException(ensureScope(), t.message ?: t.toString())
            }
            storeObjResult(handler.exceptionSlot, caughtObj)
            ip = handler.catchIp
            return true
        }
        if (finallyIp >= 0) {
            cancelIteratorsToDepth(handler.iterDepthAtPush, "handleException:toFinallyNoCatch")
            ip = finallyIp
            return true
        }
        return false
    }

    fun pushTry(exceptionSlot: Int, catchIp: Int, finallyIp: Int) {
        tryStack.addLast(TryHandler(exceptionSlot, catchIp, finallyIp, iterDepthAtPush = iterStack.size))
    }

    fun popTry() {
        if (tryStack.isNotEmpty()) {
            tryStack.removeLast()
        }
    }

    fun clearPendingThrowable() {
        pendingThrowable = null
    }

    fun rethrowPending() {
        val t = pendingThrowable ?: return
        pendingThrowable = null
        throw t
    }

    private fun posForIp(ip: Int): Pos? {
        if (ip < 0) return null
        return fn.posByIp.getOrNull(ip)
    }

    private fun currentErrorPos(): Pos? {
        val center = ip - 1
        if (center < 0) return null
        var fallback: Pos? = null
        val maxRadius = maxOf(center, fn.posByIp.size - 1 - center)
        for (radius in 0..maxRadius) {
            val before = center - radius
            if (before >= 0) {
                val pos = posForIp(before)
                if (pos != null) {
                    if (pos.source !== Source.builtIn && pos.source !== Source.UNKNOWN) return pos
                    if (fallback == null) fallback = pos
                }
            }
            if (radius == 0) continue
            val after = center + radius
            if (after < fn.posByIp.size) {
                val pos = posForIp(after)
                if (pos != null) {
                    if (pos.source !== Source.builtIn && pos.source !== Source.UNKNOWN) return pos
                    if (fallback == null) fallback = pos
                }
            }
        }
        return fallback
    }

    fun pushScope(plan: Map<String, Int>, captures: List<String>) {
        if (scope.skipScopeCreation) {
            val snapshot = emptyMap<String, Int?>()
            slotPlanStack.addLast(snapshot)
            virtualDepth += 1
            scopeStack.addLast(scope)
            scopeVirtualStack.addLast(true)
        } else {
            scopeStack.addLast(scope)
            scopeVirtualStack.addLast(false)
            scope = scope.createChildScope()
        }
        captureStack.addLast(captures)
        scopeDepth += 1
    }

    fun popScope() {
        val isVirtual = scopeVirtualStack.removeLastOrNull()
            ?: error("Scope stack underflow in POP_SCOPE")
        if (isVirtual) {
            val snapshot = slotPlanStack.removeLastOrNull()
                ?: error("Slot plan stack underflow in POP_SCOPE")
            scope.restoreSlotPlan(snapshot)
            virtualDepth -= 1
        }
        scope = scopeStack.removeLastOrNull()
            ?: error("Scope stack underflow in POP_SCOPE")
        val captures = captureStack.removeLastOrNull() ?: emptyList()
        scopeDepth -= 1
    }

    fun pushIterator(iter: Obj) {
        iterStack.addLast(iter)
        if (iter.objClass.className == "FlowIterator") {
            vmIterDebug { "pushIterator fn=${fn.name} depth=${iterStack.size} iterClass=${iter.objClass.className}" }
        }
    }

    fun popIterator() {
        val iter = iterStack.lastOrNull()
        if (iter != null && iter.objClass.className == "FlowIterator") {
            vmIterDebug { "popIterator fn=${fn.name} depth=${iterStack.size} iterClass=${iter.objClass.className}" }
        }
        iterStack.removeLastOrNull()
    }

    suspend fun cancelTopIterator() {
        val iter = iterStack.removeLastOrNull() ?: return
        vmIterDebug { "cancelTopIterator fn=${fn.name} depthAfter=${iterStack.size} iterClass=${iter.objClass.className}" }
        iter.invokeInstanceMethod(ensureScope(), "cancelIteration") { ObjVoid }
    }

    suspend fun cancelIterators() {
        while (iterStack.isNotEmpty()) {
            val iter = iterStack.removeLast()
            vmIterDebug { "cancelIterators fn=${fn.name} depthAfter=${iterStack.size} iterClass=${iter.objClass.className}" }
            try {
                iter.invokeInstanceMethod(ensureScope(), "cancelIteration") { ObjVoid }
            } catch (e: Throwable) {
                vmIterDebug(e) {
                    "cancelIterators: cancelIteration failed fn=${fn.name} depthAfter=${iterStack.size} iterClass=${iter.objClass.className}"
                }
            }
        }
    }

    private suspend fun cancelIteratorsToDepth(depth: Int, reason: String) {
        while (iterStack.size > depth) {
            val iter = iterStack.removeLast()
            vmIterDebug {
                "cancelIteratorsToDepth fn=${fn.name} reason=$reason targetDepth=$depth depthAfter=${iterStack.size} iterClass=${iter.objClass.className}"
            }
            try {
                iter.invokeInstanceMethod(ensureScope(), "cancelIteration") { ObjVoid }
            } catch (e: Throwable) {
                vmIterDebug(e) {
                    "cancelIteratorsToDepth: cancelIteration failed fn=${fn.name} reason=$reason"
                }
            }
        }
    }

    fun pushSlotPlan(plan: Map<String, Int>) {
        if (scope.hasSlotPlanConflict(plan)) {
            scopeStack.addLast(scope)
            slotPlanScopeStack.addLast(true)
            scope = scope.createChildScope()
        } else {
            val snapshot = emptyMap<String, Int?>()
            slotPlanStack.addLast(snapshot)
            slotPlanScopeStack.addLast(false)
            virtualDepth += 1
        }
        scopeDepth += 1
    }

    fun popSlotPlan() {
        val pushedScope = slotPlanScopeStack.removeLastOrNull()
            ?: error("Slot plan stack underflow in POP_SLOT_PLAN")
        if (pushedScope) {
            scope = scopeStack.removeLastOrNull()
                ?: error("Scope stack underflow in POP_SLOT_PLAN")
        } else {
            val snapshot = slotPlanStack.removeLastOrNull()
                ?: error("Slot plan stack underflow in POP_SLOT_PLAN")
            scope.restoreSlotPlan(snapshot)
            virtualDepth -= 1
        }
        scopeDepth -= 1
    }

    suspend fun getObj(slot: Int): Obj {
        return if (slot < fn.scopeSlotCount) {
            getScopeSlotValue(slot)
        } else {
            readLocalSlotValue(slot - fn.scopeSlotCount)
        }
    }

    fun setObj(slot: Int, value: Obj) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            val record = target.getSlotRecord(index)
            if (!record.isMutable) {
                val name = fn.scopeSlotNames[slot] ?: "slot#$index"
                ensureScope().raiseError("can't assign to read-only variable: $name")
            }
            target.setSlotValue(index, value)
        } else {
            val localIndex = slot - fn.scopeSlotCount
            ensureLocalMutable(localIndex)
            if (shouldWriteThroughLocal(localIndex)) {
                when (val existing = frame.getRawObj(localIndex)) {
                    is FrameSlotRef -> {
                        existing.write(value)
                        return
                    }

                    is RecordSlotRef -> {
                        existing.write(value)
                        return
                    }

                    else -> {}
                }
            }
            frame.setObj(localIndex, value)
        }
    }

    fun shouldBypassImmutableWrite(slot: Int): Boolean {
        val next = fn.cmds.getOrNull(ip) ?: return false
        return when (next) {
            is CmdDeclLocal -> next.slot == slot
            is CmdDeclDelegated -> next.slot == slot
            else -> false
        }
    }

    suspend fun writeThroughPropertyLikeSlot(slot: Int, value: Obj): Boolean {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            val name = fn.scopeSlotNames.getOrNull(slot)
            val record = resolveScopeSlotRecordForWrite(target, index, name)
            if (name != null && record != null && (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || record.value is ObjProperty)) {
                target.assign(record, name, value)
                return true
            }
            return false
        }
        val localIndex = slot - fn.scopeSlotCount
        val name = fn.localSlotNames.getOrNull(localIndex) ?: return false
        val isCapture = fn.localSlotCaptures.getOrNull(localIndex) == true
        val raw = frame.getRawObj(localIndex)
        if (raw is RecordSlotRef) {
            if (raw.write(scope, name, value)) return true
            return false
        }
        if (!isCapture && raw !== ObjUnset && raw !is ObjProperty) return false
        val record = scope.parent?.get(name) ?: scope.get(name) ?: return false
        if (record.type != ObjRecord.Type.Delegated && record.type != ObjRecord.Type.Property && record.value !is ObjProperty) {
            return false
        }
        scope.assign(record, name, value)
        return true
    }

    suspend fun getInt(slot: Int): Long {
        return if (slot < fn.scopeSlotCount) {
            getScopeSlotValue(slot).toLong()
        } else {
            val local = slot - fn.scopeSlotCount
            when (frame.getSlotTypeCode(local)) {
                SlotType.INT.code -> frame.getInt(local)
                SlotType.REAL.code -> frame.getReal(local).toLong()
                SlotType.BOOL.code -> if (frame.getBool(local)) 1L else 0L
                SlotType.OBJ.code -> readLocalSlotValue(local).toLong()
                else -> 0L
            }
        }
    }

    fun getLocalInt(local: Int): Long = frame.getInt(local)
    fun getLocalReal(local: Int): Double = frame.getReal(local)

    fun setIntUnchecked(slot: Int, value: Long) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            target.setSlotValue(index, ObjInt.of(value))
        } else {
            val localIndex = slot - fn.scopeSlotCount
            if (shouldWriteThroughLocal(localIndex)) {
                when (val existing = frame.getRawObj(localIndex)) {
                    is FrameSlotRef -> {
                        existing.write(ObjInt.of(value))
                        return
                    }

                    is RecordSlotRef -> {
                        existing.write(ObjInt.of(value))
                        return
                    }

                    else -> {}
                }
            }
            frame.setInt(localIndex, value)
        }
    }

    fun setInt(slot: Int, value: Long) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            val record = target.getSlotRecord(index)
            if (!record.isMutable) {
                val name = fn.scopeSlotNames[slot] ?: "slot#$index"
                ensureScope().raiseError("can't assign to read-only variable: $name")
            }
            target.setSlotValue(index, ObjInt.of(value))
        } else {
            val localIndex = slot - fn.scopeSlotCount
            ensureLocalMutable(localIndex)
            if (shouldWriteThroughLocal(localIndex)) {
                when (val existing = frame.getRawObj(localIndex)) {
                    is FrameSlotRef -> {
                        existing.write(ObjInt.of(value))
                        return
                    }

                    is RecordSlotRef -> {
                        existing.write(ObjInt.of(value))
                        return
                    }

                    else -> {}
                }
            }
            frame.setInt(localIndex, value)
        }
    }

    fun setLocalInt(local: Int, value: Long) {
        frame.setInt(local, value)
    }

    fun setLocalReal(local: Int, value: Double) {
        frame.setReal(local, value)
    }

    suspend fun getReal(slot: Int): Double {
        return if (slot < fn.scopeSlotCount) {
            getScopeSlotValue(slot).toDouble()
        } else {
            val local = slot - fn.scopeSlotCount
            when (frame.getSlotTypeCode(local)) {
                SlotType.REAL.code -> frame.getReal(local)
                SlotType.INT.code -> frame.getInt(local).toDouble()
                SlotType.BOOL.code -> if (frame.getBool(local)) 1.0 else 0.0
                SlotType.OBJ.code -> readLocalSlotValue(local).toDouble()
                else -> 0.0
            }
        }
    }

    fun setReal(slot: Int, value: Double) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            val record = target.getSlotRecord(index)
            if (!record.isMutable) {
                val name = fn.scopeSlotNames[slot] ?: "slot#$index"
                ensureScope().raiseError("can't assign to read-only variable: $name")
            }
            target.setSlotValue(index, ObjReal.of(value))
        } else {
            val localIndex = slot - fn.scopeSlotCount
            ensureLocalMutable(localIndex)
            if (shouldWriteThroughLocal(localIndex)) {
                when (val existing = frame.getRawObj(localIndex)) {
                    is FrameSlotRef -> {
                        existing.write(ObjReal.of(value))
                        return
                    }

                    is RecordSlotRef -> {
                        existing.write(ObjReal.of(value))
                        return
                    }

                    else -> {}
                }
            }
            frame.setReal(localIndex, value)
        }
    }

    fun setRealUnchecked(slot: Int, value: Double) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            target.setSlotValue(index, ObjReal.of(value))
        } else {
            val localIndex = slot - fn.scopeSlotCount
            if (shouldWriteThroughLocal(localIndex)) {
                when (val existing = frame.getRawObj(localIndex)) {
                    is FrameSlotRef -> {
                        existing.write(ObjReal.of(value))
                        return
                    }

                    is RecordSlotRef -> {
                        existing.write(ObjReal.of(value))
                        return
                    }

                    else -> {}
                }
            }
            frame.setReal(localIndex, value)
        }
    }

    suspend fun getBool(slot: Int): Boolean {
        return if (slot < fn.scopeSlotCount) {
            getScopeSlotValue(slot).toBool()
        } else {
            val local = slot - fn.scopeSlotCount
            when (frame.getSlotTypeCode(local)) {
                SlotType.BOOL.code -> frame.getBool(local)
                SlotType.INT.code -> frame.getInt(local) != 0L
                SlotType.REAL.code -> frame.getReal(local) != 0.0
                SlotType.OBJ.code -> readLocalSlotValue(local).toBool()
                else -> false
            }
        }
    }

    fun getLocalBool(local: Int): Boolean = frame.getBool(local)

    fun setBool(slot: Int, value: Boolean) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            val record = target.getSlotRecord(index)
            if (!record.isMutable) {
                val name = fn.scopeSlotNames[slot] ?: "slot#$index"
                ensureScope().raiseError("can't assign to read-only variable: $name")
            }
            target.setSlotValue(index, if (value) ObjTrue else ObjFalse)
        } else {
            val localIndex = slot - fn.scopeSlotCount
            ensureLocalMutable(localIndex)
            if (shouldWriteThroughLocal(localIndex)) {
                when (val existing = frame.getRawObj(localIndex)) {
                    is FrameSlotRef -> {
                        existing.write(if (value) ObjTrue else ObjFalse)
                        return
                    }

                    is RecordSlotRef -> {
                        existing.write(if (value) ObjTrue else ObjFalse)
                        return
                    }

                    else -> {}
                }
            }
            frame.setBool(localIndex, value)
        }
    }

    fun setBoolUnchecked(slot: Int, value: Boolean) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            target.setSlotValue(index, if (value) ObjTrue else ObjFalse)
        } else {
            val localIndex = slot - fn.scopeSlotCount
            if (shouldWriteThroughLocal(localIndex)) {
                when (val existing = frame.getRawObj(localIndex)) {
                    is FrameSlotRef -> {
                        existing.write(if (value) ObjTrue else ObjFalse)
                        return
                    }

                    is RecordSlotRef -> {
                        existing.write(if (value) ObjTrue else ObjFalse)
                        return
                    }

                    else -> {}
                }
            }
            frame.setBool(localIndex, value)
        }
    }

    fun setLocalBool(local: Int, value: Boolean) {
        frame.setBool(local, value)
    }

    fun resolveScopeSlotAddr(scopeSlot: Int, addrSlot: Int) {
        val target = scopeTarget(scopeSlot)
        val index = ensureScopeSlot(target, scopeSlot)
        addrScopes[addrSlot] = target
        addrIndices[addrSlot] = index
        addrScopeSlots[addrSlot] = scopeSlot
    }

    suspend fun getAddrObj(addrSlot: Int): Obj {
        return getScopeSlotValueAtAddr(addrSlot)
    }

    suspend fun setAddrObj(addrSlot: Int, value: Obj) {
        setScopeSlotValueAtAddr(addrSlot, value)
    }

    suspend fun getAddrInt(addrSlot: Int): Long {
        return getScopeSlotValueAtAddr(addrSlot).toLong()
    }

    suspend fun setAddrInt(addrSlot: Int, value: Long) {
        setScopeSlotValueAtAddr(addrSlot, ObjInt.of(value))
    }

    suspend fun getAddrReal(addrSlot: Int): Double {
        return getScopeSlotValueAtAddr(addrSlot).toDouble()
    }

    suspend fun setAddrReal(addrSlot: Int, value: Double) {
        setScopeSlotValueAtAddr(addrSlot, ObjReal.of(value))
    }

    suspend fun getAddrBool(addrSlot: Int): Boolean {
        return getScopeSlotValueAtAddr(addrSlot).toBool()
    }

    suspend fun setAddrBool(addrSlot: Int, value: Boolean) {
        setScopeSlotValueAtAddr(addrSlot, if (value) ObjTrue else ObjFalse)
    }

    suspend fun slotToObj(slot: Int): Obj {
        if (slot < fn.scopeSlotCount) {
            return getScopeSlotValue(slot)
        }
        val local = slot - fn.scopeSlotCount
        if (fn.localSlotCaptures.getOrNull(local) == true) {
            return readLocalSlotValue(local)
        }
        return when (frame.getSlotTypeCode(local)) {
            SlotType.INT.code -> ObjInt.of(frame.getInt(local))
            SlotType.REAL.code -> ObjReal.of(frame.getReal(local))
            SlotType.BOOL.code -> if (frame.getBool(local)) ObjTrue else ObjFalse
            SlotType.OBJ.code -> readLocalSlotValue(local)
            else -> readLocalSlotValue(local)
        }
    }

    fun storedSlotObj(slot: Int): Obj {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            val record = target.getSlotRecord(index)
            return when (val direct = record.value) {
                is FrameSlotRef -> direct.read()
                is RecordSlotRef -> direct.read()
                is ScopeSlotRef -> direct.read()
                else -> direct
            }
        }
        val local = slot - fn.scopeSlotCount
        return when (frame.getSlotTypeCode(local)) {
            SlotType.INT.code -> ObjInt.of(frame.getInt(local))
            SlotType.REAL.code -> ObjReal.of(frame.getReal(local))
            SlotType.BOOL.code -> if (frame.getBool(local)) ObjTrue else ObjFalse
            SlotType.OBJ.code, SlotType.UNKNOWN.code -> when (val raw = frame.getRawObj(local)) {
                is FrameSlotRef -> raw.read()
                is RecordSlotRef -> raw.read()
                is ScopeSlotRef -> raw.read()
                null -> ObjNull
                else -> raw
            }

            else -> frame.getRawObj(local) ?: ObjNull
        }
    }

    fun storeObjResult(dst: Int, result: Obj) {
        when (result) {
            is ObjInt -> setInt(dst, result.value)
            is ObjReal -> setReal(dst, result.value)
            is ObjBool -> setBool(dst, result.value)
            else -> setObj(dst, result)
        }
    }

    fun setObjUnchecked(slot: Int, value: Obj) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            target.setSlotValue(index, value)
        } else {
            val localIndex = slot - fn.scopeSlotCount
            if (shouldWriteThroughLocal(localIndex)) {
                when (val existing = frame.getRawObj(localIndex)) {
                    is FrameSlotRef -> {
                        existing.write(value)
                        return
                    }

                    is RecordSlotRef -> {
                        existing.write(value)
                        return
                    }

                    else -> {}
                }
            }
            frame.setObj(localIndex, value)
        }
    }

    private fun shouldWriteThroughLocal(localIndex: Int): Boolean {
        if (localIndex < fn.localSlotCaptures.size && fn.localSlotCaptures[localIndex]) return true
        if (localIndex < fn.localSlotDelegated.size && fn.localSlotDelegated[localIndex]) return true
        return when (frame.getRawObj(localIndex)) {
            is FrameSlotRef, is RecordSlotRef -> true
            else -> false
        }
    }

    suspend fun throwObj(pos: Pos, value: Obj) {
        var errorObject = value
        val throwScope = ensureScope().createChildScope(pos = pos)
        if (errorObject is ObjString) {
            errorObject = ObjException(throwScope, errorObject.value).apply { getStackTrace() }
        }
        if (!errorObject.isInstanceOf(ObjException.Root)) {
            throwScope.raiseError("this is not an exception object: $errorObject")
        }
        if (errorObject is ObjException) {
            errorObject = ObjException(
                errorObject.exceptionClass,
                throwScope,
                errorObject.message,
                errorObject.extraData,
                errorObject.useStackTrace
            ).apply { getStackTrace() }
            throwScope.raiseError(errorObject)
        } else {
            val msg = errorObject.invokeInstanceMethod(scope, "message").toString(scope).value
            throwScope.raiseError(errorObject, pos, msg)
        }
    }

    suspend fun buildArguments(argBase: Int, argCount: Int): Arguments {
        if (argCount == 0) return Arguments.EMPTY
        if ((argCount and ARG_PLAN_FLAG) != 0) {
            val planId = argCount and ARG_PLAN_MASK
            val plan = fn.constants.getOrNull(planId) as? BytecodeConst.CallArgsPlan
                ?: error("CALL args plan not found: $planId")
            return buildArgumentsFromPlan(argBase, plan)
        }
        val list = ArrayList<Obj>(argCount)
        for (i in 0 until argCount) {
            list.add(slotToObj(argBase + i))
        }
        return Arguments(list)
    }

    private suspend fun buildArgumentsFromPlan(
        argBase: Int,
        plan: BytecodeConst.CallArgsPlan,
    ): Arguments {
        val scope = ensureScope()
        val positional = ArrayList<Obj>(plan.specs.size)
        var named: LinkedHashMap<String, Obj>? = null
        var namedSeen = false
        for ((idx, spec) in plan.specs.withIndex()) {
            val value = slotToObj(argBase + idx)
            val name = spec.name
            if (name != null) {
                if (named == null) named = linkedMapOf()
                if (named.containsKey(name)) scope.raiseIllegalArgument("argument '$name' is already set")
                named[name] = value
                namedSeen = true
                continue
            }
            if (spec.isSplat) {
                when {
                    value is ObjMap -> {
                        if (named == null) named = linkedMapOf()
                        for ((k, v) in value.map) {
                            if (k !is ObjString) scope.raiseIllegalArgument("named splat expects a Map with string keys")
                            val key = k.value
                            if (named.containsKey(key)) scope.raiseIllegalArgument("argument '$key' is already set")
                            named[key] = v
                        }
                        namedSeen = true
                    }

                    value is ObjList -> {
                        if (namedSeen) scope.raiseIllegalArgument("positional splat cannot follow named arguments")
                        positional.addAll(value.list)
                    }

                    value.isInstanceOf(ObjIterable) -> {
                        if (namedSeen) scope.raiseIllegalArgument("positional splat cannot follow named arguments")
                        val list = (value.invokeInstanceMethod(scope, "toList") as ObjList).list
                        positional.addAll(list)
                    }

                    else -> scope.raiseClassCastError("expected list of objects for splat argument")
                }
            } else {
                if (namedSeen) {
                    val isLast = idx == plan.specs.lastIndex
                    if (!(isLast && plan.tailBlock)) {
                        scope.raiseIllegalArgument("positional argument cannot follow named arguments")
                    }
                }
                positional.add(value)
            }
        }
        return Arguments(
            list = positional,
            tailBlockMode = plan.tailBlock,
            named = named ?: emptyMap(),
            explicitTypeArgs = plan.explicitTypeArgs
        )
    }

    private fun resolveLocalScope(localIndex: Int): Scope? {
        return scope
    }

    internal fun scopeTarget(slot: Int): Scope {
        return if (slot < fn.scopeSlotCount && fn.scopeSlotIsModule.getOrNull(slot) == true) {
            moduleScope
        } else {
            scope
        }
    }

    private suspend fun readLocalSlotValue(localIndex: Int): Obj {
        val localName = fn.localSlotNames.getOrNull(localIndex)
        return when (frame.getSlotTypeCode(localIndex)) {
            SlotType.INT.code -> ObjInt.of(frame.getInt(localIndex))
            SlotType.REAL.code -> ObjReal.of(frame.getReal(localIndex))
            SlotType.BOOL.code -> if (frame.getBool(localIndex)) ObjTrue else ObjFalse
            SlotType.OBJ.code -> {
                val obj = frame.getObj(localIndex)
                when (obj) {
                    is FrameSlotRef -> obj.read()
                    is RecordSlotRef -> obj.read(scope, localName)
                    is ScopeSlotRef -> obj.read()
                    is ObjProperty -> resolvePropertyLikeLocal(localName, obj)
                    ObjUnset -> resolveUnsetLocal(localName)
                    else -> obj
                }
            }

            else -> {
                val obj = frame.getObj(localIndex)
                when (obj) {
                    is FrameSlotRef -> obj.read()
                    is RecordSlotRef -> obj.read(scope, localName)
                    is ScopeSlotRef -> obj.read()
                    is ObjProperty -> resolvePropertyLikeLocal(localName, obj)
                    ObjUnset -> resolveUnsetLocal(localName)
                    else -> obj
                }
            }
        }
    }

    private suspend fun resolvePropertyLikeLocal(localName: String?, property: ObjProperty): Obj {
        if (localName != null) {
            val record = scope.parent?.get(localName) ?: scope.get(localName)
            if (record != null && (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || record.value is ObjProperty)) {
                return scope.resolve(record, localName)
            }
        }
        return property.callGetter(scope, scope.thisObj)
    }

    private suspend fun resolveUnsetLocal(localName: String?): Obj {
        if (localName == null) return ObjUnset
        val record = scope.parent?.get(localName) ?: scope.get(localName) ?: return ObjUnset
        if (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || record.value is ObjProperty) {
            return scope.resolve(record, localName)
        }
        return when (val value = record.value) {
            is FrameSlotRef -> value.read()
            is RecordSlotRef -> value.read(scope, localName)
            is ScopeSlotRef -> value.read()
            else -> value
        }
    }

    private suspend fun readResolvedScopeRecord(target: Scope, name: String, record: ObjRecord): Obj {
        val value = record.value
        return when {
            record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || value is ObjProperty ->
                target.resolve(record, name)
            value is FrameSlotRef -> value.read()
            value is RecordSlotRef -> value.read(target, name)
            value is ScopeSlotRef -> value.read()
            else -> value
        }
    }

    private suspend fun getScopeSlotValue(slot: Int): Obj {
        val target = scopeTarget(slot)
        val name = fn.scopeSlotNames[slot]
        val hadNamedBinding = name != null && hasResolvedNamedScopeBinding(target, name)
        val index = ensureScopeSlot(target, slot)
        val record = target.getSlotRecord(index)
        val direct = record.value
        if (direct is FrameSlotRef) return direct.read()
        if (direct is RecordSlotRef) return direct.read()
        if (name != null && record.memberName != null && record.memberName != name) {
            val resolved = target.get(name)
            if (resolved != null) {
                val resolvedValue = readResolvedScopeRecord(target, name, resolved)
                if (resolvedValue !== ObjUnset) {
                    target.updateSlotFor(name, resolved)
                }
                return resolvedValue
            }
        }
        if (name != null && (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || direct is ObjProperty)) {
            return target.resolve(record, name)
        }
        if (direct !== ObjUnset) {
            return direct
        }
        if (name == null) return record.value
        val resolved = target.get(name)
        if (resolved == null) {
            failMissingPreparedModuleBinding(slot, name, hadNamedBinding, record)
            return record.value
        }
        val resolvedValue = readResolvedScopeRecord(target, name, resolved)
        if (resolvedValue !== ObjUnset) {
            target.updateSlotFor(name, resolved)
        } else {
            failMissingPreparedModuleBinding(slot, name, hadNamedBinding, resolved)
        }
        return resolvedValue
    }

    private suspend fun getScopeSlotValueAtAddr(addrSlot: Int): Obj {
        val target = addrScopes[addrSlot] ?: error("Address slot $addrSlot is not resolved")
        val index = addrIndices[addrSlot]
        val slotId = addrScopeSlots[addrSlot]
        val name = fn.scopeSlotNames.getOrNull(slotId)
        val hadNamedBinding = name != null && hasResolvedNamedScopeBinding(target, name)
        val record = target.getSlotRecord(index)
        val direct = record.value
        if (direct is FrameSlotRef) return direct.read()
        if (direct is RecordSlotRef) return direct.read()
        if (name != null && record.memberName != null && record.memberName != name) {
            val resolved = target.get(name)
            if (resolved != null) {
                val resolvedValue = readResolvedScopeRecord(target, name, resolved)
                if (resolvedValue !== ObjUnset) {
                    target.updateSlotFor(name, resolved)
                }
                return resolvedValue
            }
        }
        if (name != null && (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || direct is ObjProperty)) {
            return target.resolve(record, name)
        }
        if (direct !== ObjUnset) {
            return direct
        }
        if (name == null) return record.value
        val resolved = target.get(name)
        if (resolved == null) {
            failMissingPreparedModuleBinding(slotId, name, hadNamedBinding, record)
            return record.value
        }
        val resolvedValue = readResolvedScopeRecord(target, name, resolved)
        if (resolvedValue !== ObjUnset) {
            target.updateSlotFor(name, resolved)
        } else {
            failMissingPreparedModuleBinding(slotId, name, hadNamedBinding, resolved)
        }
        return resolvedValue
    }

    private suspend fun setScopeSlotValueAtAddr(addrSlot: Int, value: Obj) {
        val target = addrScopes[addrSlot] ?: error("Address slot $addrSlot is not resolved")
        val index = addrIndices[addrSlot]
        val slotId = addrScopeSlots[addrSlot]
        val name = fn.scopeSlotNames.getOrNull(slotId)
        val record = resolveScopeSlotRecordForWrite(target, index, name)
        if (name != null && record != null && (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || record.value is ObjProperty)) {
            target.assign(record, name, value)
            return
        }
        target.setSlotValue(index, value)
    }

    private fun resolveScopeSlotRecordForWrite(target: Scope, index: Int, name: String?): ObjRecord? {
        val record = target.getSlotRecord(index)
        if (name == null) return record
        if (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || record.value is ObjProperty) {
            return record
        }
        if (record.value !== ObjUnset && record.memberName == null) {
            return record
        }
        val resolved = target.get(name) ?: return record
        if (resolved.value !== ObjUnset || resolved.type == ObjRecord.Type.Delegated || resolved.type == ObjRecord.Type.Property || resolved.value is ObjProperty) {
            target.updateSlotFor(name, resolved)
            return resolved
        }
        return record
    }

    internal fun ensureScopeSlot(target: Scope, slot: Int): Int {
        val name = fn.scopeSlotNames[slot]
        if (name != null) {
            val existing = target.getSlotIndexOf(name)
            if (existing != null) return existing
        }
        val index = fn.scopeSlotIndices[slot]
        if (name == null) {
            if (index < target.slotCount) return index
            return index
        }
        target.applySlotPlan(mapOf(name to index))
        val existing = target.getLocalRecordDirect(name) ?: target.localBindings[name]
        if (existing != null) {
            target.updateSlotFor(name, existing)
            return index
        }
        val resolved = target.parent?.get(name) ?: target.get(name)
        if (resolved != null) {
            target.updateSlotFor(name, resolved)
        }
        return index
    }

    private fun hasNamedScopeBinding(target: Scope, name: String): Boolean {
        if (target.tryGetLocalRecord(target, name, target.currentClassCtx) != null) return true
        if (target.getSlotIndexOf(name) != null) return true
        if (target.get(name) != null) return true
        return false
    }

    private fun hasResolvedNamedScopeBinding(target: Scope, name: String): Boolean =
        findNamedExistingRecord(target, name) != null

    private fun findNamedExistingRecord(target: Scope, name: String): ObjRecord? {
        target.tryGetLocalRecord(target, name, target.currentClassCtx)?.let { return it }
        target.get(name)?.let { record ->
            if (record.value !== ObjUnset || record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property) {
                return record
            }
        }
        return null
    }

    private fun failMissingPreparedModuleBinding(
        slot: Int,
        name: String,
        hadNamedBinding: Boolean,
        record: ObjRecord
    ) {
        if (hadNamedBinding) return
        if (record.value !== ObjUnset) return
        if (fn.scopeSlotIsModule.getOrNull(slot) != true) return
        val pos = fn.scopeSlotRefPos.getOrNull(slot) ?: currentErrorPos() ?: ensureScope().pos
        if (fn.scopeSlotRequiresPreparedBinding.getOrNull(slot) != true) {
            throw ScriptError(pos, "symbol '$name' is not defined")
        }
        throw ScriptError(
            pos,
            "module binding '$name' is not available in the execution scope; prepare the script imports/module bindings explicitly"
        )
    }

    private fun ensureLocalMutable(localIndex: Int) {
        val name = fn.localSlotNames.getOrNull(localIndex) ?: return
        val isMutable = fn.localSlotMutables.getOrNull(localIndex) ?: true
        if (!isMutable) {
            val typeCode = frame.getSlotTypeCode(localIndex)
            if (typeCode == SlotType.UNKNOWN.code) return
            val rawObj = frame.getRawObj(localIndex)
            if (rawObj === ObjUnset) return
            ensureScope().raiseError("can't assign to read-only variable: $name")
        }
    }

    // Scope depth resolution is no longer used; all scope slots are resolved against the current frame.
}
