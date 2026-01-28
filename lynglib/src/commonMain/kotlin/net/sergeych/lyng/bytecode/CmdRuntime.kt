/*
 * Copyright 2026 Sergey S. Chernov
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

package net.sergeych.lyng.bytecode

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.PerfStats
import net.sergeych.lyng.Pos
import net.sergeych.lyng.ReturnException
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Statement
import net.sergeych.lyng.obj.*

class CmdVm {
    var result: Obj? = null

    suspend fun execute(fn: CmdFunction, scope0: Scope, args: List<Obj>): Obj {
        result = null
        val frame = CmdFrame(this, fn, scope0, args)
        val cmds = fn.cmds
        while (result == null) {
            val cmd = cmds[frame.ip]
            frame.ip += 1
            cmd.perform(frame)
        }
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
        val clazz = typeObj as? ObjClass
        frame.setBool(dst, clazz != null && obj.isInstanceOf(clazz))
        return
    }
}

class CmdAssertIs(internal val objSlot: Int, internal val typeSlot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val obj = frame.slotToObj(objSlot)
        val typeObj = frame.slotToObj(typeSlot)
        val clazz = typeObj as? ObjClass ?: frame.scope.raiseClassCastError(
            "${typeObj.inspect(frame.scope)} is not the class instance"
        )
        if (!obj.isInstanceOf(clazz)) {
            frame.scope.raiseClassCastError("expected ${clazz.className}, got ${obj.objClass.className}")
        }
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

class CmdIntToReal(internal val src: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setReal(dst, frame.getInt(src).toDouble())
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
        frame.setBool(dst, frame.getInt(src) != 0L)
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
        frame.setBool(dst, left.equals(frame.scope, right))
        return
    }
}

class CmdCmpNeqObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val left = frame.slotToObj(a)
        val right = frame.slotToObj(b)
        frame.setBool(dst, !left.equals(frame.scope, right))
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
        frame.setBool(dst, frame.slotToObj(a).compareTo(frame.scope, frame.slotToObj(b)) < 0)
        return
    }
}

class CmdCmpLteObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(a).compareTo(frame.scope, frame.slotToObj(b)) <= 0)
        return
    }
}

class CmdCmpGtObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(a).compareTo(frame.scope, frame.slotToObj(b)) > 0)
        return
    }
}

class CmdCmpGteObj(internal val a: Int, internal val b: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(a).compareTo(frame.scope, frame.slotToObj(b)) >= 0)
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
            if (ta == SlotType.REAL.code || tb == SlotType.REAL.code) {
                val av = if (ta == SlotType.REAL.code) frame.frame.getReal(la) else frame.frame.getInt(la).toDouble()
                val bv = if (tb == SlotType.REAL.code) frame.frame.getReal(lb) else frame.frame.getInt(lb).toDouble()
                frame.setReal(dst, av + bv)
                return
            }
        }
        frame.setObj(dst, frame.slotToObj(a).plus(frame.scope, frame.slotToObj(b)))
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
            if (ta == SlotType.REAL.code || tb == SlotType.REAL.code) {
                val av = if (ta == SlotType.REAL.code) frame.frame.getReal(la) else frame.frame.getInt(la).toDouble()
                val bv = if (tb == SlotType.REAL.code) frame.frame.getReal(lb) else frame.frame.getInt(lb).toDouble()
                frame.setReal(dst, av - bv)
                return
            }
        }
        frame.setObj(dst, frame.slotToObj(a).minus(frame.scope, frame.slotToObj(b)))
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
            if (ta == SlotType.REAL.code || tb == SlotType.REAL.code) {
                val av = if (ta == SlotType.REAL.code) frame.frame.getReal(la) else frame.frame.getInt(la).toDouble()
                val bv = if (tb == SlotType.REAL.code) frame.frame.getReal(lb) else frame.frame.getInt(lb).toDouble()
                frame.setReal(dst, av * bv)
                return
            }
        }
        frame.setObj(dst, frame.slotToObj(a).mul(frame.scope, frame.slotToObj(b)))
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
            if (ta == SlotType.REAL.code || tb == SlotType.REAL.code) {
                val av = if (ta == SlotType.REAL.code) frame.frame.getReal(la) else frame.frame.getInt(la).toDouble()
                val bv = if (tb == SlotType.REAL.code) frame.frame.getReal(lb) else frame.frame.getInt(lb).toDouble()
                frame.setReal(dst, av / bv)
                return
            }
        }
        frame.setObj(dst, frame.slotToObj(a).div(frame.scope, frame.slotToObj(b)))
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
            if (ta == SlotType.REAL.code || tb == SlotType.REAL.code) {
                val av = if (ta == SlotType.REAL.code) frame.frame.getReal(la) else frame.frame.getInt(la).toDouble()
                val bv = if (tb == SlotType.REAL.code) frame.frame.getReal(lb) else frame.frame.getInt(lb).toDouble()
                frame.setReal(dst, av % bv)
                return
            }
        }
        frame.setObj(dst, frame.slotToObj(a).mod(frame.scope, frame.slotToObj(b)))
        return
    }
}

class CmdContainsObj(internal val target: Int, internal val value: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        frame.setBool(dst, frame.slotToObj(target).contains(frame.scope, frame.slotToObj(value)))
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

class CmdPushScope(internal val planId: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val planConst = frame.fn.constants[planId] as? BytecodeConst.SlotPlan
            ?: error("PUSH_SCOPE expects SlotPlan at $planId")
        frame.pushScope(planConst.plan)
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

class CmdDeclLocal(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.LocalDecl
            ?: error("DECL_LOCAL expects LocalDecl at $constId")
        val value = frame.slotToObj(slot).byValueCopy()
        frame.scope.addItem(
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

class CmdDeclExtProperty(internal val constId: Int, internal val slot: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val decl = frame.fn.constants[constId] as? BytecodeConst.ExtensionPropertyDecl
            ?: error("DECL_EXT_PROPERTY expects ExtensionPropertyDecl at $constId")
        val type = frame.scope[decl.extTypeName]?.value
            ?: frame.scope.raiseSymbolNotFound("class ${decl.extTypeName} not found")
        if (type !is ObjClass) {
            frame.scope.raiseClassCastError("${decl.extTypeName} is not the class instance")
        }
        frame.scope.addExtension(
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
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncFrameToScope()
        }
        val ref = frame.fn.constants.getOrNull(id) as? BytecodeConst.ObjRef
            ?: error("CALL_DIRECT expects ObjRef at $id")
        val callee = ref.value
        val args = frame.buildArguments(argBase, argCount)
        val result = if (PerfFlags.SCOPE_POOL) {
            frame.scope.withChildFrame(args) { child -> callee.callOn(child) }
        } else {
            callee.callOn(frame.scope.createChildScope(frame.scope.pos, args = args))
        }
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncScopeToFrame()
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdCallVirtual(
    internal val recvSlot: Int,
    internal val methodId: Int,
    internal val argBase: Int,
    internal val argCount: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncFrameToScope()
        }
        val receiver = frame.slotToObj(recvSlot)
        val nameConst = frame.fn.constants.getOrNull(methodId) as? BytecodeConst.StringVal
            ?: error("CALL_VIRTUAL expects StringVal at $methodId")
        val args = frame.buildArguments(argBase, argCount)
        val site = frame.methodCallSites.getOrPut(frame.ip - 1) { MethodCallSite(nameConst.value) }
        val result = site.invoke(frame.scope, receiver, args)
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncScopeToFrame()
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdCallFallback(
    internal val id: Int,
    internal val argBase: Int,
    internal val argCount: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncFrameToScope()
        }
        val stmt = frame.fn.fallbackStatements.getOrNull(id)
            ?: error("Fallback statement not found: $id")
        val args = frame.buildArguments(argBase, argCount)
        val result = if (PerfFlags.SCOPE_POOL) {
            frame.scope.withChildFrame(args) { child -> stmt.execute(child) }
        } else {
            stmt.execute(frame.scope.createChildScope(frame.scope.pos, args = args))
        }
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncScopeToFrame()
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
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncFrameToScope()
        }
        val callee = frame.slotToObj(calleeSlot)
        if (callee === ObjUnset) {
            val name = if (calleeSlot < frame.fn.scopeSlotCount) {
                frame.fn.scopeSlotNames[calleeSlot]
            } else {
                val localIndex = calleeSlot - frame.fn.scopeSlotCount
                frame.fn.localSlotNames.getOrNull(localIndex)
            }
            val message = name?.let { "property '$it' is unset (not initialized)" }
                ?: "property is unset (not initialized)"
            frame.scope.raiseUnset(message)
        }
        val args = frame.buildArguments(argBase, argCount)
        val canPool = PerfFlags.SCOPE_POOL && callee !is Statement
        val result = if (canPool) {
            frame.scope.withChildFrame(args) { child -> callee.callOn(child) }
        } else {
            // Pooling for Statement-based callables (lambdas) can still alter closure semantics; keep safe path for now.
            callee.callOn(frame.scope.createChildScope(frame.scope.pos, args = args))
        }
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncScopeToFrame()
        }
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdGetField(
    internal val recvSlot: Int,
    internal val fieldId: Int,
    internal val dst: Int,
) : Cmd() {
    private var rKey: Long = 0L
    private var rVer: Int = -1

    override suspend fun perform(frame: CmdFrame) {
        val receiver = frame.slotToObj(recvSlot)
        val nameConst = frame.fn.constants.getOrNull(fieldId) as? BytecodeConst.StringVal
            ?: error("GET_FIELD expects StringVal at $fieldId")
        if (PerfFlags.FIELD_PIC) {
            val (key, ver) = when (receiver) {
                is ObjInstance -> receiver.objClass.classId to receiver.objClass.layoutVersion
                is ObjClass -> receiver.classId to receiver.layoutVersion
                else -> 0L to -1
            }
            if (key != 0L) {
                if (key == rKey && ver == rVer) {
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.fieldPicHit++
                } else {
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.fieldPicMiss++
                    rKey = key
                    rVer = ver
                }
            }
        }
        val result = receiver.readField(frame.scope, nameConst.value).value
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdGetName(
    internal val nameId: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncFrameToScope()
        }
        val nameConst = frame.fn.constants.getOrNull(nameId) as? BytecodeConst.StringVal
            ?: error("GET_NAME expects StringVal at $nameId")
        val result = frame.scope.get(nameConst.value)?.value ?: ObjUnset
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdSetField(
    internal val recvSlot: Int,
    internal val fieldId: Int,
    internal val valueSlot: Int,
) : Cmd() {
    private var wKey: Long = 0L
    private var wVer: Int = -1

    override suspend fun perform(frame: CmdFrame) {
        val receiver = frame.slotToObj(recvSlot)
        val nameConst = frame.fn.constants.getOrNull(fieldId) as? BytecodeConst.StringVal
            ?: error("SET_FIELD expects StringVal at $fieldId")
        if (PerfFlags.FIELD_PIC) {
            val (key, ver) = when (receiver) {
                is ObjInstance -> receiver.objClass.classId to receiver.objClass.layoutVersion
                is ObjClass -> receiver.classId to receiver.layoutVersion
                else -> 0L to -1
            }
            if (key != 0L) {
                if (key == wKey && ver == wVer) {
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.fieldPicSetHit++
                } else {
                    if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.fieldPicSetMiss++
                    wKey = key
                    wVer = ver
                }
            }
        }
        receiver.writeField(frame.scope, nameConst.value, frame.slotToObj(valueSlot))
        return
    }
}

class CmdGetIndex(
    internal val targetSlot: Int,
    internal val indexSlot: Int,
    internal val dst: Int,
) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val result = frame.slotToObj(targetSlot).getAt(frame.scope, frame.slotToObj(indexSlot))
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
        frame.slotToObj(targetSlot).putAt(frame.scope, frame.slotToObj(indexSlot), frame.slotToObj(valueSlot))
        return
    }
}

class CmdEvalFallback(internal val id: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        val stmt = frame.fn.fallbackStatements.getOrNull(id)
            ?: error("Fallback statement not found: $id")
        frame.syncFrameToScope()
        val result = stmt.execute(frame.scope)
        frame.syncScopeToFrame()
        frame.storeObjResult(dst, result)
        return
    }
}

class CmdEvalRef(internal val id: Int, internal val dst: Int) : Cmd() {
    override suspend fun perform(frame: CmdFrame) {
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncFrameToScope()
        }
        val ref = frame.fn.constants[id] as? BytecodeConst.Ref
            ?: error("EVAL_REF expects Ref at $id")
        val result = ref.value.evalValue(frame.scope)
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
            frame.syncFrameToScope()
        }
        val stmt = frame.fn.constants.getOrNull(id) as? BytecodeConst.StatementVal
            ?: error("EVAL_STMT expects StatementVal at $id")
        val result = stmt.statement.execute(frame.scope)
        if (frame.fn.localSlotNames.isNotEmpty()) {
            frame.syncScopeToFrame()
        }
        frame.storeObjResult(dst, result)
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
    val methodCallSites: MutableMap<Int, MethodCallSite> = CmdCallSiteCache.methodCallSites(fn)

    internal val scopeStack = ArrayDeque<Scope>()
    internal val scopeVirtualStack = ArrayDeque<Boolean>()
    internal val slotPlanStack = ArrayDeque<Map<String, Int?>>()
    internal val slotPlanScopeStack = ArrayDeque<Boolean>()
    private var scopeDepth = 0
    private var virtualDepth = 0

    internal val frame = BytecodeFrame(fn.localCount, args.size)
    private val addrScopes: Array<Scope?> = arrayOfNulls(fn.addrCount)
    private val addrIndices: IntArray = IntArray(fn.addrCount)
    private val addrScopeSlots: IntArray = IntArray(fn.addrCount)

    init {
        for (i in args.indices) {
            frame.setObj(frame.argBase + i, args[i])
        }
    }

    fun pushScope(plan: Map<String, Int>) {
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
        scopeDepth -= 1
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

    fun getObj(slot: Int): Obj {
        return if (slot < fn.scopeSlotCount) {
            getScopeSlotValue(slot)
        } else {
            frame.getObj(slot - fn.scopeSlotCount)
        }
    }

    fun setObj(slot: Int, value: Obj) {
        if (slot < fn.scopeSlotCount) {
            val target = resolveScope(scope, fn.scopeSlotDepths[slot])
            val index = ensureScopeSlot(target, slot)
            target.setSlotValue(index, value)
        } else {
            frame.setObj(slot - fn.scopeSlotCount, value)
        }
    }

    fun getInt(slot: Int): Long {
        return if (slot < fn.scopeSlotCount) {
            getScopeSlotValue(slot).toLong()
        } else {
            frame.getInt(slot - fn.scopeSlotCount)
        }
    }

    fun getLocalInt(local: Int): Long = frame.getInt(local)

    fun setInt(slot: Int, value: Long) {
        if (slot < fn.scopeSlotCount) {
            val target = resolveScope(scope, fn.scopeSlotDepths[slot])
            val index = ensureScopeSlot(target, slot)
            target.setSlotValue(index, ObjInt.of(value))
        } else {
            frame.setInt(slot - fn.scopeSlotCount, value)
        }
    }

    fun setLocalInt(local: Int, value: Long) {
        frame.setInt(local, value)
    }

    fun getReal(slot: Int): Double {
        return if (slot < fn.scopeSlotCount) {
            getScopeSlotValue(slot).toDouble()
        } else {
            frame.getReal(slot - fn.scopeSlotCount)
        }
    }

    fun setReal(slot: Int, value: Double) {
        if (slot < fn.scopeSlotCount) {
            val target = resolveScope(scope, fn.scopeSlotDepths[slot])
            val index = ensureScopeSlot(target, slot)
            target.setSlotValue(index, ObjReal.of(value))
        } else {
            frame.setReal(slot - fn.scopeSlotCount, value)
        }
    }

    fun getBool(slot: Int): Boolean {
        return if (slot < fn.scopeSlotCount) {
            getScopeSlotValue(slot).toBool()
        } else {
            frame.getBool(slot - fn.scopeSlotCount)
        }
    }

    fun getLocalBool(local: Int): Boolean = frame.getBool(local)

    fun setBool(slot: Int, value: Boolean) {
        if (slot < fn.scopeSlotCount) {
            val target = resolveScope(scope, fn.scopeSlotDepths[slot])
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
        val target = resolveScope(scope, fn.scopeSlotDepths[scopeSlot])
        val index = ensureScopeSlot(target, scopeSlot)
        addrScopes[addrSlot] = target
        addrIndices[addrSlot] = index
        addrScopeSlots[addrSlot] = scopeSlot
    }

    fun getAddrObj(addrSlot: Int): Obj {
        return getScopeSlotValueAtAddr(addrSlot)
    }

    fun setAddrObj(addrSlot: Int, value: Obj) {
        setScopeSlotValueAtAddr(addrSlot, value)
    }

    fun getAddrInt(addrSlot: Int): Long {
        return getScopeSlotValueAtAddr(addrSlot).toLong()
    }

    fun setAddrInt(addrSlot: Int, value: Long) {
        setScopeSlotValueAtAddr(addrSlot, ObjInt.of(value))
    }

    fun getAddrReal(addrSlot: Int): Double {
        return getScopeSlotValueAtAddr(addrSlot).toDouble()
    }

    fun setAddrReal(addrSlot: Int, value: Double) {
        setScopeSlotValueAtAddr(addrSlot, ObjReal.of(value))
    }

    fun getAddrBool(addrSlot: Int): Boolean {
        return getScopeSlotValueAtAddr(addrSlot).toBool()
    }

    fun setAddrBool(addrSlot: Int, value: Boolean) {
        setScopeSlotValueAtAddr(addrSlot, if (value) ObjTrue else ObjFalse)
    }

    fun slotToObj(slot: Int): Obj {
        if (slot < fn.scopeSlotCount) {
            return getScopeSlotValue(slot)
        }
        val local = slot - fn.scopeSlotCount
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
        val throwScope = scope.createChildScope(pos = pos)
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

    fun syncFrameToScope() {
        val names = fn.localSlotNames
        if (names.isEmpty()) return
        for (i in names.indices) {
            val name = names[i] ?: continue
            val target = resolveLocalScope(i) ?: continue
            val value = localSlotToObj(i)
            val rec = target.getLocalRecordDirect(name)
            if (rec == null) {
                val isMutable = fn.localSlotMutables.getOrElse(i) { true }
                target.addItem(name, isMutable, value)
            } else {
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
            val value = rec.value
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
        val depth = fn.localSlotDepths.getOrNull(localIndex) ?: return scope
        val relativeDepth = scopeDepth - depth
        if (relativeDepth < 0) return null
        return if (relativeDepth == 0) scope else resolveScope(scope, relativeDepth)
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

    private fun getScopeSlotValue(slot: Int): Obj {
        val target = resolveScope(scope, fn.scopeSlotDepths[slot])
        val index = ensureScopeSlot(target, slot)
        val record = target.getSlotRecord(index)
        if (record.value !== ObjUnset) return record.value
        val name = fn.scopeSlotNames[slot] ?: return record.value
        val resolved = target.get(name) ?: return record.value
        if (resolved.value !== ObjUnset) {
            target.updateSlotFor(name, resolved)
        }
        return resolved.value
    }

    private fun getScopeSlotValueAtAddr(addrSlot: Int): Obj {
        val target = addrScopes[addrSlot] ?: error("Address slot $addrSlot is not resolved")
        val index = addrIndices[addrSlot]
        val record = target.getSlotRecord(index)
        if (record.value !== ObjUnset) return record.value
        val slotId = addrScopeSlots[addrSlot]
        val name = fn.scopeSlotNames[slotId] ?: return record.value
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
        if (index < target.slotCount) return index
        if (name == null) return index
        target.applySlotPlan(mapOf(name to index))
        val existing = target.getLocalRecordDirect(name)
        if (existing != null) {
            target.updateSlotFor(name, existing)
        } else {
            val resolved = target.get(name)
            if (resolved != null) {
                target.updateSlotFor(name, resolved)
            }
        }
        return index
    }

    private fun resolveScope(start: Scope, depth: Int): Scope {
        if (depth == 0) return start
        var effectiveDepth = depth
        if (virtualDepth > 0) {
            if (effectiveDepth <= virtualDepth) return start
            effectiveDepth -= virtualDepth
        }
        val next = when (start) {
            is net.sergeych.lyng.ClosureScope -> start.closureScope
            else -> start.parent
        }
        return next?.let { resolveScope(it, effectiveDepth - 1) }
            ?: error("Scope depth $depth is out of range")
    }
}
