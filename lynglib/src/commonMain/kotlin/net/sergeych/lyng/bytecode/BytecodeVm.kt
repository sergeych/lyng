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
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.*

class BytecodeVm {
    suspend fun execute(fn: BytecodeFunction, scope0: Scope, args: List<Obj>): Obj {
        val scopeStack = ArrayDeque<Scope>()
        var scope = scope0
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
            val startIp = ip
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
                    setInt(fn, frame, scope, dst, c.value)
                }
                Opcode.CONST_REAL -> {
                    val constId = decoder.readConstId(code, ip, fn.constIdWidth)
                    ip += fn.constIdWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val c = fn.constants[constId] as? BytecodeConst.RealVal
                        ?: error("CONST_REAL expects RealVal at $constId")
                    setReal(fn, frame, scope, dst, c.value)
                }
                Opcode.CONST_BOOL -> {
                    val constId = decoder.readConstId(code, ip, fn.constIdWidth)
                    ip += fn.constIdWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val c = fn.constants[constId] as? BytecodeConst.Bool
                        ?: error("CONST_BOOL expects Bool at $constId")
                    setBool(fn, frame, scope, dst, c.value)
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
                                is ObjInt -> setInt(fn, frame, scope, dst, obj.value)
                                is ObjReal -> setReal(fn, frame, scope, dst, obj.value)
                                is ObjBool -> setBool(fn, frame, scope, dst, obj.value)
                                else -> setObj(fn, frame, scope, dst, obj)
                            }
                        }
                        is BytecodeConst.StringVal -> setObj(fn, frame, scope, dst, ObjString(c.value))
                        else -> error("CONST_OBJ expects ObjRef/StringVal at $constId")
                    }
                }
                Opcode.CONST_NULL -> {
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setObj(fn, frame, scope, dst, ObjNull)
                }
                Opcode.MOVE_INT -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, src))
                }
                Opcode.MOVE_REAL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setReal(fn, frame, scope, dst, getReal(fn, frame, scope, src))
                }
                Opcode.MOVE_BOOL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getBool(fn, frame, scope, src))
                }
                Opcode.MOVE_OBJ -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setObj(fn, frame, scope, dst, getObj(fn, frame, scope, src))
                }
                Opcode.BOX_OBJ -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setObj(fn, frame, scope, dst, slotToObj(fn, frame, scope, src))
                }
                Opcode.INT_TO_REAL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setReal(fn, frame, scope, dst, getInt(fn, frame, scope, src).toDouble())
                }
                Opcode.REAL_TO_INT -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getReal(fn, frame, scope, src).toLong())
                }
                Opcode.BOOL_TO_INT -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, if (getBool(fn, frame, scope, src)) 1L else 0L)
                }
                Opcode.INT_TO_BOOL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, src) != 0L)
                }
                Opcode.ADD_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, a) + getInt(fn, frame, scope, b))
                }
                Opcode.SUB_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, a) - getInt(fn, frame, scope, b))
                }
                Opcode.MUL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, a) * getInt(fn, frame, scope, b))
                }
                Opcode.DIV_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, a) / getInt(fn, frame, scope, b))
                }
                Opcode.MOD_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, a) % getInt(fn, frame, scope, b))
                }
                Opcode.NEG_INT -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, -getInt(fn, frame, scope, src))
                }
                Opcode.INC_INT -> {
                    val slot = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, slot, getInt(fn, frame, scope, slot) + 1L)
                }
                Opcode.DEC_INT -> {
                    val slot = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, slot, getInt(fn, frame, scope, slot) - 1L)
                }
                Opcode.ADD_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setReal(fn, frame, scope, dst, getReal(fn, frame, scope, a) + getReal(fn, frame, scope, b))
                }
                Opcode.SUB_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setReal(fn, frame, scope, dst, getReal(fn, frame, scope, a) - getReal(fn, frame, scope, b))
                }
                Opcode.MUL_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setReal(fn, frame, scope, dst, getReal(fn, frame, scope, a) * getReal(fn, frame, scope, b))
                }
                Opcode.DIV_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setReal(fn, frame, scope, dst, getReal(fn, frame, scope, a) / getReal(fn, frame, scope, b))
                }
                Opcode.NEG_REAL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setReal(fn, frame, scope, dst, -getReal(fn, frame, scope, src))
                }
                Opcode.AND_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, a) and getInt(fn, frame, scope, b))
                }
                Opcode.OR_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, a) or getInt(fn, frame, scope, b))
                }
                Opcode.XOR_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, a) xor getInt(fn, frame, scope, b))
                }
                Opcode.SHL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, a) shl getInt(fn, frame, scope, b).toInt())
                }
                Opcode.SHR_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, a) shr getInt(fn, frame, scope, b).toInt())
                }
                Opcode.USHR_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, a) ushr getInt(fn, frame, scope, b).toInt())
                }
                Opcode.INV_INT -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setInt(fn, frame, scope, dst, getInt(fn, frame, scope, src).inv())
                }
                Opcode.CMP_LT_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a) < getInt(fn, frame, scope, b))
                }
                Opcode.CMP_LTE_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a) <= getInt(fn, frame, scope, b))
                }
                Opcode.CMP_GT_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a) > getInt(fn, frame, scope, b))
                }
                Opcode.CMP_GTE_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a) >= getInt(fn, frame, scope, b))
                }
                Opcode.CMP_EQ_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a) == getInt(fn, frame, scope, b))
                }
                Opcode.CMP_NEQ_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a) != getInt(fn, frame, scope, b))
                }
                Opcode.CMP_EQ_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) == getReal(fn, frame, scope, b))
                }
                Opcode.CMP_NEQ_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) != getReal(fn, frame, scope, b))
                }
                Opcode.CMP_LT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) < getReal(fn, frame, scope, b))
                }
                Opcode.CMP_LTE_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) <= getReal(fn, frame, scope, b))
                }
                Opcode.CMP_GT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) > getReal(fn, frame, scope, b))
                }
                Opcode.CMP_GTE_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) >= getReal(fn, frame, scope, b))
                }
                Opcode.CMP_EQ_BOOL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getBool(fn, frame, scope, a) == getBool(fn, frame, scope, b))
                }
                Opcode.CMP_NEQ_BOOL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getBool(fn, frame, scope, a) != getBool(fn, frame, scope, b))
                }
                Opcode.CMP_EQ_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a).toDouble() == getReal(fn, frame, scope, b))
                }
                Opcode.CMP_EQ_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) == getInt(fn, frame, scope, b).toDouble())
                }
                Opcode.CMP_LT_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a).toDouble() < getReal(fn, frame, scope, b))
                }
                Opcode.CMP_LT_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) < getInt(fn, frame, scope, b).toDouble())
                }
                Opcode.CMP_LTE_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a).toDouble() <= getReal(fn, frame, scope, b))
                }
                Opcode.CMP_LTE_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) <= getInt(fn, frame, scope, b).toDouble())
                }
                Opcode.CMP_GT_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a).toDouble() > getReal(fn, frame, scope, b))
                }
                Opcode.CMP_GT_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) > getInt(fn, frame, scope, b).toDouble())
                }
                Opcode.CMP_GTE_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a).toDouble() >= getReal(fn, frame, scope, b))
                }
                Opcode.CMP_GTE_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) >= getInt(fn, frame, scope, b).toDouble())
                }
                Opcode.CMP_NEQ_INT_REAL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getInt(fn, frame, scope, a).toDouble() != getReal(fn, frame, scope, b))
                }
                Opcode.CMP_NEQ_REAL_INT -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getReal(fn, frame, scope, a) != getInt(fn, frame, scope, b).toDouble())
                }
                Opcode.CMP_EQ_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getObj(fn, frame, scope, a).equals(scope, getObj(fn, frame, scope, b)))
                }
                Opcode.CMP_NEQ_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, !getObj(fn, frame, scope, a).equals(scope, getObj(fn, frame, scope, b)))
                }
                Opcode.CMP_REF_EQ_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getObj(fn, frame, scope, a) === getObj(fn, frame, scope, b))
                }
                Opcode.CMP_REF_NEQ_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getObj(fn, frame, scope, a) !== getObj(fn, frame, scope, b))
                }
                Opcode.CMP_LT_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getObj(fn, frame, scope, a).compareTo(scope, getObj(fn, frame, scope, b)) < 0)
                }
                Opcode.CMP_LTE_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getObj(fn, frame, scope, a).compareTo(scope, getObj(fn, frame, scope, b)) <= 0)
                }
                Opcode.CMP_GT_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getObj(fn, frame, scope, a).compareTo(scope, getObj(fn, frame, scope, b)) > 0)
                }
                Opcode.CMP_GTE_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getObj(fn, frame, scope, a).compareTo(scope, getObj(fn, frame, scope, b)) >= 0)
                }
                Opcode.ADD_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setObj(fn, frame, scope, dst, getObj(fn, frame, scope, a).plus(scope, getObj(fn, frame, scope, b)))
                }
                Opcode.SUB_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setObj(fn, frame, scope, dst, getObj(fn, frame, scope, a).minus(scope, getObj(fn, frame, scope, b)))
                }
                Opcode.MUL_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setObj(fn, frame, scope, dst, getObj(fn, frame, scope, a).mul(scope, getObj(fn, frame, scope, b)))
                }
                Opcode.DIV_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setObj(fn, frame, scope, dst, getObj(fn, frame, scope, a).div(scope, getObj(fn, frame, scope, b)))
                }
                Opcode.MOD_OBJ -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setObj(fn, frame, scope, dst, getObj(fn, frame, scope, a).mod(scope, getObj(fn, frame, scope, b)))
                }
                Opcode.NOT_BOOL -> {
                    val src = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, !getBool(fn, frame, scope, src))
                }
                Opcode.AND_BOOL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getBool(fn, frame, scope, a) && getBool(fn, frame, scope, b))
                }
                Opcode.OR_BOOL -> {
                    val a = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val b = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    setBool(fn, frame, scope, dst, getBool(fn, frame, scope, a) || getBool(fn, frame, scope, b))
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
                    if (!getBool(fn, frame, scope, cond)) {
                        ip = target
                    }
                }
                Opcode.JMP_IF_TRUE -> {
                    val cond = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val target = decoder.readIp(code, ip, fn.ipWidth)
                    ip += fn.ipWidth
                    if (getBool(fn, frame, scope, cond)) {
                        ip = target
                    }
                }
                Opcode.PUSH_SCOPE -> {
                    val constId = decoder.readConstId(code, ip, fn.constIdWidth)
                    ip += fn.constIdWidth
                    val planConst = fn.constants[constId] as? BytecodeConst.SlotPlan
                        ?: error("PUSH_SCOPE expects SlotPlan at $constId")
                    scopeStack.addLast(scope)
                    scope = scope.createChildScope()
                    if (planConst.plan.isNotEmpty()) {
                        scope.applySlotPlan(planConst.plan)
                    }
                }
                Opcode.POP_SCOPE -> {
                    scope = scopeStack.removeLastOrNull()
                        ?: error("Scope stack underflow in POP_SCOPE")
                }
                Opcode.CALL_SLOT -> {
                    val calleeSlot = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val argBase = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val argCount = decoder.readConstId(code, ip, 2)
                    ip += 2
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val callee = slotToObj(fn, frame, scope, calleeSlot)
                    val args = buildArguments(fn, frame, scope, argBase, argCount)
                    val result = if (PerfFlags.SCOPE_POOL) {
                        scope.withChildFrame(args) { child -> callee.callOn(child) }
                    } else {
                        callee.callOn(scope.createChildScope(scope.pos, args = args))
                    }
                    when (result) {
                        is ObjInt -> setInt(fn, frame, scope, dst, result.value)
                        is ObjReal -> setReal(fn, frame, scope, dst, result.value)
                        is ObjBool -> setBool(fn, frame, scope, dst, result.value)
                        else -> setObj(fn, frame, scope, dst, result)
                    }
                }
                Opcode.CALL_VIRTUAL -> {
                    val recvSlot = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val methodId = decoder.readConstId(code, ip, 2)
                    ip += 2
                    val argBase = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val argCount = decoder.readConstId(code, ip, 2)
                    ip += 2
                    val dst = decoder.readSlot(code, ip)
                    ip += fn.slotWidth
                    val receiver = slotToObj(fn, frame, scope, recvSlot)
                    val nameConst = fn.constants.getOrNull(methodId) as? BytecodeConst.StringVal
                        ?: error("CALL_VIRTUAL expects StringVal at $methodId")
                    val args = buildArguments(fn, frame, scope, argBase, argCount)
                    val site = fn.methodCallSites.getOrPut(startIp) { MethodCallSite(nameConst.value) }
                    val result = site.invoke(scope, receiver, args)
                    when (result) {
                        is ObjInt -> setInt(fn, frame, scope, dst, result.value)
                        is ObjReal -> setReal(fn, frame, scope, dst, result.value)
                        is ObjBool -> setBool(fn, frame, scope, dst, result.value)
                        else -> setObj(fn, frame, scope, dst, result)
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
                        is ObjInt -> setInt(fn, frame, scope, dst, result.value)
                        is ObjReal -> setReal(fn, frame, scope, dst, result.value)
                        is ObjBool -> setBool(fn, frame, scope, dst, result.value)
                        else -> setObj(fn, frame, scope, dst, result)
                    }
                }
                Opcode.RET -> {
                    val slot = decoder.readSlot(code, ip)
                    return slotToObj(fn, frame, scope, slot)
                }
                Opcode.RET_VOID -> return ObjVoid
                else -> error("Opcode not implemented: $op")
            }
        }
        return ObjVoid
    }

    private fun slotToObj(fn: BytecodeFunction, frame: BytecodeFrame, scope: Scope, slot: Int): Obj {
        if (slot < fn.scopeSlotCount) {
            return resolveScope(scope, fn.scopeSlotDepths[slot]).getSlotRecord(fn.scopeSlotIndices[slot]).value
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

    private fun buildArguments(
        fn: BytecodeFunction,
        frame: BytecodeFrame,
        scope: Scope,
        argBase: Int,
        argCount: Int,
    ): Arguments {
        if (argCount == 0) return Arguments.EMPTY
        val list = ArrayList<Obj>(argCount)
        for (i in 0 until argCount) {
            list.add(slotToObj(fn, frame, scope, argBase + i))
        }
        return Arguments(list)
    }

    private fun getObj(fn: BytecodeFunction, frame: BytecodeFrame, scope: Scope, slot: Int): Obj {
        return if (slot < fn.scopeSlotCount) {
            resolveScope(scope, fn.scopeSlotDepths[slot]).getSlotRecord(fn.scopeSlotIndices[slot]).value
        } else {
            frame.getObj(slot - fn.scopeSlotCount)
        }
    }

    private fun setObj(fn: BytecodeFunction, frame: BytecodeFrame, scope: Scope, slot: Int, value: Obj) {
        if (slot < fn.scopeSlotCount) {
            setScopeSlotValue(scope, fn.scopeSlotDepths[slot], fn.scopeSlotIndices[slot], value)
        } else {
            frame.setObj(slot - fn.scopeSlotCount, value)
        }
    }

    private fun getInt(fn: BytecodeFunction, frame: BytecodeFrame, scope: Scope, slot: Int): Long {
        return if (slot < fn.scopeSlotCount) {
            resolveScope(scope, fn.scopeSlotDepths[slot]).getSlotRecord(fn.scopeSlotIndices[slot]).value.toLong()
        } else {
            frame.getInt(slot - fn.scopeSlotCount)
        }
    }

    private fun setInt(fn: BytecodeFunction, frame: BytecodeFrame, scope: Scope, slot: Int, value: Long) {
        if (slot < fn.scopeSlotCount) {
            setScopeSlotValue(scope, fn.scopeSlotDepths[slot], fn.scopeSlotIndices[slot], ObjInt.of(value))
        } else {
            frame.setInt(slot - fn.scopeSlotCount, value)
        }
    }

    private fun getReal(fn: BytecodeFunction, frame: BytecodeFrame, scope: Scope, slot: Int): Double {
        return if (slot < fn.scopeSlotCount) {
            resolveScope(scope, fn.scopeSlotDepths[slot]).getSlotRecord(fn.scopeSlotIndices[slot]).value.toDouble()
        } else {
            frame.getReal(slot - fn.scopeSlotCount)
        }
    }

    private fun setReal(fn: BytecodeFunction, frame: BytecodeFrame, scope: Scope, slot: Int, value: Double) {
        if (slot < fn.scopeSlotCount) {
            setScopeSlotValue(scope, fn.scopeSlotDepths[slot], fn.scopeSlotIndices[slot], ObjReal.of(value))
        } else {
            frame.setReal(slot - fn.scopeSlotCount, value)
        }
    }

    private fun getBool(fn: BytecodeFunction, frame: BytecodeFrame, scope: Scope, slot: Int): Boolean {
        return if (slot < fn.scopeSlotCount) {
            resolveScope(scope, fn.scopeSlotDepths[slot]).getSlotRecord(fn.scopeSlotIndices[slot]).value.toBool()
        } else {
            frame.getBool(slot - fn.scopeSlotCount)
        }
    }

    private fun setBool(fn: BytecodeFunction, frame: BytecodeFrame, scope: Scope, slot: Int, value: Boolean) {
        if (slot < fn.scopeSlotCount) {
            setScopeSlotValue(scope, fn.scopeSlotDepths[slot], fn.scopeSlotIndices[slot], if (value) ObjTrue else ObjFalse)
        } else {
            frame.setBool(slot - fn.scopeSlotCount, value)
        }
    }

    private fun setScopeSlotValue(scope: Scope, depth: Int, index: Int, value: Obj) {
        val target = resolveScope(scope, depth)
        target.setSlotValue(index, value)
    }

    private fun resolveScope(scope: Scope, depth: Int): Scope {
        if (depth == 0) return scope
        val next = when (scope) {
            is net.sergeych.lyng.ClosureScope -> scope.closureScope
            else -> scope.parent
        }
        return next?.let { resolveScope(it, depth - 1) }
            ?: error("Scope depth $depth is out of range")
    }
}
