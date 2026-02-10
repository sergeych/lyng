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

    suspend fun execute(fn: CmdFunction, scope0: Scope, args: List<Obj>): Obj {
        result = null
        val frame = CmdFrame(this, fn, scope0, args)
        val cmds = fn.cmds
        if (fn.localSlotNames.isNotEmpty()) {
            frame.syncScopeToFrame()
        }
        try {
            while (result == null) {
                val cmd = cmds[frame.ip]
                frame.ip += 1
                try {
                    cmd.perform(frame)
                } catch (e: Throwable) {
                    if (!frame.handleException(e)) {
                        frame.cancelIterators()
                        throw e
                    }
                }
            }
        } catch (e: Throwable) {
            frame.cancelIterators()
            throw e
        }
        frame.cancelIterators()
        return result ?: ObjVoid
    }
}

sealed class Cmd {
    abstract suspend fun perform(frame: CmdFrame)
}

class CmdNop : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        return
    }
}

class CmdMoveObj(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setObj(dst, frame.slotToObj(src))
        return
    }
}

class CmdMoveInt(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(src))
        return
    }
}

class CmdMoveIntLocal(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalInt(dst, frame.getLocalInt(src))
        return
    }
}

class CmdMoveReal(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, frame.getReal(src))
        return
    }
}

class CmdMoveBool(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getBool(src))
        return
    }
}

class CmdConstObj(internal val constId: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
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
        return
    }
}

class CmdConstInt(internal val constId: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val c = frame.fn.constants[constId] as? BytecodeConst.IntVal
            ?: error("CONST_INT expects IntVal at $constId")
        frame.setInt(dst, c.value)
        return
    }
}

class CmdConstIntLocal(internal val constId: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val c = frame.fn.constants[constId] as? BytecodeConst.IntVal
            ?: error("CONST_INT expects IntVal at $constId")
        frame.setLocalInt(dst, c.value)
        return
    }
}

class CmdConstReal(internal val constId: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val c = frame.fn.constants[constId] as? BytecodeConst.RealVal
            ?: error("CONST_REAL expects RealVal at $constId")
        frame.setReal(dst, c.value)
        return
    }
}

class CmdConstBool(internal val constId: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val c = frame.fn.constants[constId] as? BytecodeConst.Bool
            ?: error("CONST_BOOL expects Bool at $constId")
        frame.setBool(dst, c.value)
        return
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
        val receiver = frame.ensureScope().thisVariants.firstOrNull { it.isInstanceOf(typeName) }
            ?: frame.ensureScope().raiseClassCastError("Cannot cast ${frame.ensureScope().thisObj.objClass.className} to $typeName")
        frame.setObj(dst, receiver)
        return
    }
}

class CmdMakeRange(
    internal val startSlot: Int,
    internal val endSlot: Int,
    internal val inclusiveSlot: Int,
    internal val stepSlot: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val start = frame.slotToObj(startSlot)
        val end = frame.slotToObj(endSlot)
        val inclusive = frame.slotToObj(inclusiveSlot).toBool()
        val stepObj = frame.slotToObj(stepSlot)
        val step = if (stepObj.isNull) null else stepObj
        frame.storeObjResult(dst, ObjRange(start, end, isEndInclusive = inclusive, step = step))
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

class CmdObjToBool(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(src).toBool())
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
        val clazz = typeObj as? ObjClass ?: frame.ensureScope().raiseClassCastError(
            "${typeObj.inspect(frame.ensureScope())} is not the class instance"
        )
        if (!obj.isInstanceOf(clazz)) {
            frame.ensureScope().raiseClassCastError(
                "Cannot cast ${obj.objClass.className} to ${clazz.className}"
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
        val clazz = typeObj as? ObjClass ?: frame.ensureScope().raiseClassCastError(
            "${typeObj.inspect(frame.ensureScope())} is not the class instance"
        )
        val base = when (obj0) {
            is ObjQualifiedView -> obj0.instance
            else -> obj0
        }
        val result = if (base is ObjInstance && base.isInstanceOf(clazz)) {
            ObjQualifiedView(base, clazz)
        } else {
            base
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdRangeIntBounds(
    internal val src: Int,
    internal val startSlot: Int,
    internal val endSlot: Int,
    internal val okSlot: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val obj = frame.slotToObj(src)
        val range = obj as? ObjRange
        if (range == null || !range.isIntRange) {
            frame.setBool(okSlot, false)
            return
        }
        val start = (range.start as ObjInt).value
        val end = (range.end as ObjInt).value
        frame.setInt(startSlot, start)
        frame.setInt(endSlot, if (range.isEndInclusive) end + 1 else end)
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
        frame.setObj(dst, frame.getAddrObj(addrSlot))
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
        frame.setInt(dst, frame.getAddrInt(addrSlot))
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
        frame.setReal(dst, frame.getAddrReal(addrSlot))
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
        frame.setBool(dst, frame.getAddrBool(addrSlot))
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

class CmdRealToInt(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getReal(src).toLong())
        return
    }
}

class CmdBoolToInt(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, if (frame.getBool(src)) 1L else 0L)
        return
    }
}

class CmdIntToBool(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getBool(src))
        return
    }
}

class CmdAddInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) + frame.getInt(b))
        return
    }
}

class CmdAddIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalInt(dst, frame.getLocalInt(a) + frame.getLocalInt(b))
        return
    }
}

class CmdSubInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) - frame.getInt(b))
        return
    }
}

class CmdSubIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalInt(dst, frame.getLocalInt(a) - frame.getLocalInt(b))
        return
    }
}

class CmdMulInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) * frame.getInt(b))
        return
    }
}

class CmdMulIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalInt(dst, frame.getLocalInt(a) * frame.getLocalInt(b))
        return
    }
}

class CmdDivInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) / frame.getInt(b))
        return
    }
}

class CmdDivIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalInt(dst, frame.getLocalInt(a) / frame.getLocalInt(b))
        return
    }
}

class CmdModInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) % frame.getInt(b))
        return
    }
}

class CmdModIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalInt(dst, frame.getLocalInt(a) % frame.getLocalInt(b))
        return
    }
}

class CmdNegInt(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, -frame.getInt(src))
        return
    }
}

class CmdIncInt(internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(slot, frame.getInt(slot) + 1L)
        return
    }
}

class CmdIncIntLocal(internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalInt(slot, frame.getLocalInt(slot) + 1L)
        return
    }
}

class CmdDecInt(internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(slot, frame.getInt(slot) - 1L)
        return
    }
}

class CmdDecIntLocal(internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalInt(slot, frame.getLocalInt(slot) - 1L)
        return
    }
}

class CmdAddReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, frame.getReal(a) + frame.getReal(b))
        return
    }
}

class CmdSubReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, frame.getReal(a) - frame.getReal(b))
        return
    }
}

class CmdMulReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, frame.getReal(a) * frame.getReal(b))
        return
    }
}

class CmdDivReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, frame.getReal(a) / frame.getReal(b))
        return
    }
}

class CmdNegReal(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, -frame.getReal(src))
        return
    }
}

class CmdAndInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) and frame.getInt(b))
        return
    }
}

class CmdOrInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) or frame.getInt(b))
        return
    }
}

class CmdXorInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) xor frame.getInt(b))
        return
    }
}

class CmdShlInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) shl frame.getInt(b).toInt())
        return
    }
}

class CmdShrInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) shr frame.getInt(b).toInt())
        return
    }
}

class CmdUshrInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(a) ushr frame.getInt(b).toInt())
        return
    }
}

class CmdInvInt(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setInt(dst, frame.getInt(src).inv())
        return
    }
}

class CmdCmpEqInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) == frame.getInt(b))
        return
    }
}

class CmdCmpEqIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalBool(dst, frame.getLocalInt(a) == frame.getLocalInt(b))
        return
    }
}

class CmdCmpNeqInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) != frame.getInt(b))
        return
    }
}

class CmdCmpNeqIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalBool(dst, frame.getLocalInt(a) != frame.getLocalInt(b))
        return
    }
}

class CmdCmpLtInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) < frame.getInt(b))
        return
    }
}

class CmdCmpLtIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalBool(dst, frame.getLocalInt(a) < frame.getLocalInt(b))
        return
    }
}

class CmdCmpLteInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) <= frame.getInt(b))
        return
    }
}

class CmdCmpLteIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalBool(dst, frame.getLocalInt(a) <= frame.getLocalInt(b))
        return
    }
}

class CmdCmpGtInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) > frame.getInt(b))
        return
    }
}

class CmdCmpGtIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalBool(dst, frame.getLocalInt(a) > frame.getLocalInt(b))
        return
    }
}

class CmdCmpGteInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a) >= frame.getInt(b))
        return
    }
}

class CmdCmpGteIntLocal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setLocalBool(dst, frame.getLocalInt(a) >= frame.getLocalInt(b))
        return
    }
}

class CmdCmpEqReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) == frame.getReal(b))
        return
    }
}

class CmdCmpNeqReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) != frame.getReal(b))
        return
    }
}

class CmdCmpLtReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) < frame.getReal(b))
        return
    }
}

class CmdCmpLteReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) <= frame.getReal(b))
        return
    }
}

class CmdCmpGtReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) > frame.getReal(b))
        return
    }
}

class CmdCmpGteReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) >= frame.getReal(b))
        return
    }
}

class CmdCmpEqBool(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getBool(a) == frame.getBool(b))
        return
    }
}

class CmdCmpNeqBool(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getBool(a) != frame.getBool(b))
        return
    }
}

class CmdCmpEqIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() == frame.getReal(b))
        return
    }
}

class CmdCmpEqRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) == frame.getInt(b).toDouble())
        return
    }
}

class CmdCmpLtIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() < frame.getReal(b))
        return
    }
}

class CmdCmpLtRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) < frame.getInt(b).toDouble())
        return
    }
}

class CmdCmpLteIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() <= frame.getReal(b))
        return
    }
}

class CmdCmpLteRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) <= frame.getInt(b).toDouble())
        return
    }
}

class CmdCmpGtIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() > frame.getReal(b))
        return
    }
}

class CmdCmpGtRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) > frame.getInt(b).toDouble())
        return
    }
}

class CmdCmpGteIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() >= frame.getReal(b))
        return
    }
}

class CmdCmpGteRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) >= frame.getInt(b).toDouble())
        return
    }
}

class CmdCmpNeqIntReal(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getInt(a).toDouble() != frame.getReal(b))
        return
    }
}

class CmdCmpNeqRealInt(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getReal(a) != frame.getInt(b).toDouble())
        return
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

class CmdNotBool(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, !frame.getBool(src))
        return
    }
}

class CmdAndBool(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getBool(a) && frame.getBool(b))
        return
    }
}

class CmdOrBool(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.getBool(a) || frame.getBool(b))
        return
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
        val scopeSlotCount = frame.fn.scopeSlotCount
        if (a >= scopeSlotCount && b >= scopeSlotCount) {
            val la = a - scopeSlotCount
            val lb = b - scopeSlotCount
            val ta = frame.frame.getSlotTypeCode(la)
            val tb = frame.frame.getSlotTypeCode(lb)
            if (ta == SlotType.INT.code && tb == SlotType.INT.code) {
                frame.setInt(dst, frame.frame.getInt(la) + frame.frame.getInt(lb))
                return
            }
            val aNumeric = ta == SlotType.INT.code || ta == SlotType.REAL.code
            val bNumeric = tb == SlotType.INT.code || tb == SlotType.REAL.code
            if (aNumeric && bNumeric && (ta == SlotType.REAL.code || tb == SlotType.REAL.code)) {
                val av = if (ta == SlotType.REAL.code) frame.frame.getReal(la) else frame.frame.getInt(la).toDouble()
                val bv = if (tb == SlotType.REAL.code) frame.frame.getReal(lb) else frame.frame.getInt(lb).toDouble()
                frame.setReal(dst, av + bv)
                return
            }
        }
        val result = frame.slotToObj(a).plus(frame.ensureScope(), frame.slotToObj(b))
        when (result) {
            is ObjInt -> frame.setInt(dst, result.value)
            is ObjReal -> frame.setReal(dst, result.value)
            else -> frame.setObj(dst, result)
        }
        return
    }
}

class CmdSubObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val scopeSlotCount = frame.fn.scopeSlotCount
        if (a >= scopeSlotCount && b >= scopeSlotCount) {
            val la = a - scopeSlotCount
            val lb = b - scopeSlotCount
            val ta = frame.frame.getSlotTypeCode(la)
            val tb = frame.frame.getSlotTypeCode(lb)
            if (ta == SlotType.INT.code && tb == SlotType.INT.code) {
                frame.setInt(dst, frame.frame.getInt(la) - frame.frame.getInt(lb))
                return
            }
            val aNumeric = ta == SlotType.INT.code || ta == SlotType.REAL.code
            val bNumeric = tb == SlotType.INT.code || tb == SlotType.REAL.code
            if (aNumeric && bNumeric && (ta == SlotType.REAL.code || tb == SlotType.REAL.code)) {
                val av = if (ta == SlotType.REAL.code) frame.frame.getReal(la) else frame.frame.getInt(la).toDouble()
                val bv = if (tb == SlotType.REAL.code) frame.frame.getReal(lb) else frame.frame.getInt(lb).toDouble()
                frame.setReal(dst, av - bv)
                return
            }
        }
        val result = frame.slotToObj(a).minus(frame.ensureScope(), frame.slotToObj(b))
        when (result) {
            is ObjInt -> frame.setInt(dst, result.value)
            is ObjReal -> frame.setReal(dst, result.value)
            else -> frame.setObj(dst, result)
        }
        return
    }
}

class CmdMulObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val scopeSlotCount = frame.fn.scopeSlotCount
        if (a >= scopeSlotCount && b >= scopeSlotCount) {
            val la = a - scopeSlotCount
            val lb = b - scopeSlotCount
            val ta = frame.frame.getSlotTypeCode(la)
            val tb = frame.frame.getSlotTypeCode(lb)
            if (ta == SlotType.INT.code && tb == SlotType.INT.code) {
                frame.setInt(dst, frame.frame.getInt(la) * frame.frame.getInt(lb))
                return
            }
            val aNumeric = ta == SlotType.INT.code || ta == SlotType.REAL.code
            val bNumeric = tb == SlotType.INT.code || tb == SlotType.REAL.code
            if (aNumeric && bNumeric && (ta == SlotType.REAL.code || tb == SlotType.REAL.code)) {
                val av = if (ta == SlotType.REAL.code) frame.frame.getReal(la) else frame.frame.getInt(la).toDouble()
                val bv = if (tb == SlotType.REAL.code) frame.frame.getReal(lb) else frame.frame.getInt(lb).toDouble()
                frame.setReal(dst, av * bv)
                return
            }
        }
        val result = frame.slotToObj(a).mul(frame.ensureScope(), frame.slotToObj(b))
        when (result) {
            is ObjInt -> frame.setInt(dst, result.value)
            is ObjReal -> frame.setReal(dst, result.value)
            else -> frame.setObj(dst, result)
        }
        return
    }
}

class CmdDivObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val scopeSlotCount = frame.fn.scopeSlotCount
        if (a >= scopeSlotCount && b >= scopeSlotCount) {
            val la = a - scopeSlotCount
            val lb = b - scopeSlotCount
            val ta = frame.frame.getSlotTypeCode(la)
            val tb = frame.frame.getSlotTypeCode(lb)
            if (ta == SlotType.INT.code && tb == SlotType.INT.code) {
                frame.setInt(dst, frame.frame.getInt(la) / frame.frame.getInt(lb))
                return
            }
            val aNumeric = ta == SlotType.INT.code || ta == SlotType.REAL.code
            val bNumeric = tb == SlotType.INT.code || tb == SlotType.REAL.code
            if (aNumeric && bNumeric && (ta == SlotType.REAL.code || tb == SlotType.REAL.code)) {
                val av = if (ta == SlotType.REAL.code) frame.frame.getReal(la) else frame.frame.getInt(la).toDouble()
                val bv = if (tb == SlotType.REAL.code) frame.frame.getReal(lb) else frame.frame.getInt(lb).toDouble()
                frame.setReal(dst, av / bv)
                return
            }
        }
        val result = frame.slotToObj(a).div(frame.ensureScope(), frame.slotToObj(b))
        when (result) {
            is ObjInt -> frame.setInt(dst, result.value)
            is ObjReal -> frame.setReal(dst, result.value)
            else -> frame.setObj(dst, result)
        }
        return
    }
}

class CmdModObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val scopeSlotCount = frame.fn.scopeSlotCount
        if (a >= scopeSlotCount && b >= scopeSlotCount) {
            val la = a - scopeSlotCount
            val lb = b - scopeSlotCount
            val ta = frame.frame.getSlotTypeCode(la)
            val tb = frame.frame.getSlotTypeCode(lb)
            if (ta == SlotType.INT.code && tb == SlotType.INT.code) {
                frame.setInt(dst, frame.frame.getInt(la) % frame.frame.getInt(lb))
                return
            }
            val aNumeric = ta == SlotType.INT.code || ta == SlotType.REAL.code
            val bNumeric = tb == SlotType.INT.code || tb == SlotType.REAL.code
            if (aNumeric && bNumeric && (ta == SlotType.REAL.code || tb == SlotType.REAL.code)) {
                val av = if (ta == SlotType.REAL.code) frame.frame.getReal(la) else frame.frame.getInt(la).toDouble()
                val bv = if (tb == SlotType.REAL.code) frame.frame.getReal(lb) else frame.frame.getInt(lb).toDouble()
                frame.setReal(dst, av % bv)
                return
            }
        }
        val result = frame.slotToObj(a).mod(frame.ensureScope(), frame.slotToObj(b))
        when (result) {
            is ObjInt -> frame.setInt(dst, result.value)
            is ObjReal -> frame.setReal(dst, result.value)
            else -> frame.setObj(dst, result)
        }
        return
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
    override suspend fun perform(frame: CmdFrame) {
        frame.ip = target
        return
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

class CmdJmpIfFalse(internal val cond: Int, internal val target: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (!frame.getBool(cond)) {
            frame.ip = target
        }
        return
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
        val value = frame.slotToObj(slot).byValueCopy()
        frame.ensureScope().addItem(
            decl.name,
            decl.isMutable,
            value,
            decl.visibility,
            recordType = ObjRecord.Type.Other,
            isTransient = decl.isTransient
        )
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
        val rec = frame.ensureScope().addItem(
            decl.name,
            decl.isMutable,
            ObjNull,
            decl.visibility,
            recordType = ObjRecord.Type.Delegated,
            isTransient = decl.isTransient
        )
        rec.delegate = finalDelegate
        frame.storeObjResult(slot, finalDelegate)
        return
    }
}

class CmdDeclExec(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.DeclExec
            ?: error("DECL_EXEC expects DeclExec at $constId")
        val result = decl.executable.execute(frame.ensureScope())
        frame.storeObjResult(slot, result)
        return
    }
}

class CmdDeclDestructure(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.DestructureDecl
            ?: error("DECL_DESTRUCTURE expects DestructureDecl at $constId")
        val value = frame.slotToObj(slot)
        val scope = frame.ensureScope()
        for (name in decl.names) {
            scope.addItem(name, true, ObjVoid, decl.visibility, isTransient = decl.isTransient)
        }
        decl.pattern.setAt(decl.pos, scope, value)
        if (!decl.isMutable) {
            for (name in decl.names) {
                val rec = scope.objects[name] ?: continue
                val immutableRec = rec.copy(isMutable = false)
                scope.objects[name] = immutableRec
                scope.localBindings[name] = immutableRec
                scope.updateSlotFor(name, immutableRec)
            }
        }
        if (slot >= frame.fn.scopeSlotCount) {
            frame.storeObjResult(slot, ObjVoid)
        }
        return
    }
}

class CmdAssignDestructure(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncFrameToScope(useRefs = true)
        }
        val decl = frame.fn.constants[constId] as? BytecodeConst.DestructureAssign
            ?: error("ASSIGN_DESTRUCTURE expects DestructureAssign at $constId")
        val value = frame.slotToObj(slot)
        decl.pattern.setAt(decl.pos, frame.ensureScope(), value)
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncScopeToFrame()
        }
        frame.storeObjResult(slot, value)
        return
    }
}

class CmdDeclExtProperty(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.ExtensionPropertyDecl
            ?: error("DECL_EXT_PROPERTY expects ExtensionPropertyDecl at $constId")
        val type = frame.ensureScope()[decl.extTypeName]?.value
            ?: frame.ensureScope().raiseSymbolNotFound("class ${decl.extTypeName} not found")
        if (type !is ObjClass) {
            frame.ensureScope().raiseClassCastError("${decl.extTypeName} is not the class instance")
        }
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
        if (decl.property.setter != null) {
            val setterName = extensionPropertySetterName(decl.extTypeName, decl.property.name)
            val setterWrapper = ObjExtensionPropertySetterCallable(decl.property.name, decl.property)
            frame.ensureScope().addItem(setterName, false, setterWrapper, decl.visibility, recordType = ObjRecord.Type.Fun)
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
            // Pooling for Statement-based callables (lambdas) can still alter closure semantics; keep safe path for now.
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
        return rec.value.invoke(scope, recv, Arguments.EMPTY, rec.declaringClass)
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
        val receiver = frame.slotToObj(recvSlot)
        val inst = receiver as? ObjInstance
        val cls = receiver as? ObjClass
        val (fieldIdResolved, fieldOnObjClass) = decodeMemberId(fieldId)
        val (methodIdResolved, methodOnObjClass) = decodeMemberId(methodId)
        val fieldRec = if (fieldIdResolved >= 0) {
            when {
                inst != null -> inst.fieldRecordForId(fieldIdResolved) ?: inst.objClass.fieldRecordForId(fieldIdResolved)
                cls != null && fieldOnObjClass -> cls.objClass.fieldRecordForId(fieldIdResolved)
                cls != null -> cls.fieldRecordForId(fieldIdResolved)
                else -> receiver.objClass.fieldRecordForId(fieldIdResolved)
            }
        } else null
        val rec = fieldRec ?: run {
            if (methodIdResolved >= 0) {
                when {
                    inst != null -> inst.methodRecordForId(methodIdResolved) ?: inst.objClass.methodRecordForId(methodIdResolved)
                    cls != null && methodOnObjClass -> cls.objClass.methodRecordForId(methodIdResolved)
                    cls != null -> cls.methodRecordForId(methodIdResolved)
                    else -> receiver.objClass.methodRecordForId(methodIdResolved)
                }
            } else null
        } ?: frame.ensureScope().raiseSymbolNotFound("member")
        val rawName = rec.memberName ?: "<member>"
        val name = if (receiver is ObjInstance && rawName.contains("::")) {
            rawName.substringAfterLast("::")
        } else {
            rawName
        }
        suspend fun autoCallIfMethod(resolved: ObjRecord, recv: Obj): Obj {
            return if (resolved.type == ObjRecord.Type.Fun && !resolved.isAbstract) {
                resolved.value.invoke(frame.ensureScope(), resolved.receiver ?: recv, Arguments.EMPTY, resolved.declaringClass)
            } else {
                resolved.value
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
        val receiver = frame.slotToObj(recvSlot)
        val inst = receiver as? ObjInstance
        val cls = receiver as? ObjClass
        val (fieldIdResolved, fieldOnObjClass) = decodeMemberId(fieldId)
        val (methodIdResolved, methodOnObjClass) = decodeMemberId(methodId)
        val fieldRec = if (fieldIdResolved >= 0) {
            when {
                inst != null -> inst.fieldRecordForId(fieldIdResolved) ?: inst.objClass.fieldRecordForId(fieldIdResolved)
                cls != null && fieldOnObjClass -> cls.objClass.fieldRecordForId(fieldIdResolved)
                cls != null -> cls.fieldRecordForId(fieldIdResolved)
                else -> receiver.objClass.fieldRecordForId(fieldIdResolved)
            }
        } else null
        val rec = fieldRec ?: run {
            if (methodIdResolved >= 0) {
                when {
                    inst != null -> inst.methodRecordForId(methodIdResolved) ?: inst.objClass.methodRecordForId(methodIdResolved)
                    cls != null && methodOnObjClass -> cls.objClass.methodRecordForId(methodIdResolved)
                    cls != null -> cls.methodRecordForId(methodIdResolved)
                    else -> receiver.objClass.methodRecordForId(methodIdResolved)
                }
            } else null
        } ?: frame.ensureScope().raiseSymbolNotFound("member")
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
        val rec = cls.readField(scope, nameConst.value)
        val value = scope.resolve(rec, nameConst.value)
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
        val decl = rec.declaringClass ?: receiver.objClass
        val result = when (rec.type) {
            ObjRecord.Type.Property -> {
                if (callArgs.isEmpty()) (rec.value as ObjProperty).callGetter(frame.ensureScope(), receiver, decl)
                else frame.ensureScope().raiseError("property $name cannot be called with arguments")
            }
            ObjRecord.Type.Fun -> {
                val callScope = inst?.instanceScope ?: frame.ensureScope()
                rec.value.invoke(callScope, receiver, callArgs, decl)
            }
            ObjRecord.Type.Delegated -> {
                val scope = frame.ensureScope()
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
                        del ?: scope.raiseError("Internal error: delegated member $name has no delegate (tried $storageName)")
                    }
                    is ObjClass -> rec.delegate ?: scope.raiseError("Internal error: delegated member $name has no delegate")
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
        val result = frame.slotToObj(targetSlot).getAt(frame.ensureScope(), frame.slotToObj(indexSlot))
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
        frame.slotToObj(targetSlot).putAt(frame.ensureScope(), frame.slotToObj(indexSlot), frame.slotToObj(valueSlot))
        return
    }
}

class CmdEvalRef(internal val id: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncFrameToScope(useRefs = true)
        }
        val ref = frame.fn.constants[id] as? BytecodeConst.Ref
            ?: error("EVAL_REF expects Ref at $id")
        val result = ref.value.evalValue(frame.ensureScope())
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncScopeToFrame()
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdEvalStmt(internal val id: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncFrameToScope(useRefs = true)
        }
        val stmt = frame.fn.constants.getOrNull(id) as? BytecodeConst.StatementVal
            ?: error("EVAL_STMT expects StatementVal at $id")
        val result = stmt.statement.execute(frame.ensureScope())
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncScopeToFrame()
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdMakeValueFn(internal val id: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val valueFn = frame.fn.constants.getOrNull(id) as? BytecodeConst.ValueFn
            ?: error("MAKE_VALUE_FN expects ValueFn at $id")
        val scope = frame.ensureScope()
        val previousCaptures = scope.captureRecords
        val captureRecords = valueFn.captureTableId?.let { frame.buildCaptureRecords(it) }
        scope.captureRecords = captureRecords
        val result = valueFn.fn(scope).value
        scope.captureRecords = previousCaptures
        frame.storeObjResult(dst, result)
        return
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
        var inCatch: Boolean = false
    )
    internal val tryStack = ArrayDeque<TryHandler>()
    private var pendingThrowable: Throwable? = null

    internal val frame = BytecodeFrame(fn.localCount, args.size)
    private val addrScopes: Array<Scope?> = arrayOfNulls(fn.addrCount)
    private val addrIndices: IntArray = IntArray(fn.addrCount)
    private val addrScopeSlots: IntArray = IntArray(fn.addrCount)

    init {
        for (i in args.indices) {
            frame.setObj(frame.argBase + i, args[i])
        }
    }

    internal fun buildCaptureRecords(captureTableId: Int): List<ObjRecord> {
        val table = fn.constants.getOrNull(captureTableId) as? BytecodeConst.CaptureTable
            ?: error("Capture table $captureTableId missing")
        return table.entries.map { entry ->
            when (entry.ownerKind) {
                CaptureOwnerFrameKind.LOCAL -> {
                    val localIndex = entry.slotIndex - fn.scopeSlotCount
                    if (localIndex < 0) {
                        error("Invalid local capture slot ${entry.slotIndex}")
                    }
                    val isMutable = fn.localSlotMutables.getOrNull(localIndex) ?: false
                    val isDelegated = fn.localSlotDelegated.getOrNull(localIndex) ?: false
                    if (isDelegated) {
                        val delegate = frame.getObj(localIndex)
                        ObjRecord(ObjNull, isMutable, type = ObjRecord.Type.Delegated).also {
                            it.delegate = delegate
                        }
                    } else {
                        ObjRecord(FrameSlotRef(frame, localIndex), isMutable)
                    }
                }
                CaptureOwnerFrameKind.MODULE -> {
                    val slot = entry.slotIndex
                    val target = scopeTarget(slot)
                    val index = fn.scopeSlotIndices[slot]
                    target.getSlotRecord(index)
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
            findScopeWithSlot(scope, moduleSlotName)?.let { return it }
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
            if (current is ClosureScope) {
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
            if (current.parent is ModuleScope) return current
            current.parent?.let { queue.add(it) }
            if (current is ClosureScope) {
                queue.add(current.closureScope)
            } else if (current is ApplyScope) {
                queue.add(current.applied)
            }
        }
        return null
    }

    fun ensureScope(): Scope {
        val pos = posForIp(ip - 1)
        if (pos != null && lastScopePosIp != ip) {
            scope.pos = pos
            lastScopePosIp = ip
        }
        return scope
    }

    fun handleException(t: Throwable): Boolean {
        val handler = tryStack.lastOrNull() ?: return false
        val finallyIp = handler.finallyIp
        if (t is ReturnException || t is LoopBreakContinueException) {
            if (finallyIp >= 0) {
                pendingThrowable = t
                ip = finallyIp
                return true
            }
            return false
        }
        if (handler.inCatch) {
            if (finallyIp >= 0) {
                pendingThrowable = t
                ip = finallyIp
                return true
            }
            return false
        }
        handler.inCatch = true
        pendingThrowable = t
        if (handler.catchIp >= 0) {
            val caughtObj = when (t) {
                is ExecutionError -> t.errorObject
                else -> ObjUnknownException(ensureScope(), t.message ?: t.toString())
            }
            storeObjResult(handler.exceptionSlot, caughtObj)
            ip = handler.catchIp
            return true
        }
        if (finallyIp >= 0) {
            ip = finallyIp
            return true
        }
        return false
    }

    fun pushTry(exceptionSlot: Int, catchIp: Int, finallyIp: Int) {
        tryStack.addLast(TryHandler(exceptionSlot, catchIp, finallyIp))
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

    fun pushScope(plan: Map<String, Int>, captures: List<String>) {
        if (scope.skipScopeCreation) {
            val snapshot = scope.applySlotPlanWithSnapshot(plan)
            slotPlanStack.addLast(snapshot)
            virtualDepth += 1
            scopeStack.addLast(scope)
            scopeVirtualStack.addLast(true)
        } else {
            scopeStack.addLast(scope)
            scopeVirtualStack.addLast(false)
            scope = scope.createChildScope()
            if (plan.isNotEmpty()) {
                scope.applySlotPlan(plan)
            }
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
    }

    fun popIterator() {
        iterStack.removeLastOrNull()
    }

    suspend fun cancelTopIterator() {
        val iter = iterStack.removeLastOrNull() ?: return
        iter.invokeInstanceMethod(ensureScope(), "cancelIteration") { ObjVoid }
    }

    suspend fun cancelIterators() {
        while (iterStack.isNotEmpty()) {
            val iter = iterStack.removeLast()
            iter.invokeInstanceMethod(ensureScope(), "cancelIteration") { ObjVoid }
        }
    }

    fun pushSlotPlan(plan: Map<String, Int>) {
        if (scope.hasSlotPlanConflict(plan)) {
            scopeStack.addLast(scope)
            slotPlanScopeStack.addLast(true)
            scope = scope.createChildScope()
            if (plan.isNotEmpty()) {
                scope.applySlotPlan(plan)
            }
        } else {
            val snapshot = scope.applySlotPlanWithSnapshot(plan)
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
            frame.getObj(slot - fn.scopeSlotCount)
        }
    }

    fun setObj(slot: Int, value: Obj) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            target.setSlotValue(index, value)
        } else {
            frame.setObj(slot - fn.scopeSlotCount, value)
        }
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
                SlotType.OBJ.code -> frame.getObj(local).toLong()
                else -> 0L
            }
        }
    }

    fun getLocalInt(local: Int): Long = frame.getInt(local)

    fun setInt(slot: Int, value: Long) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            target.setSlotValue(index, ObjInt.of(value))
        } else {
            frame.setInt(slot - fn.scopeSlotCount, value)
        }
    }

    fun setLocalInt(local: Int, value: Long) {
        frame.setInt(local, value)
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
                SlotType.OBJ.code -> frame.getObj(local).toDouble()
                else -> 0.0
            }
        }
    }

    fun setReal(slot: Int, value: Double) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            target.setSlotValue(index, ObjReal.of(value))
        } else {
            frame.setReal(slot - fn.scopeSlotCount, value)
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
                SlotType.OBJ.code -> frame.getObj(local).toBool()
                else -> false
            }
        }
    }

    fun getLocalBool(local: Int): Boolean = frame.getBool(local)

    fun setBool(slot: Int, value: Boolean) {
        if (slot < fn.scopeSlotCount) {
            val target = scopeTarget(slot)
            val index = ensureScopeSlot(target, slot)
            target.setSlotValue(index, if (value) ObjTrue else ObjFalse)
        } else {
            frame.setBool(slot - fn.scopeSlotCount, value)
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

    fun setAddrObj(addrSlot: Int, value: Obj) {
        setScopeSlotValueAtAddr(addrSlot, value)
    }

    suspend fun getAddrInt(addrSlot: Int): Long {
        return getScopeSlotValueAtAddr(addrSlot).toLong()
    }

    fun setAddrInt(addrSlot: Int, value: Long) {
        setScopeSlotValueAtAddr(addrSlot, ObjInt.of(value))
    }

    suspend fun getAddrReal(addrSlot: Int): Double {
        return getScopeSlotValueAtAddr(addrSlot).toDouble()
    }

    fun setAddrReal(addrSlot: Int, value: Double) {
        setScopeSlotValueAtAddr(addrSlot, ObjReal.of(value))
    }

    suspend fun getAddrBool(addrSlot: Int): Boolean {
        return getScopeSlotValueAtAddr(addrSlot).toBool()
    }

    fun setAddrBool(addrSlot: Int, value: Boolean) {
        setScopeSlotValueAtAddr(addrSlot, if (value) ObjTrue else ObjFalse)
    }

    suspend fun slotToObj(slot: Int): Obj {
        if (slot < fn.scopeSlotCount) {
            return getScopeSlotValue(slot)
        }
        val local = slot - fn.scopeSlotCount
        val localName = fn.localSlotNames.getOrNull(local)
        if (localName != null && fn.localSlotDelegated.getOrNull(local) != true) {
            val rec = scope.getLocalRecordDirect(localName) ?: scope.localBindings[localName]
            if (rec != null && (rec.type == ObjRecord.Type.Delegated || rec.type == ObjRecord.Type.Property || rec.value is ObjProperty)) {
                return scope.resolve(rec, localName)
            }
        }
        return when (frame.getSlotTypeCode(local)) {
            SlotType.INT.code -> ObjInt.of(frame.getInt(local))
            SlotType.REAL.code -> ObjReal.of(frame.getReal(local))
            SlotType.BOOL.code -> if (frame.getBool(local)) ObjTrue else ObjFalse
            SlotType.OBJ.code -> frame.getObj(local)
            else -> ObjVoid
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

    fun syncFrameToScope(useRefs: Boolean = false) {
        val names = fn.localSlotNames
        if (names.isEmpty()) return
        for (i in names.indices) {
            val name = names[i] ?: continue
            if (scopeSlotNames.contains(name)) continue
            val target = resolveLocalScope(i) ?: continue
            val isDelegated = fn.localSlotDelegated.getOrNull(i) == true
            val value = if (useRefs) FrameSlotRef(frame, i) else localSlotToObj(i)
            val rec = target.getLocalRecordDirect(name)
            if (rec == null) {
                val isMutable = fn.localSlotMutables.getOrElse(i) { true }
                if (isDelegated) {
                    val delegatedRec = target.addItem(
                        name,
                        isMutable,
                        ObjNull,
                        recordType = ObjRecord.Type.Delegated
                    )
                    delegatedRec.delegate = localSlotToObj(i)
                } else {
                    target.addItem(name, isMutable, value)
                }
            } else {
                if (isDelegated && rec.type == ObjRecord.Type.Delegated) {
                    rec.delegate = localSlotToObj(i)
                    continue
                }
                val existing = rec.value
                if (existing is FrameSlotRef && !useRefs) continue
                rec.value = value
            }
        }
    }

    fun syncScopeToFrame() {
        val names = fn.localSlotNames
        if (names.isEmpty()) return
        for (i in names.indices) {
            val name = names[i] ?: continue
            val target = resolveLocalScope(i) ?: continue
            val rec = target.getLocalRecordDirect(name) ?: continue
            if (fn.localSlotDelegated.getOrNull(i) == true && rec.type == ObjRecord.Type.Delegated) {
                val delegate = rec.delegate ?: ObjNull
                frame.setObj(i, delegate)
                continue
            }
            val value = rec.value
            if (value is FrameSlotRef) {
                val resolved = value.read()
                when (resolved) {
                    is ObjInt -> frame.setInt(i, resolved.value)
                    is ObjReal -> frame.setReal(i, resolved.value)
                    is ObjBool -> frame.setBool(i, resolved.value)
                    else -> frame.setObj(i, resolved)
                }
                continue
            }
            when (value) {
                is ObjInt -> frame.setInt(i, value.value)
                is ObjReal -> frame.setReal(i, value.value)
                is ObjBool -> frame.setBool(i, value.value)
                else -> frame.setObj(i, value)
            }
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
        return Arguments(positional, plan.tailBlock, named ?: emptyMap())
    }

    private fun resolveLocalScope(localIndex: Int): Scope? {
        return scope
    }

    private fun scopeTarget(slot: Int): Scope {
        return if (slot < fn.scopeSlotCount && fn.scopeSlotIsModule.getOrNull(slot) == true) {
            moduleScope
        } else {
            scope
        }
    }

    private fun localSlotToObj(localIndex: Int): Obj {
        return when (frame.getSlotTypeCode(localIndex)) {
            SlotType.INT.code -> ObjInt.of(frame.getInt(localIndex))
            SlotType.REAL.code -> ObjReal.of(frame.getReal(localIndex))
            SlotType.BOOL.code -> if (frame.getBool(localIndex)) ObjTrue else ObjFalse
            SlotType.OBJ.code -> frame.getObj(localIndex)
            else -> ObjNull
        }
    }

    private suspend fun getScopeSlotValue(slot: Int): Obj {
        val target = scopeTarget(slot)
        val index = ensureScopeSlot(target, slot)
        val record = target.getSlotRecord(index)
        val direct = record.value
        if (direct is FrameSlotRef) return direct.read()
        val name = fn.scopeSlotNames[slot]
        if (name != null && (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || direct is ObjProperty)) {
            return target.resolve(record, name)
        }
        if (direct !== ObjUnset) {
            return direct
        }
        if (name == null) return record.value
        val resolved = target.get(name) ?: return record.value
        if (resolved.value !== ObjUnset) {
            target.updateSlotFor(name, resolved)
        }
        return resolved.value
    }

    private suspend fun getScopeSlotValueAtAddr(addrSlot: Int): Obj {
        val target = addrScopes[addrSlot] ?: error("Address slot $addrSlot is not resolved")
        val index = addrIndices[addrSlot]
        val record = target.getSlotRecord(index)
        val direct = record.value
        if (direct is FrameSlotRef) return direct.read()
        val slotId = addrScopeSlots[addrSlot]
        val name = fn.scopeSlotNames.getOrNull(slotId)
        if (name != null && (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || direct is ObjProperty)) {
            return target.resolve(record, name)
        }
        if (direct !== ObjUnset) {
            return direct
        }
        if (name == null) return record.value
        val resolved = target.get(name) ?: return record.value
        if (resolved.value !== ObjUnset) {
            target.updateSlotFor(name, resolved)
        }
        return resolved.value
    }

    private fun setScopeSlotValueAtAddr(addrSlot: Int, value: Obj) {
        val target = addrScopes[addrSlot] ?: error("Address slot $addrSlot is not resolved")
        val index = addrIndices[addrSlot]
        target.setSlotValue(index, value)
    }

    private fun ensureScopeSlot(target: Scope, slot: Int): Int {
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

    // Scope depth resolution is no longer used; all scope slots are resolved against the current frame.
}
