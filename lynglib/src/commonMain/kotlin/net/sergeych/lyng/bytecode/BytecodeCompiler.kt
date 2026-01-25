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
import net.sergeych.lyng.IfStatement
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Statement
import net.sergeych.lyng.ToBoolStatement
import net.sergeych.lyng.obj.*

class BytecodeCompiler {
    private val builder = BytecodeBuilder()
    private var nextSlot = 0

    fun compileStatement(name: String, stmt: net.sergeych.lyng.Statement): BytecodeFunction? {
        return when (stmt) {
            is ExpressionStatement -> compileExpression(name, stmt)
            is net.sergeych.lyng.IfStatement -> compileIf(name, stmt)
            else -> null
        }
    }

    fun compileExpression(name: String, stmt: ExpressionStatement): BytecodeFunction? {
        val value = compileRefWithFallback(stmt.ref, null, stmt.pos) ?: return null
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
                when (a.type) {
                    SlotType.INT -> {
                        builder.emit(Opcode.CMP_EQ_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    SlotType.REAL -> {
                        builder.emit(Opcode.CMP_EQ_REAL, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    SlotType.BOOL -> {
                        builder.emit(Opcode.CMP_EQ_BOOL, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    else -> null
                }
            }
            BinOp.NEQ -> {
                when (a.type) {
                    SlotType.INT -> {
                        builder.emit(Opcode.CMP_NEQ_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    SlotType.REAL -> {
                        builder.emit(Opcode.CMP_NEQ_REAL, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    SlotType.BOOL -> {
                        builder.emit(Opcode.CMP_NEQ_BOOL, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    else -> null
                }
            }
            BinOp.LT -> {
                when (a.type) {
                    SlotType.INT -> {
                        builder.emit(Opcode.CMP_LT_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    SlotType.REAL -> {
                        builder.emit(Opcode.CMP_LT_REAL, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    else -> null
                }
            }
            BinOp.LTE -> {
                when (a.type) {
                    SlotType.INT -> {
                        builder.emit(Opcode.CMP_LTE_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    SlotType.REAL -> {
                        builder.emit(Opcode.CMP_LTE_REAL, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    else -> null
                }
            }
            BinOp.GT -> {
                when (a.type) {
                    SlotType.INT -> {
                        builder.emit(Opcode.CMP_GT_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    SlotType.REAL -> {
                        builder.emit(Opcode.CMP_GT_REAL, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    else -> null
                }
            }
            BinOp.GTE -> {
                when (a.type) {
                    SlotType.INT -> {
                        builder.emit(Opcode.CMP_GTE_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    SlotType.REAL -> {
                        builder.emit(Opcode.CMP_GTE_REAL, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.BOOL)
                    }
                    else -> null
                }
            }
            BinOp.AND -> {
                if (a.type != SlotType.BOOL) return null
                builder.emit(Opcode.AND_BOOL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.OR -> {
                if (a.type != SlotType.BOOL) return null
                builder.emit(Opcode.OR_BOOL, a.slot, b.slot, out)
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

    private fun compileIf(name: String, stmt: IfStatement): BytecodeFunction? {
        val conditionStmt = stmt.condition as? ExpressionStatement ?: return null
        val condValue = compileRefWithFallback(conditionStmt.ref, SlotType.BOOL, stmt.pos) ?: return null
        if (condValue.type != SlotType.BOOL) return null

        val resultSlot = allocSlot()
        val elseLabel = builder.label()
        val endLabel = builder.label()

        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(BytecodeBuilder.Operand.IntVal(condValue.slot), BytecodeBuilder.Operand.LabelRef(elseLabel))
        )
        val thenValue = compileStatementValue(stmt.ifBody) ?: return null
        emitMove(thenValue, resultSlot)
        builder.emit(Opcode.JMP, listOf(BytecodeBuilder.Operand.LabelRef(endLabel)))

        builder.mark(elseLabel)
        if (stmt.elseBody != null) {
            val elseValue = compileStatementValue(stmt.elseBody) ?: return null
            emitMove(elseValue, resultSlot)
        } else {
            val id = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
            builder.emit(Opcode.CONST_OBJ, id, resultSlot)
        }

        builder.mark(endLabel)
        builder.emit(Opcode.RET, resultSlot)
        val localCount = maxOf(nextSlot, resultSlot + 1)
        return builder.build(name, localCount)
    }

    private fun compileStatementValue(stmt: Statement): CompiledValue? {
        return when (stmt) {
            is ExpressionStatement -> compileRefWithFallback(stmt.ref, null, stmt.pos)
            else -> null
        }
    }

    private fun emitMove(value: CompiledValue, dstSlot: Int) {
        when (value.type) {
            SlotType.INT -> builder.emit(Opcode.MOVE_INT, value.slot, dstSlot)
            SlotType.REAL -> builder.emit(Opcode.MOVE_REAL, value.slot, dstSlot)
            SlotType.BOOL -> builder.emit(Opcode.MOVE_BOOL, value.slot, dstSlot)
            else -> builder.emit(Opcode.MOVE_OBJ, value.slot, dstSlot)
        }
    }

    private fun compileRefWithFallback(ref: ObjRef, forceType: SlotType?, pos: Pos): CompiledValue? {
        val compiled = compileRef(ref)
        if (compiled != null && (forceType == null || compiled.type == forceType || compiled.type == SlotType.UNKNOWN)) {
            return if (forceType != null && compiled.type == SlotType.UNKNOWN) {
                CompiledValue(compiled.slot, forceType)
            } else compiled
        }
        val slot = allocSlot()
        val stmt = if (forceType == SlotType.BOOL) {
            ToBoolStatement(ExpressionStatement(ref, pos), pos)
        } else {
            ExpressionStatement(ref, pos)
        }
        val id = builder.addFallback(stmt)
        builder.emit(Opcode.EVAL_FALLBACK, id, slot)
        return CompiledValue(slot, forceType ?: SlotType.OBJ)
    }

    private fun refSlot(ref: LocalSlotRef): Int = ref.slot
    private fun refDepth(ref: LocalSlotRef): Int = ref.depth
    private fun binaryLeft(ref: BinaryOpRef): ObjRef = ref.left
    private fun binaryRight(ref: BinaryOpRef): ObjRef = ref.right
    private fun binaryOp(ref: BinaryOpRef): BinOp = ref.op
    private fun unaryOperand(ref: UnaryOpRef): ObjRef = ref.a
    private fun unaryOp(ref: UnaryOpRef): UnaryOp = ref.op
    private fun assignTarget(ref: AssignRef): LocalSlotRef? = ref.target as? LocalSlotRef
    private fun assignValue(ref: AssignRef): ObjRef = ref.value
}
