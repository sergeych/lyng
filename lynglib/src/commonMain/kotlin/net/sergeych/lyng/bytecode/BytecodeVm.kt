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
                Opcode.CONST_OBJ -> {
                    val constId = decoder.readConstId(code, ip, fn.constIdWidth)
                    ip += fn.constIdWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    when (val c = fn.constants[constId]) {
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
                Opcode.INT_TO_REAL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setReal(dst, frame.getInt(src).toDouble())
                }
                Opcode.REAL_TO_INT -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getReal(src).toLong())
                }
                Opcode.BOOL_TO_INT -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, if (frame.getBool(src)) 1L else 0L)
                }
                Opcode.INT_TO_BOOL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(src) != 0L)
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
                Opcode.SUB_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(a) - frame.getInt(b))
                }
                Opcode.MUL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(a) * frame.getInt(b))
                }
                Opcode.DIV_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(a) / frame.getInt(b))
                }
                Opcode.MOD_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(a) % frame.getInt(b))
                }
                Opcode.NEG_INT -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, -frame.getInt(src))
                }
                Opcode.INC_INT -> {
                    val slot = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(slot, frame.getInt(slot) + 1L)
                }
                Opcode.DEC_INT -> {
                    val slot = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(slot, frame.getInt(slot) - 1L)
                }
                Opcode.ADD_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setReal(dst, frame.getReal(a) + frame.getReal(b))
                }
                Opcode.SUB_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setReal(dst, frame.getReal(a) - frame.getReal(b))
                }
                Opcode.MUL_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setReal(dst, frame.getReal(a) * frame.getReal(b))
                }
                Opcode.DIV_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setReal(dst, frame.getReal(a) / frame.getReal(b))
                }
                Opcode.NEG_REAL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setReal(dst, -frame.getReal(src))
                }
                Opcode.AND_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(a) and frame.getInt(b))
                }
                Opcode.OR_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(a) or frame.getInt(b))
                }
                Opcode.XOR_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(a) xor frame.getInt(b))
                }
                Opcode.SHL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(a) shl frame.getInt(b).toInt())
                }
                Opcode.SHR_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(a) shr frame.getInt(b).toInt())
                }
                Opcode.USHR_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(a) ushr frame.getInt(b).toInt())
                }
                Opcode.INV_INT -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setInt(dst, frame.getInt(src).inv())
                }
                Opcode.CMP_LT_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a) < frame.getInt(b))
                }
                Opcode.CMP_LTE_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a) <= frame.getInt(b))
                }
                Opcode.CMP_GT_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a) > frame.getInt(b))
                }
                Opcode.CMP_GTE_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a) >= frame.getInt(b))
                }
                Opcode.CMP_EQ_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a) == frame.getInt(b))
                }
                Opcode.CMP_NEQ_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a) != frame.getInt(b))
                }
                Opcode.CMP_EQ_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) == frame.getReal(b))
                }
                Opcode.CMP_NEQ_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) != frame.getReal(b))
                }
                Opcode.CMP_LT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) < frame.getReal(b))
                }
                Opcode.CMP_LTE_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) <= frame.getReal(b))
                }
                Opcode.CMP_GT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) > frame.getReal(b))
                }
                Opcode.CMP_GTE_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) >= frame.getReal(b))
                }
                Opcode.CMP_EQ_BOOL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getBool(a) == frame.getBool(b))
                }
                Opcode.CMP_NEQ_BOOL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getBool(a) != frame.getBool(b))
                }
                Opcode.CMP_EQ_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a).toDouble() == frame.getReal(b))
                }
                Opcode.CMP_EQ_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) == frame.getInt(b).toDouble())
                }
                Opcode.CMP_LT_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a).toDouble() < frame.getReal(b))
                }
                Opcode.CMP_LT_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) < frame.getInt(b).toDouble())
                }
                Opcode.CMP_LTE_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a).toDouble() <= frame.getReal(b))
                }
                Opcode.CMP_LTE_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) <= frame.getInt(b).toDouble())
                }
                Opcode.CMP_GT_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a).toDouble() > frame.getReal(b))
                }
                Opcode.CMP_GT_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) > frame.getInt(b).toDouble())
                }
                Opcode.CMP_GTE_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a).toDouble() >= frame.getReal(b))
                }
                Opcode.CMP_GTE_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) >= frame.getInt(b).toDouble())
                }
                Opcode.CMP_NEQ_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getInt(a).toDouble() != frame.getReal(b))
                }
                Opcode.CMP_NEQ_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getReal(a) != frame.getInt(b).toDouble())
                }
                Opcode.CMP_EQ_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getObj(a).equals(scope, frame.getObj(b)))
                }
                Opcode.CMP_NEQ_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, !frame.getObj(a).equals(scope, frame.getObj(b)))
                }
                Opcode.CMP_REF_EQ_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getObj(a) === frame.getObj(b))
                }
                Opcode.CMP_REF_NEQ_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getObj(a) !== frame.getObj(b))
                }
                Opcode.CMP_LT_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getObj(a).compareTo(scope, frame.getObj(b)) < 0)
                }
                Opcode.CMP_LTE_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getObj(a).compareTo(scope, frame.getObj(b)) <= 0)
                }
                Opcode.CMP_GT_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getObj(a).compareTo(scope, frame.getObj(b)) > 0)
                }
                Opcode.CMP_GTE_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getObj(a).compareTo(scope, frame.getObj(b)) >= 0)
                }
                Opcode.NOT_BOOL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, !frame.getBool(src))
                }
                Opcode.AND_BOOL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getBool(a) && frame.getBool(b))
                }
                Opcode.OR_BOOL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    frame.setBool(dst, frame.getBool(a) || frame.getBool(b))
                }
                Opcode.JMP -> {
                    val target = decoder.readIp(code, ip, fn.ipWidth)
                    ip = target
                }
                Opcode.JMP_IF_FALSE -> {
                    val cond = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val target = decoder.readIp(code, ip, fn.ipWidth)
                    ip += fn.ipWidth
                    if (!frame.getBool(cond)) {
                        ip = target
                    }
                }
                Opcode.JMP_IF_TRUE -> {
                    val cond = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val target = decoder.readIp(code, ip, fn.ipWidth)
                    ip += fn.ipWidth
                    if (frame.getBool(cond)) {
                        ip = target
                    }
                }
                Opcode.EVAL_FALLBACK -> {
                    val id = decoder.readConstId(code, ip, 2)
                    ip += 2
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val stmt = fn.fallbackStatements.getOrNull(id)
                        ?: error("Fallback statement not found: $id")
                    val result = stmt.execute(scope)
                    when (result) {
                        is ObjInt -> frame.setInt(dst, result.value)
                        is ObjReal -> frame.setReal(dst, result.value)
                        is ObjBool -> frame.setBool(dst, result.value)
                        else -> frame.setObj(dst, result)
                    }
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
