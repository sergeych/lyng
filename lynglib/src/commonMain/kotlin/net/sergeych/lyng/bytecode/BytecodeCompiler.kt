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

import net.sergeych.lyng.ExpressionStatement
import net.sergeych.lyng.obj.*

class BytecodeCompiler {
    private val builder = BytecodeBuilder()
    private var nextSlot = 0

    fun compileExpression(name: String, stmt: ExpressionStatement): BytecodeFunction? {
        val value = compileRef(stmt.ref) ?: return null
        builder.emit(Opcode.RET, value.slot)
        val localCount = maxOf(nextSlot, value.slot + 1)
        return builder.build(name, localCount)
    }

    private data class CompiledValue(val slot: Int, val type: SlotType)

    private fun allocSlot(): Int = nextSlot++

    private fun compileRef(ref: ObjRef): CompiledValue? {
        return when (ref) {
            is ConstRef -> compileConst(ref.constValue)
            is LocalSlotRef -> {
                if (ref.name.isEmpty()) return null
                if (refDepth(ref) != 0) return null
                CompiledValue(refSlot(ref), SlotType.UNKNOWN)
            }
            is BinaryOpRef -> compileBinary(ref)
            is UnaryOpRef -> compileUnary(ref)
            is AssignRef -> compileAssign(ref)
            else -> null
        }
    }

    private fun compileConst(obj: Obj): CompiledValue? {
        val slot = allocSlot()
        when (obj) {
            is ObjInt -> {
                val id = builder.addConst(BytecodeConst.IntVal(obj.value))
                builder.emit(Opcode.CONST_INT, id, slot)
                return CompiledValue(slot, SlotType.INT)
            }
            is ObjReal -> {
                val id = builder.addConst(BytecodeConst.RealVal(obj.value))
                builder.emit(Opcode.CONST_REAL, id, slot)
                return CompiledValue(slot, SlotType.REAL)
            }
            is ObjBool -> {
                val id = builder.addConst(BytecodeConst.Bool(obj.value))
                builder.emit(Opcode.CONST_BOOL, id, slot)
                return CompiledValue(slot, SlotType.BOOL)
            }
            is ObjString -> {
                val id = builder.addConst(BytecodeConst.StringVal(obj.value))
                builder.emit(Opcode.CONST_OBJ, id, slot)
                return CompiledValue(slot, SlotType.OBJ)
            }
            ObjNull -> {
                builder.emit(Opcode.CONST_NULL, slot)
                return CompiledValue(slot, SlotType.OBJ)
            }
            else -> {
                val id = builder.addConst(BytecodeConst.ObjRef(obj))
                builder.emit(Opcode.CONST_OBJ, id, slot)
                return CompiledValue(slot, SlotType.OBJ)
            }
        }
    }

    private fun compileUnary(ref: UnaryOpRef): CompiledValue? {
        val a = compileRef(unaryOperand(ref)) ?: return null
        val out = allocSlot()
        return when (unaryOp(ref)) {
            UnaryOp.NEGATE -> when (a.type) {
                SlotType.INT -> {
                    builder.emit(Opcode.NEG_INT, a.slot, out)
                    CompiledValue(out, SlotType.INT)
                }
                SlotType.REAL -> {
                    builder.emit(Opcode.NEG_REAL, a.slot, out)
                    CompiledValue(out, SlotType.REAL)
                }
                else -> null
            }
            UnaryOp.NOT -> {
                if (a.type != SlotType.BOOL) return null
                builder.emit(Opcode.NOT_BOOL, a.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            UnaryOp.BITNOT -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.INV_INT, a.slot, out)
                CompiledValue(out, SlotType.INT)
            }
        }
    }

    private fun compileBinary(ref: BinaryOpRef): CompiledValue? {
        val a = compileRef(binaryLeft(ref)) ?: return null
        val b = compileRef(binaryRight(ref)) ?: return null
        if (a.type != b.type && a.type != SlotType.UNKNOWN && b.type != SlotType.UNKNOWN) return null
        val out = allocSlot()
        val op = binaryOp(ref)
        return when (op) {
            BinOp.PLUS -> when (a.type) {
                SlotType.INT -> {
                    builder.emit(Opcode.ADD_INT, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.INT)
                }
                SlotType.REAL -> {
                    builder.emit(Opcode.ADD_REAL, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.REAL)
                }
                else -> null
            }
            BinOp.MINUS -> when (a.type) {
                SlotType.INT -> {
                    builder.emit(Opcode.SUB_INT, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.INT)
                }
                SlotType.REAL -> {
                    builder.emit(Opcode.SUB_REAL, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.REAL)
                }
                else -> null
            }
            BinOp.STAR -> when (a.type) {
                SlotType.INT -> {
                    builder.emit(Opcode.MUL_INT, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.INT)
                }
                SlotType.REAL -> {
                    builder.emit(Opcode.MUL_REAL, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.REAL)
                }
                else -> null
            }
            BinOp.SLASH -> when (a.type) {
                SlotType.INT -> {
                    builder.emit(Opcode.DIV_INT, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.INT)
                }
                SlotType.REAL -> {
                    builder.emit(Opcode.DIV_REAL, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.REAL)
                }
                else -> null
            }
            BinOp.PERCENT -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.MOD_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.INT)
            }
            BinOp.EQ -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.CMP_EQ_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.NEQ -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.CMP_NEQ_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.LT -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.CMP_LT_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.LTE -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.CMP_LTE_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.GT -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.CMP_GT_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.GTE -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.CMP_GTE_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.BAND -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.AND_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.INT)
            }
            BinOp.BOR -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.OR_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.INT)
            }
            BinOp.BXOR -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.XOR_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.INT)
            }
            BinOp.SHL -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.SHL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.INT)
            }
            BinOp.SHR -> {
                if (a.type != SlotType.INT) return null
                builder.emit(Opcode.SHR_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.INT)
            }
            else -> null
        }
    }

    private fun compileAssign(ref: AssignRef): CompiledValue? {
        val target = assignTarget(ref) ?: return null
        if (refDepth(target) != 0) return null
        val value = compileRef(assignValue(ref)) ?: return null
        val slot = refSlot(target)
        when (value.type) {
            SlotType.INT -> builder.emit(Opcode.MOVE_INT, value.slot, slot)
            SlotType.REAL -> builder.emit(Opcode.MOVE_REAL, value.slot, slot)
            SlotType.BOOL -> builder.emit(Opcode.MOVE_BOOL, value.slot, slot)
            else -> builder.emit(Opcode.MOVE_OBJ, value.slot, slot)
        }
        return CompiledValue(slot, value.type)
    }

    private fun refSlot(ref: LocalSlotRef): Int = ref.slot

    private fun refDepth(ref: LocalSlotRef): Int = refDepthAccessor(ref)

    private fun binaryLeft(ref: BinaryOpRef): ObjRef = binaryLeftAccessor(ref)
    private fun binaryRight(ref: BinaryOpRef): ObjRef = binaryRightAccessor(ref)
    private fun binaryOp(ref: BinaryOpRef): BinOp = binaryOpAccessor(ref)

    private fun unaryOperand(ref: UnaryOpRef): ObjRef = unaryOperandAccessor(ref)
    private fun unaryOp(ref: UnaryOpRef): UnaryOp = unaryOpAccessor(ref)

    private fun assignTarget(ref: AssignRef): LocalSlotRef? = assignTargetAccessor(ref)
    private fun assignValue(ref: AssignRef): ObjRef = assignValueAccessor(ref)

    // Accessor helpers to avoid exposing fields directly in ObjRef classes.
    private fun refDepthAccessor(ref: LocalSlotRef): Int = ref.depth
    private fun binaryLeftAccessor(ref: BinaryOpRef): ObjRef = ref.left
    private fun binaryRightAccessor(ref: BinaryOpRef): ObjRef = ref.right
    private fun binaryOpAccessor(ref: BinaryOpRef): BinOp = ref.op
    private fun unaryOperandAccessor(ref: UnaryOpRef): ObjRef = ref.a
    private fun unaryOpAccessor(ref: UnaryOpRef): UnaryOp = ref.op
    private fun assignTargetAccessor(ref: AssignRef): LocalSlotRef? = ref.target as? LocalSlotRef
    private fun assignValueAccessor(ref: AssignRef): ObjRef = ref.value
}
