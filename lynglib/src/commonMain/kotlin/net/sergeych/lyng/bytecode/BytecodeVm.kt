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

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.*

class BytecodeVm {
    suspend fun execute(fn: BytecodeFunction, scope: Scope, args: List<Obj>): Obj {
        val frame = BytecodeFrame(fn.localCount, args.size)
        for (i in args.indices) {
            frame.setObj(frame.argBase + i, args[i])
        }
        val decoder = when (fn.slotWidth) {
            1 -> Decoder8
            2 -> Decoder16
            4 -> Decoder32
            else -> error("Unsupported slot width: ${fn.slotWidth}")
        }
        var ip = 0
        val code = fn.code
        while (ip < code.size) {
            val op = decoder.readOpcode(code, ip)
            ip += 1
            when (op) {
                Opcode.NOP -> {
                    // no-op
                }
                Opcode.CONST_INT -> {
                    val constId = decoder.readConstId(code, ip, fn.constIdWidth)
                    ip += fn.constIdWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val c = fn.constants[constId] as? BytecodeConst.IntVal
                        ?: error("CONST_INT expects IntVal at $constId")
                    frame.setInt(dst, c.value)
                }
                Opcode.CONST_REAL -> {
                    val constId = decoder.readConstId(code, ip, fn.constIdWidth)
                    ip += fn.constIdWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val c = fn.constants[constId] as? BytecodeConst.RealVal
                        ?: error("CONST_REAL expects RealVal at $constId")
                    frame.setReal(dst, c.value)
                }
                Opcode.CONST_BOOL -> {
                    val constId = decoder.readConstId(code, ip, fn.constIdWidth)
                    ip += fn.constIdWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val c = fn.constants[constId] as? BytecodeConst.Bool
                        ?: error("CONST_BOOL expects Bool at $constId")
                    frame.setBool(dst, c.value)
                }
                Opcode.CONST_NULL -> {
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setObj(dst, ObjNull)
                }
                Opcode.MOVE_INT -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(src))
                }
                Opcode.MOVE_REAL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setReal(dst, frame.getReal(src))
                }
                Opcode.MOVE_BOOL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getBool(src))
                }
                Opcode.MOVE_OBJ -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setObj(dst, frame.getObj(src))
                }
                Opcode.ADD_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(a) + frame.getInt(b))
                }
                Opcode.RET -> {
                    val slot = decoder.readSlot(code, ip)
                    return slotToObj(frame, slot)
                }
                Opcode.RET_VOID -> return ObjVoid
                else -> error("Opcode not implemented: $op")
            }
        }
        return ObjVoid
    }

    private fun slotToObj(frame: BytecodeFrame, slot: Int): Obj {
        return when (frame.getSlotTypeCode(slot)) {
            SlotType.INT.code -> ObjInt.of(frame.getInt(slot))
            SlotType.REAL.code -> ObjReal.of(frame.getReal(slot))
            SlotType.BOOL.code -> if (frame.getBool(slot)) ObjTrue else ObjFalse
            SlotType.OBJ.code -> frame.getObj(slot)
            else -> ObjVoid
        }
    }
}
