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

import net.sergeych.lyng.BlockStatement
import net.sergeych.lyng.ExpressionStatement
import net.sergeych.lyng.IfStatement
import net.sergeych.lyng.ParsedArgument
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Statement
import net.sergeych.lyng.ToBoolStatement
import net.sergeych.lyng.VarDeclStatement
import net.sergeych.lyng.obj.*

class BytecodeCompiler(
    private val allowLocalSlots: Boolean = true,
) {
    private var builder = CmdBuilder()
    private var nextSlot = 0
    private var nextAddrSlot = 0
    private var scopeSlotCount = 0
    private var scopeSlotDepths = IntArray(0)
    private var scopeSlotIndices = IntArray(0)
    private var scopeSlotNames = emptyArray<String?>()
    private val scopeSlotMap = LinkedHashMap<ScopeSlotKey, Int>()
    private val scopeSlotNameMap = LinkedHashMap<ScopeSlotKey, String>()
    private val addrSlotByScopeSlot = LinkedHashMap<Int, Int>()
    private data class LocalSlotInfo(val name: String, val isMutable: Boolean, val depth: Int)
    private val localSlotInfoMap = LinkedHashMap<ScopeSlotKey, LocalSlotInfo>()
    private val localSlotIndexByKey = LinkedHashMap<ScopeSlotKey, Int>()
    private val localSlotIndexByName = LinkedHashMap<String, Int>()
    private var localSlotNames = emptyArray<String?>()
    private var localSlotMutables = BooleanArray(0)
    private var localSlotDepths = IntArray(0)
    private val declaredLocalKeys = LinkedHashSet<ScopeSlotKey>()
    private val slotTypes = mutableMapOf<Int, SlotType>()
    private val intLoopVarNames = LinkedHashSet<String>()

    fun compileStatement(name: String, stmt: net.sergeych.lyng.Statement): CmdFunction? {
        prepareCompilation(stmt)
        return when (stmt) {
            is ExpressionStatement -> compileExpression(name, stmt)
            is net.sergeych.lyng.IfStatement -> compileIf(name, stmt)
            is net.sergeych.lyng.ForInStatement -> compileForIn(name, stmt)
            is BlockStatement -> compileBlock(name, stmt)
            is VarDeclStatement -> compileVarDecl(name, stmt)
            else -> null
        }
    }

    fun compileExpression(name: String, stmt: ExpressionStatement): CmdFunction? {
        prepareCompilation(stmt)
        val value = compileRefWithFallback(stmt.ref, null, stmt.pos) ?: return null
        builder.emit(Opcode.RET, value.slot)
        val localCount = maxOf(nextSlot, value.slot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            scopeSlotDepths,
            scopeSlotIndices,
            scopeSlotNames,
            localSlotNames,
            localSlotMutables,
            localSlotDepths
        )
    }

    private data class CompiledValue(val slot: Int, val type: SlotType)

    private fun allocSlot(): Int = nextSlot++

    private fun compileRef(ref: ObjRef): CompiledValue? {
        return when (ref) {
            is ConstRef -> compileConst(ref.constValue)
            is LocalSlotRef -> {
                if (!allowLocalSlots) return null
                if (ref.isDelegated) return null
                if (ref.name.isEmpty()) return null
                val mapped = resolveSlot(ref) ?: return null
                var resolved = slotTypes[mapped] ?: SlotType.UNKNOWN
                if (resolved == SlotType.UNKNOWN && intLoopVarNames.contains(ref.name)) {
                    updateSlotType(mapped, SlotType.INT)
                    resolved = SlotType.INT
                }
                if (mapped < scopeSlotCount && resolved != SlotType.UNKNOWN) {
                    val addrSlot = ensureScopeAddr(mapped)
                    val local = allocSlot()
                    emitLoadFromAddr(addrSlot, local, resolved)
                    updateSlotType(local, resolved)
                    return CompiledValue(local, resolved)
                }
                CompiledValue(mapped, resolved)
            }
            is BinaryOpRef -> compileBinary(ref)
            is UnaryOpRef -> compileUnary(ref)
            is AssignRef -> compileAssign(ref)
            is AssignOpRef -> compileAssignOp(ref)
            is IncDecRef -> compileIncDec(ref, true)
            is ConditionalRef -> compileConditional(ref)
            is ElvisRef -> compileElvis(ref)
            is CallRef -> compileCall(ref)
            is MethodCallRef -> compileMethodCall(ref)
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
        val op = binaryOp(ref)
        if (op == BinOp.AND || op == BinOp.OR) {
            return compileLogical(op, binaryLeft(ref), binaryRight(ref), refPos(ref))
        }
        val leftRef = binaryLeft(ref)
        val rightRef = binaryRight(ref)
        var a = compileRef(leftRef) ?: return null
        var b = compileRef(rightRef) ?: return null
        val intOps = setOf(
            BinOp.PLUS, BinOp.MINUS, BinOp.STAR, BinOp.SLASH, BinOp.PERCENT,
            BinOp.BAND, BinOp.BOR, BinOp.BXOR, BinOp.SHL, BinOp.SHR
        )
        val leftIsLoopVar = (leftRef as? LocalSlotRef)?.name?.let { intLoopVarNames.contains(it) } == true
        val rightIsLoopVar = (rightRef as? LocalSlotRef)?.name?.let { intLoopVarNames.contains(it) } == true
        if (a.type == SlotType.UNKNOWN && b.type == SlotType.INT && op in intOps && leftIsLoopVar) {
            updateSlotType(a.slot, SlotType.INT)
            a = CompiledValue(a.slot, SlotType.INT)
        }
        if (b.type == SlotType.UNKNOWN && a.type == SlotType.INT && op in intOps && rightIsLoopVar) {
            updateSlotType(b.slot, SlotType.INT)
            b = CompiledValue(b.slot, SlotType.INT)
        }
        if (a.type == SlotType.UNKNOWN && b.type == SlotType.UNKNOWN && op in intOps && leftIsLoopVar && rightIsLoopVar) {
            updateSlotType(a.slot, SlotType.INT)
            updateSlotType(b.slot, SlotType.INT)
            a = CompiledValue(a.slot, SlotType.INT)
            b = CompiledValue(b.slot, SlotType.INT)
        }
        val typesMismatch = a.type != b.type && a.type != SlotType.UNKNOWN && b.type != SlotType.UNKNOWN
        if (typesMismatch && op !in setOf(BinOp.EQ, BinOp.NEQ, BinOp.LT, BinOp.LTE, BinOp.GT, BinOp.GTE)) {
            return null
        }
        val out = allocSlot()
        return when (op) {
            BinOp.PLUS -> when (a.type) {
                SlotType.INT -> {
                    when (b.type) {
                        SlotType.INT -> {
                            builder.emit(Opcode.ADD_INT, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.INT)
                        }
                        SlotType.REAL -> compileRealArithmeticWithCoercion(Opcode.ADD_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.REAL -> {
                    when (b.type) {
                        SlotType.REAL -> {
                            builder.emit(Opcode.ADD_REAL, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.REAL)
                        }
                        SlotType.INT -> compileRealArithmeticWithCoercion(Opcode.ADD_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.OBJ -> {
                    if (b.type != SlotType.OBJ) return null
                    builder.emit(Opcode.ADD_OBJ, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.OBJ)
                }
                else -> null
            }
            BinOp.MINUS -> when (a.type) {
                SlotType.INT -> {
                    when (b.type) {
                        SlotType.INT -> {
                            builder.emit(Opcode.SUB_INT, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.INT)
                        }
                        SlotType.REAL -> compileRealArithmeticWithCoercion(Opcode.SUB_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.REAL -> {
                    when (b.type) {
                        SlotType.REAL -> {
                            builder.emit(Opcode.SUB_REAL, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.REAL)
                        }
                        SlotType.INT -> compileRealArithmeticWithCoercion(Opcode.SUB_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.OBJ -> {
                    if (b.type != SlotType.OBJ) return null
                    builder.emit(Opcode.SUB_OBJ, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.OBJ)
                }
                else -> null
            }
            BinOp.STAR -> when (a.type) {
                SlotType.INT -> {
                    when (b.type) {
                        SlotType.INT -> {
                            builder.emit(Opcode.MUL_INT, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.INT)
                        }
                        SlotType.REAL -> compileRealArithmeticWithCoercion(Opcode.MUL_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.REAL -> {
                    when (b.type) {
                        SlotType.REAL -> {
                            builder.emit(Opcode.MUL_REAL, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.REAL)
                        }
                        SlotType.INT -> compileRealArithmeticWithCoercion(Opcode.MUL_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.OBJ -> {
                    if (b.type != SlotType.OBJ) return null
                    builder.emit(Opcode.MUL_OBJ, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.OBJ)
                }
                else -> null
            }
            BinOp.SLASH -> when (a.type) {
                SlotType.INT -> {
                    when (b.type) {
                        SlotType.INT -> {
                            builder.emit(Opcode.DIV_INT, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.INT)
                        }
                        SlotType.REAL -> compileRealArithmeticWithCoercion(Opcode.DIV_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.REAL -> {
                    when (b.type) {
                        SlotType.REAL -> {
                            builder.emit(Opcode.DIV_REAL, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.REAL)
                        }
                        SlotType.INT -> compileRealArithmeticWithCoercion(Opcode.DIV_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.OBJ -> {
                    if (b.type != SlotType.OBJ) return null
                    builder.emit(Opcode.DIV_OBJ, a.slot, b.slot, out)
                    CompiledValue(out, SlotType.OBJ)
                }
                else -> null
            }
            BinOp.PERCENT -> {
                return when (a.type) {
                    SlotType.INT -> {
                        if (b.type != SlotType.INT) return null
                        builder.emit(Opcode.MOD_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.INT)
                    }
                    SlotType.OBJ -> {
                        if (b.type != SlotType.OBJ) return null
                        builder.emit(Opcode.MOD_OBJ, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.OBJ)
                    }
                    else -> null
                }
            }
            BinOp.EQ -> {
                compileCompareEq(a, b, out)
            }
            BinOp.NEQ -> {
                compileCompareNeq(a, b, out)
            }
            BinOp.LT -> {
                compileCompareLt(a, b, out)
            }
            BinOp.LTE -> {
                compileCompareLte(a, b, out)
            }
            BinOp.GT -> {
                compileCompareGt(a, b, out)
            }
            BinOp.GTE -> {
                compileCompareGte(a, b, out)
            }
            BinOp.REF_EQ -> {
                if (a.type != SlotType.OBJ || b.type != SlotType.OBJ) return null
                builder.emit(Opcode.CMP_REF_EQ_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.REF_NEQ -> {
                if (a.type != SlotType.OBJ || b.type != SlotType.OBJ) return null
                builder.emit(Opcode.CMP_REF_NEQ_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
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

    private fun compileRealArithmeticWithCoercion(
        op: Opcode,
        a: CompiledValue,
        b: CompiledValue,
        out: Int
    ): CompiledValue? {
        if (a.type == SlotType.INT && b.type == SlotType.REAL) {
            val left = allocSlot()
            builder.emit(Opcode.INT_TO_REAL, a.slot, left)
            builder.emit(op, left, b.slot, out)
            return CompiledValue(out, SlotType.REAL)
        }
        if (a.type == SlotType.REAL && b.type == SlotType.INT) {
            val right = allocSlot()
            builder.emit(Opcode.INT_TO_REAL, b.slot, right)
            builder.emit(op, a.slot, right, out)
            return CompiledValue(out, SlotType.REAL)
        }
        return null
    }

    private fun compileCompareEq(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_EQ_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_EQ_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_EQ_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.BOOL && b.type == SlotType.BOOL -> {
                builder.emit(Opcode.CMP_EQ_BOOL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_EQ_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_EQ_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_EQ_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileCompareNeq(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_NEQ_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_NEQ_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_NEQ_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.BOOL && b.type == SlotType.BOOL -> {
                builder.emit(Opcode.CMP_NEQ_BOOL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_NEQ_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_NEQ_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_NEQ_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileCompareLt(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_LT_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_LT_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_LT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_LT_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_LT_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_LT_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileCompareLte(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_LTE_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_LTE_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_LTE_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_LTE_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_LTE_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_LTE_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileCompareGt(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_GT_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_GT_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_GT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_GT_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_GT_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_GT_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileCompareGte(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_GTE_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_GTE_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_GTE_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_GTE_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_GTE_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_GTE_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileLogical(op: BinOp, left: ObjRef, right: ObjRef, pos: Pos): CompiledValue? {
        val leftValue = compileRefWithFallback(left, SlotType.BOOL, pos) ?: return null
        if (leftValue.type != SlotType.BOOL) return null
        val resultSlot = allocSlot()
        val shortLabel = builder.label()
        val endLabel = builder.label()
        if (op == BinOp.AND) {
            builder.emit(
                Opcode.JMP_IF_FALSE,
                listOf(CmdBuilder.Operand.IntVal(leftValue.slot), CmdBuilder.Operand.LabelRef(shortLabel))
            )
        } else {
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(leftValue.slot), CmdBuilder.Operand.LabelRef(shortLabel))
            )
        }
        val rightValue = compileRefWithFallback(right, SlotType.BOOL, pos) ?: return null
        emitMove(rightValue, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(shortLabel)
        val constId = builder.addConst(BytecodeConst.Bool(op == BinOp.OR))
        builder.emit(Opcode.CONST_BOOL, constId, resultSlot)
        builder.mark(endLabel)
        return CompiledValue(resultSlot, SlotType.BOOL)
    }

    private fun compileAssign(ref: AssignRef): CompiledValue? {
        val target = assignTarget(ref) ?: return null
        if (!allowLocalSlots) return null
        if (!target.isMutable || target.isDelegated) return null
        val value = compileRef(assignValue(ref)) ?: return null
        val slot = resolveSlot(target) ?: return null
        if (slot < scopeSlotCount && value.type != SlotType.UNKNOWN) {
            val addrSlot = ensureScopeAddr(slot)
            emitStoreToAddr(value.slot, addrSlot, value.type)
        } else if (slot < scopeSlotCount) {
            val addrSlot = ensureScopeAddr(slot)
            emitStoreToAddr(value.slot, addrSlot, SlotType.OBJ)
        } else {
            when (value.type) {
                SlotType.INT -> builder.emit(Opcode.MOVE_INT, value.slot, slot)
                SlotType.REAL -> builder.emit(Opcode.MOVE_REAL, value.slot, slot)
                SlotType.BOOL -> builder.emit(Opcode.MOVE_BOOL, value.slot, slot)
                else -> builder.emit(Opcode.MOVE_OBJ, value.slot, slot)
            }
        }
        updateSlotType(slot, value.type)
        return value
    }

    private fun compileAssignOp(ref: AssignOpRef): CompiledValue? {
        val target = ref.target as? LocalSlotRef ?: return null
        if (!allowLocalSlots) return null
        if (!target.isMutable || target.isDelegated) return null
        val slot = resolveSlot(target) ?: return null
        val targetType = slotTypes[slot] ?: return null
        val rhs = compileRef(ref.value) ?: return null
        if (slot < scopeSlotCount) {
            val addrSlot = ensureScopeAddr(slot)
            val current = allocSlot()
            emitLoadFromAddr(addrSlot, current, targetType)
            val result = when (ref.op) {
                BinOp.PLUS -> compileAssignOpBinary(targetType, rhs, current, Opcode.ADD_INT, Opcode.ADD_REAL, Opcode.ADD_OBJ)
                BinOp.MINUS -> compileAssignOpBinary(targetType, rhs, current, Opcode.SUB_INT, Opcode.SUB_REAL, Opcode.SUB_OBJ)
                BinOp.STAR -> compileAssignOpBinary(targetType, rhs, current, Opcode.MUL_INT, Opcode.MUL_REAL, Opcode.MUL_OBJ)
                BinOp.SLASH -> compileAssignOpBinary(targetType, rhs, current, Opcode.DIV_INT, Opcode.DIV_REAL, Opcode.DIV_OBJ)
                BinOp.PERCENT -> compileAssignOpBinary(targetType, rhs, current, Opcode.MOD_INT, null, Opcode.MOD_OBJ)
                else -> null
            } ?: return null
            emitStoreToAddr(current, addrSlot, result.type)
            updateSlotType(slot, result.type)
            return CompiledValue(current, result.type)
        }
        val out = slot
        val result = when (ref.op) {
            BinOp.PLUS -> compileAssignOpBinary(targetType, rhs, out, Opcode.ADD_INT, Opcode.ADD_REAL, Opcode.ADD_OBJ)
            BinOp.MINUS -> compileAssignOpBinary(targetType, rhs, out, Opcode.SUB_INT, Opcode.SUB_REAL, Opcode.SUB_OBJ)
            BinOp.STAR -> compileAssignOpBinary(targetType, rhs, out, Opcode.MUL_INT, Opcode.MUL_REAL, Opcode.MUL_OBJ)
            BinOp.SLASH -> compileAssignOpBinary(targetType, rhs, out, Opcode.DIV_INT, Opcode.DIV_REAL, Opcode.DIV_OBJ)
            BinOp.PERCENT -> compileAssignOpBinary(targetType, rhs, out, Opcode.MOD_INT, null, Opcode.MOD_OBJ)
            else -> null
        } ?: return null
        updateSlotType(out, result.type)
        return CompiledValue(out, result.type)
    }

    private fun compileAssignOpBinary(
        targetType: SlotType,
        rhs: CompiledValue,
        out: Int,
        intOp: Opcode,
        realOp: Opcode?,
        objOp: Opcode?,
    ): CompiledValue? {
        return when (targetType) {
            SlotType.INT -> {
                when (rhs.type) {
                    SlotType.INT -> {
                        builder.emit(intOp, out, rhs.slot, out)
                        CompiledValue(out, SlotType.INT)
                    }
                    SlotType.REAL -> {
                        if (realOp == null) return null
                        val left = allocSlot()
                        builder.emit(Opcode.INT_TO_REAL, out, left)
                        builder.emit(realOp, left, rhs.slot, out)
                        CompiledValue(out, SlotType.REAL)
                    }
                    else -> null
                }
            }
            SlotType.REAL -> {
                if (realOp == null) return null
                when (rhs.type) {
                    SlotType.REAL -> {
                        builder.emit(realOp, out, rhs.slot, out)
                        CompiledValue(out, SlotType.REAL)
                    }
                    SlotType.INT -> {
                        val right = allocSlot()
                        builder.emit(Opcode.INT_TO_REAL, rhs.slot, right)
                        builder.emit(realOp, out, right, out)
                        CompiledValue(out, SlotType.REAL)
                    }
                    else -> null
                }
            }
            SlotType.OBJ -> {
                if (objOp == null) return null
                if (rhs.type != SlotType.OBJ) return null
                builder.emit(objOp, out, rhs.slot, out)
                CompiledValue(out, SlotType.OBJ)
            }
            else -> null
        }
    }

    private fun compileIncDec(ref: IncDecRef, wantResult: Boolean): CompiledValue? {
        val target = ref.target as? LocalSlotRef ?: return null
        if (!allowLocalSlots) return null
        if (!target.isMutable || target.isDelegated) return null
        val slot = resolveSlot(target) ?: return null
        val slotType = slotTypes[slot] ?: SlotType.UNKNOWN
        if (slot < scopeSlotCount && slotType != SlotType.UNKNOWN) {
            val addrSlot = ensureScopeAddr(slot)
            val current = allocSlot()
            emitLoadFromAddr(addrSlot, current, slotType)
            val result = when (slotType) {
                SlotType.INT -> {
                    if (wantResult && ref.isPost) {
                        val old = allocSlot()
                        builder.emit(Opcode.MOVE_INT, current, old)
                        builder.emit(if (ref.isIncrement) Opcode.INC_INT else Opcode.DEC_INT, current)
                        emitStoreToAddr(current, addrSlot, SlotType.INT)
                        CompiledValue(old, SlotType.INT)
                    } else {
                        builder.emit(if (ref.isIncrement) Opcode.INC_INT else Opcode.DEC_INT, current)
                        emitStoreToAddr(current, addrSlot, SlotType.INT)
                        CompiledValue(current, SlotType.INT)
                    }
                }
                SlotType.REAL -> {
                    val oneSlot = allocSlot()
                    val oneId = builder.addConst(BytecodeConst.RealVal(1.0))
                    builder.emit(Opcode.CONST_REAL, oneId, oneSlot)
                    if (wantResult && ref.isPost) {
                        val old = allocSlot()
                        builder.emit(Opcode.MOVE_REAL, current, old)
                        val op = if (ref.isIncrement) Opcode.ADD_REAL else Opcode.SUB_REAL
                        builder.emit(op, current, oneSlot, current)
                        emitStoreToAddr(current, addrSlot, SlotType.REAL)
                        CompiledValue(old, SlotType.REAL)
                    } else {
                        val op = if (ref.isIncrement) Opcode.ADD_REAL else Opcode.SUB_REAL
                        builder.emit(op, current, oneSlot, current)
                        emitStoreToAddr(current, addrSlot, SlotType.REAL)
                        CompiledValue(current, SlotType.REAL)
                    }
                }
                SlotType.OBJ -> {
                    val oneSlot = allocSlot()
                    val oneId = builder.addConst(BytecodeConst.ObjRef(ObjInt.One))
                    builder.emit(Opcode.CONST_OBJ, oneId, oneSlot)
                    val boxed = allocSlot()
                    builder.emit(Opcode.BOX_OBJ, current, boxed)
                    if (wantResult && ref.isPost) {
                        val result = allocSlot()
                        val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
                        builder.emit(op, boxed, oneSlot, result)
                        builder.emit(Opcode.MOVE_OBJ, result, boxed)
                        emitStoreToAddr(boxed, addrSlot, SlotType.OBJ)
                        updateSlotType(slot, SlotType.OBJ)
                        CompiledValue(boxed, SlotType.OBJ)
                    } else {
                        val result = allocSlot()
                        val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
                        builder.emit(op, boxed, oneSlot, result)
                        builder.emit(Opcode.MOVE_OBJ, result, boxed)
                        emitStoreToAddr(boxed, addrSlot, SlotType.OBJ)
                        updateSlotType(slot, SlotType.OBJ)
                        CompiledValue(result, SlotType.OBJ)
                    }
                }
                else -> null
            }
            if (result != null) return result
        }
        return when (slotType) {
            SlotType.INT -> {
                if (wantResult && ref.isPost) {
                    val old = allocSlot()
                    builder.emit(Opcode.MOVE_INT, slot, old)
                    builder.emit(if (ref.isIncrement) Opcode.INC_INT else Opcode.DEC_INT, slot)
                    CompiledValue(old, SlotType.INT)
                } else {
                    builder.emit(if (ref.isIncrement) Opcode.INC_INT else Opcode.DEC_INT, slot)
                    CompiledValue(slot, SlotType.INT)
                }
            }
            SlotType.REAL -> {
                val oneSlot = allocSlot()
                val oneId = builder.addConst(BytecodeConst.RealVal(1.0))
                builder.emit(Opcode.CONST_REAL, oneId, oneSlot)
                if (wantResult && ref.isPost) {
                    val old = allocSlot()
                    builder.emit(Opcode.MOVE_REAL, slot, old)
                    val op = if (ref.isIncrement) Opcode.ADD_REAL else Opcode.SUB_REAL
                    builder.emit(op, slot, oneSlot, slot)
                    CompiledValue(old, SlotType.REAL)
                } else {
                    val op = if (ref.isIncrement) Opcode.ADD_REAL else Opcode.SUB_REAL
                    builder.emit(op, slot, oneSlot, slot)
                    CompiledValue(slot, SlotType.REAL)
                }
            }
            SlotType.OBJ -> {
                val oneSlot = allocSlot()
                val oneId = builder.addConst(BytecodeConst.ObjRef(ObjInt.One))
                builder.emit(Opcode.CONST_OBJ, oneId, oneSlot)
                val current = allocSlot()
                builder.emit(Opcode.BOX_OBJ, slot, current)
                if (wantResult && ref.isPost) {
                    val result = allocSlot()
                    val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
                    builder.emit(op, current, oneSlot, result)
                    builder.emit(Opcode.MOVE_OBJ, result, slot)
                    updateSlotType(slot, SlotType.OBJ)
                    CompiledValue(current, SlotType.OBJ)
                } else {
                    val result = allocSlot()
                    val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
                    builder.emit(op, current, oneSlot, result)
                    builder.emit(Opcode.MOVE_OBJ, result, slot)
                    updateSlotType(slot, SlotType.OBJ)
                    CompiledValue(result, SlotType.OBJ)
                }
            }
            SlotType.UNKNOWN -> {
                if (wantResult && ref.isPost) {
                    val old = allocSlot()
                    builder.emit(Opcode.MOVE_INT, slot, old)
                    builder.emit(if (ref.isIncrement) Opcode.INC_INT else Opcode.DEC_INT, slot)
                    updateSlotType(slot, SlotType.INT)
                    CompiledValue(old, SlotType.INT)
                } else {
                    builder.emit(if (ref.isIncrement) Opcode.INC_INT else Opcode.DEC_INT, slot)
                    updateSlotType(slot, SlotType.INT)
                    CompiledValue(slot, SlotType.INT)
                }
            }
            else -> null
        }
    }

    private fun compileConditional(ref: ConditionalRef): CompiledValue? {
        val condition = compileRefWithFallback(ref.condition, SlotType.BOOL, Pos.builtIn) ?: return null
        if (condition.type != SlotType.BOOL) return null
        val resultSlot = allocSlot()
        val elseLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(condition.slot), CmdBuilder.Operand.LabelRef(elseLabel))
        )
        val thenValue = compileRefWithFallback(ref.ifTrue, null, Pos.builtIn) ?: return null
        val thenObj = ensureObjSlot(thenValue)
        builder.emit(Opcode.MOVE_OBJ, thenObj.slot, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(elseLabel)
        val elseValue = compileRefWithFallback(ref.ifFalse, null, Pos.builtIn) ?: return null
        val elseObj = ensureObjSlot(elseValue)
        builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
        builder.mark(endLabel)
        updateSlotType(resultSlot, SlotType.OBJ)
        return CompiledValue(resultSlot, SlotType.OBJ)
    }

    private fun compileElvis(ref: ElvisRef): CompiledValue? {
        val leftValue = compileRefWithFallback(ref.left, null, Pos.builtIn) ?: return null
        val leftObj = ensureObjSlot(leftValue)
        val resultSlot = allocSlot()
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, leftObj.slot, nullSlot, cmpSlot)
        val rightLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(rightLabel))
        )
        builder.emit(Opcode.MOVE_OBJ, leftObj.slot, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(rightLabel)
        val rightValue = compileRefWithFallback(ref.right, null, Pos.builtIn) ?: return null
        val rightObj = ensureObjSlot(rightValue)
        builder.emit(Opcode.MOVE_OBJ, rightObj.slot, resultSlot)
        builder.mark(endLabel)
        updateSlotType(resultSlot, SlotType.OBJ)
        return CompiledValue(resultSlot, SlotType.OBJ)
    }

    private fun ensureObjSlot(value: CompiledValue): CompiledValue {
        if (value.type == SlotType.OBJ) return value
        val dst = allocSlot()
        builder.emit(Opcode.BOX_OBJ, value.slot, dst)
        updateSlotType(dst, SlotType.OBJ)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileCall(ref: CallRef): CompiledValue? {
        if (ref.isOptionalInvoke) return null
        if (ref.target is LocalVarRef || ref.target is FastLocalVarRef || ref.target is BoundLocalVarRef) {
            return null
        }
        val fieldTarget = ref.target as? FieldRef
        if (fieldTarget != null && !fieldTarget.isOptional) {
            val receiver = compileRefWithFallback(fieldTarget.target, null, Pos.builtIn) ?: return null
            val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            val methodId = builder.addConst(BytecodeConst.StringVal(fieldTarget.name))
            if (methodId > 0xFFFF) return null
            val dst = allocSlot()
            builder.emit(Opcode.CALL_VIRTUAL, receiver.slot, methodId, args.base, encodedCount, dst)
            return CompiledValue(dst, SlotType.UNKNOWN)
        }
        val callee = compileRefWithFallback(ref.target, null, Pos.builtIn) ?: return null
        val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
        val encodedCount = encodeCallArgCount(args) ?: return null
        val dst = allocSlot()
        builder.emit(Opcode.CALL_SLOT, callee.slot, args.base, encodedCount, dst)
        return CompiledValue(dst, SlotType.UNKNOWN)
    }

    private fun compileMethodCall(ref: MethodCallRef): CompiledValue? {
        if (ref.isOptional) return null
        val receiver = compileRefWithFallback(ref.receiver, null, Pos.builtIn) ?: return null
        val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
        val encodedCount = encodeCallArgCount(args) ?: return null
        val methodId = builder.addConst(BytecodeConst.StringVal(ref.name))
        if (methodId > 0xFFFF) return null
        val dst = allocSlot()
        builder.emit(Opcode.CALL_VIRTUAL, receiver.slot, methodId, args.base, encodedCount, dst)
        return CompiledValue(dst, SlotType.UNKNOWN)
    }

    private data class CallArgs(val base: Int, val count: Int, val planId: Int?)

    private fun compileCallArgs(args: List<ParsedArgument>, tailBlock: Boolean): CallArgs? {
        if (args.isEmpty()) return CallArgs(base = 0, count = 0, planId = null)
        val argSlots = IntArray(args.size) { allocSlot() }
        val needPlan = tailBlock || args.any { it.isSplat || it.name != null }
        val specs = if (needPlan) ArrayList<BytecodeConst.CallArgSpec>(args.size) else null
        for ((index, arg) in args.withIndex()) {
            val compiled = compileArgValue(arg.value) ?: return null
            val dst = argSlots[index]
            if (compiled.slot != dst || compiled.type != SlotType.OBJ) {
                builder.emit(Opcode.BOX_OBJ, compiled.slot, dst)
            }
            updateSlotType(dst, SlotType.OBJ)
            specs?.add(BytecodeConst.CallArgSpec(arg.name, arg.isSplat))
        }
        val planId = if (needPlan) {
            builder.addConst(BytecodeConst.CallArgsPlan(tailBlock, specs ?: emptyList()))
        } else {
            null
        }
        return CallArgs(base = argSlots[0], count = argSlots.size, planId = planId)
    }

    private fun compileArgValue(stmt: Statement): CompiledValue? {
        return when (stmt) {
            is ExpressionStatement -> compileRefWithFallback(stmt.ref, null, stmt.pos)
            else -> {
                val slot = allocSlot()
                val id = builder.addFallback(stmt)
                builder.emit(Opcode.EVAL_FALLBACK, id, slot)
                updateSlotType(slot, SlotType.OBJ)
                CompiledValue(slot, SlotType.OBJ)
            }
        }
    }

    private fun encodeCallArgCount(args: CallArgs): Int? {
        val planId = args.planId ?: return args.count
        if (planId > 0x7FFF) return null
        return 0x8000 or planId
    }

    private fun compileIf(name: String, stmt: IfStatement): CmdFunction? {
        val conditionStmt = stmt.condition as? ExpressionStatement ?: return null
        val condValue = compileRefWithFallback(conditionStmt.ref, SlotType.BOOL, stmt.pos) ?: return null
        if (condValue.type != SlotType.BOOL) return null

        val resultSlot = allocSlot()
        val elseLabel = builder.label()
        val endLabel = builder.label()

        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(condValue.slot), CmdBuilder.Operand.LabelRef(elseLabel))
        )
        val thenValue = compileStatementValue(stmt.ifBody) ?: return null
        emitMove(thenValue, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))

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
        val localCount = maxOf(nextSlot, resultSlot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            scopeSlotDepths,
            scopeSlotIndices,
            scopeSlotNames,
            localSlotNames,
            localSlotMutables,
            localSlotDepths
        )
    }

    private fun compileForIn(name: String, stmt: net.sergeych.lyng.ForInStatement): CmdFunction? {
        val resultSlot = emitForIn(stmt, true) ?: return null
        builder.emit(Opcode.RET, resultSlot)
        val localCount = maxOf(nextSlot, resultSlot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            scopeSlotDepths,
            scopeSlotIndices,
            scopeSlotNames,
            localSlotNames,
            localSlotMutables,
            localSlotDepths
        )
    }

    private fun compileBlock(name: String, stmt: BlockStatement): CmdFunction? {
        val result = emitBlock(stmt, true) ?: return null
        builder.emit(Opcode.RET, result.slot)
        val localCount = maxOf(nextSlot, result.slot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            scopeSlotDepths,
            scopeSlotIndices,
            scopeSlotNames,
            localSlotNames,
            localSlotMutables,
            localSlotDepths
        )
    }

    private fun compileVarDecl(name: String, stmt: VarDeclStatement): CmdFunction? {
        val result = emitVarDecl(stmt) ?: return null
        builder.emit(Opcode.RET, result.slot)
        val localCount = maxOf(nextSlot, result.slot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            scopeSlotDepths,
            scopeSlotIndices,
            scopeSlotNames,
            localSlotNames,
            localSlotMutables,
            localSlotDepths
        )
    }

    private fun compileStatementValue(stmt: Statement): CompiledValue? {
        return when (stmt) {
            is ExpressionStatement -> compileRefWithFallback(stmt.ref, null, stmt.pos)
            else -> null
        }
    }

    private fun compileStatementValueOrFallback(stmt: Statement, needResult: Boolean = true): CompiledValue? {
        val target = if (stmt is BytecodeStatement) stmt.original else stmt
        return if (needResult) {
            when (target) {
                is ExpressionStatement -> compileRefWithFallback(target.ref, null, target.pos)
                is IfStatement -> compileIfExpression(target)
                is net.sergeych.lyng.ForInStatement -> {
                    val resultSlot = emitForIn(target, true) ?: return null
                    updateSlotType(resultSlot, SlotType.OBJ)
                    CompiledValue(resultSlot, SlotType.OBJ)
                }
                is BlockStatement -> emitBlock(target, true)
                is VarDeclStatement -> emitVarDecl(target)
                else -> {
                    val slot = allocSlot()
                    val id = builder.addFallback(target)
                    builder.emit(Opcode.EVAL_FALLBACK, id, slot)
                    builder.emit(Opcode.BOX_OBJ, slot, slot)
                    updateSlotType(slot, SlotType.OBJ)
                    CompiledValue(slot, SlotType.OBJ)
                }
            }
        } else {
            when (target) {
                is ExpressionStatement -> {
                    val ref = target.ref
                    if (ref is IncDecRef) {
                        compileIncDec(ref, false)
                    } else {
                        compileRefWithFallback(ref, null, target.pos)
                    }
                }
                is IfStatement -> compileIfStatement(target)
                is net.sergeych.lyng.ForInStatement -> {
                    val resultSlot = emitForIn(target, false) ?: return null
                    CompiledValue(resultSlot, SlotType.OBJ)
                }
                is BlockStatement -> emitBlock(target, false)
                is VarDeclStatement -> emitVarDecl(target)
                else -> {
                    val slot = allocSlot()
                    val id = builder.addFallback(target)
                    builder.emit(Opcode.EVAL_FALLBACK, id, slot)
                    builder.emit(Opcode.BOX_OBJ, slot, slot)
                    updateSlotType(slot, SlotType.OBJ)
                    CompiledValue(slot, SlotType.OBJ)
                }
            }
        }
    }

    private fun emitBlock(stmt: BlockStatement, needResult: Boolean): CompiledValue? {
        val planId = builder.addConst(BytecodeConst.SlotPlan(stmt.slotPlan))
        builder.emit(Opcode.PUSH_SCOPE, planId)
        resetAddrCache()
        val statements = stmt.statements()
        var lastValue: CompiledValue? = null
        for ((index, statement) in statements.withIndex()) {
            val isLast = index == statements.lastIndex
            val wantResult = needResult && isLast
            val value = compileStatementValueOrFallback(statement, wantResult) ?: return null
            if (wantResult) {
                lastValue = value
            }
        }
        val result = if (needResult) {
            var value = lastValue ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                CompiledValue(slot, SlotType.OBJ)
            }
            if (value.slot < scopeSlotCount) {
                val captured = allocSlot()
                emitMove(value, captured)
                value = CompiledValue(captured, value.type)
            }
            value
        } else {
            lastValue ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                CompiledValue(slot, SlotType.OBJ)
            }
        }
        builder.emit(Opcode.POP_SCOPE)
        resetAddrCache()
        return result
    }

    private fun emitVarDecl(stmt: VarDeclStatement): CompiledValue? {
        val localSlot = if (allowLocalSlots && stmt.slotIndex != null) {
            val depth = stmt.slotDepth ?: 0
            val key = ScopeSlotKey(depth, stmt.slotIndex)
            val localIndex = localSlotIndexByKey[key]
            localIndex?.let { scopeSlotCount + it }
        } else {
            null
        }
        if (localSlot != null) {
            val value = stmt.initializer?.let { compileStatementValueOrFallback(it) } ?: run {
                builder.emit(Opcode.CONST_NULL, localSlot)
                updateSlotType(localSlot, SlotType.OBJ)
                CompiledValue(localSlot, SlotType.OBJ)
            }
            if (value.slot != localSlot) {
                emitMove(value, localSlot)
            }
            updateSlotType(localSlot, value.type)
            val declId = builder.addConst(
                BytecodeConst.LocalDecl(
                    stmt.name,
                    stmt.isMutable,
                    stmt.visibility,
                    stmt.isTransient
                )
            )
            builder.emit(Opcode.DECL_LOCAL, declId, localSlot)
            return CompiledValue(localSlot, value.type)
        }
        val value = stmt.initializer?.let { compileStatementValueOrFallback(it) } ?: run {
            val slot = allocSlot()
            builder.emit(Opcode.CONST_NULL, slot)
            updateSlotType(slot, SlotType.OBJ)
            CompiledValue(slot, SlotType.OBJ)
        }
        val declId = builder.addConst(
            BytecodeConst.LocalDecl(
                stmt.name,
                stmt.isMutable,
                stmt.visibility,
                stmt.isTransient
            )
        )
        builder.emit(Opcode.DECL_LOCAL, declId, value.slot)
        if (value.type != SlotType.UNKNOWN) {
            updateSlotTypeByName(stmt.name, value.type)
        }
        return value
    }
    private fun emitForIn(stmt: net.sergeych.lyng.ForInStatement, wantResult: Boolean): Int? {
        if (stmt.canBreak) return null
        val range = stmt.constRange ?: return null
        val loopLocalIndex = localSlotIndexByName[stmt.loopVarName] ?: return null
        val loopSlotId = scopeSlotCount + loopLocalIndex

        val iSlot = allocSlot()
        val endSlot = allocSlot()
        val startId = builder.addConst(BytecodeConst.IntVal(range.start))
        val endId = builder.addConst(BytecodeConst.IntVal(range.endExclusive))
        builder.emit(Opcode.CONST_INT, startId, iSlot)
        builder.emit(Opcode.CONST_INT, endId, endSlot)

        val resultSlot = allocSlot()
        if (wantResult) {
            val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
            builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)
        }

        val loopLabel = builder.label()
        val endLabel = builder.label()
        builder.mark(loopLabel)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_GTE_INT, iSlot, endSlot, cmpSlot)
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(endLabel))
        )
        builder.emit(Opcode.MOVE_INT, iSlot, loopSlotId)
        updateSlotType(loopSlotId, SlotType.INT)
        updateSlotTypeByName(stmt.loopVarName, SlotType.INT)
        val bodyValue = compileStatementValueOrFallback(stmt.body, wantResult) ?: return null
        if (wantResult) {
            val bodyObj = ensureObjSlot(bodyValue)
            builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
        }
        builder.emit(Opcode.INC_INT, iSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(loopLabel)))

        builder.mark(endLabel)
        if (stmt.elseStatement != null) {
            val elseValue = compileStatementValueOrFallback(stmt.elseStatement, wantResult) ?: return null
            if (wantResult) {
                val elseObj = ensureObjSlot(elseValue)
                builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
            }
        }
        return resultSlot
    }

    private fun compileIfStatement(stmt: IfStatement): CompiledValue? {
        val condition = compileCondition(stmt.condition, stmt.pos) ?: return null
        if (condition.type != SlotType.BOOL) return null
        val elseLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(condition.slot), CmdBuilder.Operand.LabelRef(elseLabel))
        )
        compileStatementValueOrFallback(stmt.ifBody, false) ?: return null
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(elseLabel)
        stmt.elseBody?.let {
            compileStatementValueOrFallback(it, false) ?: return null
        }
        builder.mark(endLabel)
        return condition
    }

    private fun updateSlotTypeByName(name: String, type: SlotType) {
        val localIndex = localSlotIndexByName[name]
        if (localIndex != null) {
            updateSlotType(scopeSlotCount + localIndex, type)
            return
        }
        for ((key, index) in scopeSlotMap) {
            if (scopeSlotNameMap[key] == name) {
                updateSlotType(index, type)
            }
        }
    }

    private fun compileIfExpression(stmt: IfStatement): CompiledValue? {
        val condition = compileCondition(stmt.condition, stmt.pos) ?: return null
        if (condition.type != SlotType.BOOL) return null
        val resultSlot = allocSlot()
        val elseLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(condition.slot), CmdBuilder.Operand.LabelRef(elseLabel))
        )
        val thenValue = compileStatementValueOrFallback(stmt.ifBody) ?: return null
        val thenObj = ensureObjSlot(thenValue)
        builder.emit(Opcode.MOVE_OBJ, thenObj.slot, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(elseLabel)
        if (stmt.elseBody != null) {
            val elseValue = compileStatementValueOrFallback(stmt.elseBody) ?: return null
            val elseObj = ensureObjSlot(elseValue)
            builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
        } else {
            val id = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
            builder.emit(Opcode.CONST_OBJ, id, resultSlot)
        }
        builder.mark(endLabel)
        updateSlotType(resultSlot, SlotType.OBJ)
        return CompiledValue(resultSlot, SlotType.OBJ)
    }

    private fun compileCondition(stmt: Statement, pos: Pos): CompiledValue? {
        return when (stmt) {
            is ExpressionStatement -> compileRefWithFallback(stmt.ref, SlotType.BOOL, stmt.pos)
            else -> {
                val slot = allocSlot()
                val id = builder.addFallback(ToBoolStatement(stmt, pos))
                builder.emit(Opcode.EVAL_FALLBACK, id, slot)
                updateSlotType(slot, SlotType.BOOL)
                CompiledValue(slot, SlotType.BOOL)
            }
        }
    }

    private fun resetAddrCache() {
        addrSlotByScopeSlot.clear()
    }

    private fun ensureScopeAddr(scopeSlot: Int): Int {
        val existing = addrSlotByScopeSlot[scopeSlot]
        if (existing != null) return existing
        val addrSlot = nextAddrSlot++
        addrSlotByScopeSlot[scopeSlot] = addrSlot
        builder.emit(Opcode.RESOLVE_SCOPE_SLOT, scopeSlot, addrSlot)
        return addrSlot
    }

    private fun emitLoadFromAddr(addrSlot: Int, dstSlot: Int, type: SlotType) {
        when (type) {
            SlotType.INT -> builder.emit(Opcode.LOAD_INT_ADDR, addrSlot, dstSlot)
            SlotType.REAL -> builder.emit(Opcode.LOAD_REAL_ADDR, addrSlot, dstSlot)
            SlotType.BOOL -> builder.emit(Opcode.LOAD_BOOL_ADDR, addrSlot, dstSlot)
            SlotType.OBJ -> builder.emit(Opcode.LOAD_OBJ_ADDR, addrSlot, dstSlot)
            else -> builder.emit(Opcode.LOAD_OBJ_ADDR, addrSlot, dstSlot)
        }
    }

    private fun emitStoreToAddr(srcSlot: Int, addrSlot: Int, type: SlotType) {
        when (type) {
            SlotType.INT -> builder.emit(Opcode.STORE_INT_ADDR, srcSlot, addrSlot)
            SlotType.REAL -> builder.emit(Opcode.STORE_REAL_ADDR, srcSlot, addrSlot)
            SlotType.BOOL -> builder.emit(Opcode.STORE_BOOL_ADDR, srcSlot, addrSlot)
            SlotType.OBJ -> builder.emit(Opcode.STORE_OBJ_ADDR, srcSlot, addrSlot)
            else -> builder.emit(Opcode.STORE_OBJ_ADDR, srcSlot, addrSlot)
        }
    }

    private fun emitMove(value: CompiledValue, dstSlot: Int) {
        val srcSlot = value.slot
        val srcIsScope = srcSlot < scopeSlotCount
        val dstIsScope = dstSlot < scopeSlotCount
        if (value.type != SlotType.UNKNOWN) {
            if (srcIsScope && !dstIsScope) {
                val addrSlot = ensureScopeAddr(srcSlot)
                emitLoadFromAddr(addrSlot, dstSlot, value.type)
                return
            }
            if (dstIsScope) {
                val addrSlot = ensureScopeAddr(dstSlot)
                emitStoreToAddr(srcSlot, addrSlot, value.type)
                return
            }
        }
        when (value.type) {
            SlotType.INT -> builder.emit(Opcode.MOVE_INT, srcSlot, dstSlot)
            SlotType.REAL -> builder.emit(Opcode.MOVE_REAL, srcSlot, dstSlot)
            SlotType.BOOL -> builder.emit(Opcode.MOVE_BOOL, srcSlot, dstSlot)
            else -> builder.emit(Opcode.MOVE_OBJ, srcSlot, dstSlot)
        }
    }

    private fun compileRefWithFallback(ref: ObjRef, forceType: SlotType?, pos: Pos): CompiledValue? {
        var compiled = compileRef(ref)
        if (compiled != null) {
            if (forceType == null) return compiled
            if (compiled.type == forceType) return compiled
            if (compiled.type == SlotType.UNKNOWN) {
                compiled = null
            }
        }
        val slot = allocSlot()
        val stmt = if (forceType == SlotType.BOOL) {
            ToBoolStatement(ExpressionStatement(ref, pos), pos)
        } else {
            ExpressionStatement(ref, pos)
        }
        val id = builder.addFallback(stmt)
        builder.emit(Opcode.EVAL_FALLBACK, id, slot)
        if (forceType == null) {
            builder.emit(Opcode.BOX_OBJ, slot, slot)
            updateSlotType(slot, SlotType.OBJ)
            return CompiledValue(slot, SlotType.OBJ)
        }
        updateSlotType(slot, forceType)
        return CompiledValue(slot, forceType)
    }

    private fun refSlot(ref: LocalSlotRef): Int = ref.slot
    private fun refDepth(ref: LocalSlotRef): Int = ref.depth
    private fun refScopeDepth(ref: LocalSlotRef): Int = ref.scopeDepth
    private fun binaryLeft(ref: BinaryOpRef): ObjRef = ref.left
    private fun binaryRight(ref: BinaryOpRef): ObjRef = ref.right
    private fun binaryOp(ref: BinaryOpRef): BinOp = ref.op
    private fun unaryOperand(ref: UnaryOpRef): ObjRef = ref.a
    private fun unaryOp(ref: UnaryOpRef): UnaryOp = ref.op
    private fun assignTarget(ref: AssignRef): LocalSlotRef? = ref.target as? LocalSlotRef
    private fun assignValue(ref: AssignRef): ObjRef = ref.value
    private fun refPos(ref: BinaryOpRef): Pos = Pos.builtIn

    private fun resolveSlot(ref: LocalSlotRef): Int? {
        val localKey = ScopeSlotKey(refScopeDepth(ref), refSlot(ref))
        val localIndex = localSlotIndexByKey[localKey]
        if (localIndex != null) return scopeSlotCount + localIndex
        val nameIndex = localSlotIndexByName[ref.name]
        if (nameIndex != null) return scopeSlotCount + nameIndex
        val scopeKey = ScopeSlotKey(refDepth(ref), refSlot(ref))
        return scopeSlotMap[scopeKey]
    }

    private fun updateSlotType(slot: Int, type: SlotType) {
        if (type == SlotType.UNKNOWN) {
            slotTypes.remove(slot)
        } else {
            slotTypes[slot] = type
        }
    }

    private fun prepareCompilation(stmt: Statement) {
        builder = CmdBuilder()
        nextSlot = 0
        nextAddrSlot = 0
        slotTypes.clear()
        scopeSlotMap.clear()
        localSlotInfoMap.clear()
        localSlotIndexByKey.clear()
        localSlotIndexByName.clear()
        localSlotNames = emptyArray()
        localSlotMutables = BooleanArray(0)
        localSlotDepths = IntArray(0)
        declaredLocalKeys.clear()
        intLoopVarNames.clear()
        addrSlotByScopeSlot.clear()
        if (allowLocalSlots) {
            collectLoopVarNames(stmt)
            collectScopeSlots(stmt)
            collectLoopSlotPlans(stmt, 0)
        }
        scopeSlotCount = scopeSlotMap.size
        scopeSlotDepths = IntArray(scopeSlotCount)
        scopeSlotIndices = IntArray(scopeSlotCount)
        scopeSlotNames = arrayOfNulls(scopeSlotCount)
        for ((key, index) in scopeSlotMap) {
            scopeSlotDepths[index] = key.depth
            val name = scopeSlotNameMap[key]
            scopeSlotIndices[index] = key.slot
            scopeSlotNames[index] = name
        }
        if (allowLocalSlots && localSlotInfoMap.isNotEmpty()) {
            val names = ArrayList<String?>(localSlotInfoMap.size)
            val mutables = BooleanArray(localSlotInfoMap.size)
            val depths = IntArray(localSlotInfoMap.size)
            var index = 0
            for ((key, info) in localSlotInfoMap) {
                localSlotIndexByKey[key] = index
                if (!localSlotIndexByName.containsKey(info.name)) {
                    localSlotIndexByName[info.name] = index
                }
                names.add(info.name)
                mutables[index] = info.isMutable
                depths[index] = info.depth
                index += 1
            }
            localSlotNames = names.toTypedArray()
            localSlotMutables = mutables
            localSlotDepths = depths
        }
        nextSlot = scopeSlotCount + localSlotNames.size
    }

    private fun collectScopeSlots(stmt: Statement) {
        if (stmt is BytecodeStatement) {
            collectScopeSlots(stmt.original)
            return
        }
        when (stmt) {
            is ExpressionStatement -> collectScopeSlotsRef(stmt.ref)
            is BlockStatement -> {
                for (child in stmt.statements()) {
                    collectScopeSlots(child)
                }
            }
            is VarDeclStatement -> {
                val slotIndex = stmt.slotIndex
                val slotDepth = stmt.slotDepth
                if (allowLocalSlots && slotIndex != null && slotDepth != null) {
                    declaredLocalKeys.add(ScopeSlotKey(slotDepth, slotIndex))
                }
                stmt.initializer?.let { collectScopeSlots(it) }
            }
            is IfStatement -> {
                collectScopeSlots(stmt.condition)
                collectScopeSlots(stmt.ifBody)
                stmt.elseBody?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.ForInStatement -> {
                collectScopeSlots(stmt.source)
                collectScopeSlots(stmt.body)
                stmt.elseStatement?.let { collectScopeSlots(it) }
            }
            else -> {}
        }
    }

    private fun collectLoopSlotPlans(stmt: Statement, scopeDepth: Int) {
        if (stmt is BytecodeStatement) {
            collectLoopSlotPlans(stmt.original, scopeDepth)
            return
        }
        when (stmt) {
            is net.sergeych.lyng.ForInStatement -> {
                collectLoopSlotPlans(stmt.source, scopeDepth)
                val loopDepth = scopeDepth + 1
                for ((name, slotIndex) in stmt.loopSlotPlan) {
                    val key = ScopeSlotKey(loopDepth, slotIndex)
                    if (!localSlotInfoMap.containsKey(key)) {
                        localSlotInfoMap[key] = LocalSlotInfo(name, isMutable = true, depth = loopDepth)
                    }
                }
                collectLoopSlotPlans(stmt.body, loopDepth)
                stmt.elseStatement?.let { collectLoopSlotPlans(it, loopDepth) }
            }
            is BlockStatement -> {
                val nextDepth = scopeDepth + 1
                for (child in stmt.statements()) {
                    collectLoopSlotPlans(child, nextDepth)
                }
            }
            is IfStatement -> {
                collectLoopSlotPlans(stmt.condition, scopeDepth)
                collectLoopSlotPlans(stmt.ifBody, scopeDepth)
                stmt.elseBody?.let { collectLoopSlotPlans(it, scopeDepth) }
            }
            is VarDeclStatement -> {
                stmt.initializer?.let { collectLoopSlotPlans(it, scopeDepth) }
            }
            is ExpressionStatement -> {
                // no-op
            }
            else -> {}
        }
    }

    private fun collectLoopVarNames(stmt: Statement) {
        if (stmt is BytecodeStatement) {
            collectLoopVarNames(stmt.original)
            return
        }
        when (stmt) {
            is net.sergeych.lyng.ForInStatement -> {
                if (stmt.constRange != null) {
                    intLoopVarNames.add(stmt.loopVarName)
                }
                collectLoopVarNames(stmt.source)
                collectLoopVarNames(stmt.body)
                stmt.elseStatement?.let { collectLoopVarNames(it) }
            }
            is BlockStatement -> {
                for (child in stmt.statements()) {
                    collectLoopVarNames(child)
                }
            }
            is VarDeclStatement -> {
                stmt.initializer?.let { collectLoopVarNames(it) }
            }
            is IfStatement -> {
                collectLoopVarNames(stmt.condition)
                collectLoopVarNames(stmt.ifBody)
                stmt.elseBody?.let { collectLoopVarNames(it) }
            }
            is ExpressionStatement -> collectLoopVarNamesRef(stmt.ref)
            else -> {}
        }
    }

    private fun collectLoopVarNamesRef(ref: ObjRef) {
        when (ref) {
            is BinaryOpRef -> {
                collectLoopVarNamesRef(binaryLeft(ref))
                collectLoopVarNamesRef(binaryRight(ref))
            }
            is UnaryOpRef -> collectLoopVarNamesRef(unaryOperand(ref))
            is AssignRef -> collectLoopVarNamesRef(assignValue(ref))
            is AssignOpRef -> {
                collectLoopVarNamesRef(ref.target)
                collectLoopVarNamesRef(ref.value)
            }
            is IncDecRef -> collectLoopVarNamesRef(ref.target)
            is ConditionalRef -> {
                collectLoopVarNamesRef(ref.condition)
                collectLoopVarNamesRef(ref.ifTrue)
                collectLoopVarNamesRef(ref.ifFalse)
            }
            is ElvisRef -> {
                collectLoopVarNamesRef(ref.left)
                collectLoopVarNamesRef(ref.right)
            }
            else -> {}
        }
    }

    private fun collectScopeSlotsRef(ref: ObjRef) {
        when (ref) {
            is LocalSlotRef -> {
                val localKey = ScopeSlotKey(refScopeDepth(ref), refSlot(ref))
                val shouldLocalize = declaredLocalKeys.contains(localKey) ||
                    intLoopVarNames.contains(ref.name)
                if (allowLocalSlots && !ref.isDelegated && shouldLocalize) {
                    if (!localSlotInfoMap.containsKey(localKey)) {
                        localSlotInfoMap[localKey] = LocalSlotInfo(ref.name, ref.isMutable, localKey.depth)
                    }
                    return
                }
                val key = ScopeSlotKey(refDepth(ref), refSlot(ref))
                if (!scopeSlotMap.containsKey(key)) {
                    scopeSlotMap[key] = scopeSlotMap.size
                }
                if (!scopeSlotNameMap.containsKey(key)) {
                    scopeSlotNameMap[key] = ref.name
                }
            }
            is BinaryOpRef -> {
                collectScopeSlotsRef(binaryLeft(ref))
                collectScopeSlotsRef(binaryRight(ref))
            }
            is UnaryOpRef -> collectScopeSlotsRef(unaryOperand(ref))
            is AssignRef -> {
                val target = assignTarget(ref)
                if (target != null) {
                    val localKey = ScopeSlotKey(refScopeDepth(target), refSlot(target))
                    val shouldLocalize = declaredLocalKeys.contains(localKey) ||
                        intLoopVarNames.contains(target.name)
                    if (allowLocalSlots && !target.isDelegated && shouldLocalize) {
                        if (!localSlotInfoMap.containsKey(localKey)) {
                            localSlotInfoMap[localKey] = LocalSlotInfo(target.name, target.isMutable, localKey.depth)
                        }
                    } else {
                        val key = ScopeSlotKey(refDepth(target), refSlot(target))
                        if (!scopeSlotMap.containsKey(key)) {
                            scopeSlotMap[key] = scopeSlotMap.size
                        }
                        if (!scopeSlotNameMap.containsKey(key)) {
                            scopeSlotNameMap[key] = target.name
                        }
                    }
                }
                collectScopeSlotsRef(assignValue(ref))
            }
            is AssignOpRef -> {
                collectScopeSlotsRef(ref.target)
                collectScopeSlotsRef(ref.value)
            }
            is IncDecRef -> collectScopeSlotsRef(ref.target)
            is ConditionalRef -> {
                collectScopeSlotsRef(ref.condition)
                collectScopeSlotsRef(ref.ifTrue)
                collectScopeSlotsRef(ref.ifFalse)
            }
            is ElvisRef -> {
                collectScopeSlotsRef(ref.left)
                collectScopeSlotsRef(ref.right)
            }
            is CallRef -> {
                collectScopeSlotsRef(ref.target)
                collectScopeSlotsArgs(ref.args)
            }
            is MethodCallRef -> {
                collectScopeSlotsRef(ref.receiver)
                collectScopeSlotsArgs(ref.args)
            }
            else -> {}
        }
    }

    private fun collectScopeSlotsArgs(args: List<ParsedArgument>) {
        for (arg in args) {
            val stmt = arg.value
            if (stmt is ExpressionStatement) {
                collectScopeSlotsRef(stmt.ref)
            }
        }
    }

    private data class ScopeSlotKey(val depth: Int, val slot: Int)
}
