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
    private val returnLabels: Set<String> = emptySet(),
    private val rangeLocalNames: Set<String> = emptySet(),
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
    private val scopeSlotIndexByName = LinkedHashMap<String, Int>()
    private val pendingScopeNameRefs = LinkedHashSet<String>()
    private val addrSlotByScopeSlot = LinkedHashMap<Int, Int>()
    private data class LocalSlotInfo(val name: String, val isMutable: Boolean, val depth: Int)
    private val localSlotInfoMap = LinkedHashMap<ScopeSlotKey, LocalSlotInfo>()
    private val localSlotIndexByKey = LinkedHashMap<ScopeSlotKey, Int>()
    private val localSlotIndexByName = LinkedHashMap<String, Int>()
    private val loopSlotOverrides = LinkedHashMap<String, Int>()
    private var localSlotNames = emptyArray<String?>()
    private var localSlotMutables = BooleanArray(0)
    private var localSlotDepths = IntArray(0)
    private val declaredLocalKeys = LinkedHashSet<ScopeSlotKey>()
    private val localRangeRefs = LinkedHashMap<ScopeSlotKey, RangeRef>()
    private val slotTypes = mutableMapOf<Int, SlotType>()
    private val intLoopVarNames = LinkedHashSet<String>()
    private val loopStack = ArrayDeque<LoopContext>()
    private val virtualScopeDepths = LinkedHashSet<Int>()

    private data class LoopContext(
        val label: String?,
        val breakLabel: CmdBuilder.Label,
        val continueLabel: CmdBuilder.Label,
        val breakFlagSlot: Int,
        val resultSlot: Int?,
    )

    fun compileStatement(name: String, stmt: net.sergeych.lyng.Statement): CmdFunction? {
        prepareCompilation(stmt)
        return when (stmt) {
            is ExpressionStatement -> compileExpression(name, stmt)
            is net.sergeych.lyng.IfStatement -> compileIf(name, stmt)
            is net.sergeych.lyng.ForInStatement -> compileForIn(name, stmt)
            is net.sergeych.lyng.DoWhileStatement -> compileDoWhile(name, stmt)
            is net.sergeych.lyng.WhileStatement -> compileWhile(name, stmt)
            is BlockStatement -> compileBlock(name, stmt)
            is VarDeclStatement -> compileVarDecl(name, stmt)
            is net.sergeych.lyng.ThrowStatement -> compileThrowStatement(name, stmt)
            is net.sergeych.lyng.ExtensionPropertyDeclStatement -> compileExtensionPropertyDecl(name, stmt)
            else -> null
        }
    }

    private fun compileThrowStatement(name: String, stmt: net.sergeych.lyng.ThrowStatement): CmdFunction? {
        prepareCompilation(stmt)
        compileThrow(stmt) ?: return null
        return builder.build(
            name,
            localCount = nextSlot - scopeSlotCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotDepths,
            scopeSlotIndices,
            scopeSlotNames,
            localSlotNames,
            localSlotMutables,
            localSlotDepths
        )
    }

    private fun compileExtensionPropertyDecl(
        name: String,
        stmt: net.sergeych.lyng.ExtensionPropertyDeclStatement,
    ): CmdFunction? {
        prepareCompilation(stmt)
        val value = emitExtensionPropertyDecl(stmt)
        builder.emit(Opcode.RET, value.slot)
        val localCount = maxOf(nextSlot, value.slot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotDepths,
            scopeSlotIndices,
            scopeSlotNames,
            localSlotNames,
            localSlotMutables,
            localSlotDepths
        )
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
            returnLabels = returnLabels,
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

    private fun compileNameLookup(name: String): CompiledValue {
        val nameId = builder.addConst(BytecodeConst.StringVal(name))
        val slot = allocSlot()
        builder.emit(Opcode.GET_NAME, nameId, slot)
        updateSlotType(slot, SlotType.OBJ)
        return CompiledValue(slot, SlotType.OBJ)
    }

    private fun compileRef(ref: ObjRef): CompiledValue? {
        return when (ref) {
            is ConstRef -> compileConst(ref.constValue)
            is LocalSlotRef -> {
                if (!allowLocalSlots) return null
                if (ref.isDelegated) return null
                if (ref.name.isEmpty()) return null
                val mapped = resolveSlot(ref) ?: return compileNameLookup(ref.name)
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
            is LocalVarRef -> compileNameLookup(ref.name)
            is ValueFnRef -> {
                val constId = builder.addConst(BytecodeConst.ValueFn(ref.valueFn()))
                val slot = allocSlot()
                builder.emit(Opcode.EVAL_VALUE_FN, constId, slot)
                updateSlotType(slot, SlotType.OBJ)
                CompiledValue(slot, SlotType.OBJ)
            }
            is ListLiteralRef -> compileListLiteral(ref)
            is ThisMethodSlotCallRef -> compileThisMethodSlotCall(ref)
            is StatementRef -> {
                val constId = builder.addConst(BytecodeConst.StatementVal(ref.statement))
                val slot = allocSlot()
                builder.emit(Opcode.EVAL_STMT, constId, slot)
                updateSlotType(slot, SlotType.OBJ)
                CompiledValue(slot, SlotType.OBJ)
            }
            is BinaryOpRef -> compileBinary(ref) ?: compileEvalRef(ref)
            is UnaryOpRef -> compileUnary(ref)
            is AssignRef -> compileAssign(ref) ?: compileEvalRef(ref)
            is AssignOpRef -> compileAssignOp(ref) ?: compileEvalRef(ref)
            is AssignIfNullRef -> compileAssignIfNull(ref)
            is IncDecRef -> compileIncDec(ref, true)
            is ConditionalRef -> compileConditional(ref)
            is ElvisRef -> compileElvis(ref)
            is CallRef -> compileCall(ref)
            is MethodCallRef -> compileMethodCall(ref)
            is FieldRef -> compileFieldRef(ref)
            is ImplicitThisMemberRef -> {
                val nameId = builder.addConst(BytecodeConst.StringVal(ref.name))
                val slot = allocSlot()
                builder.emit(Opcode.GET_THIS_MEMBER, nameId, slot)
                updateSlotType(slot, SlotType.OBJ)
                CompiledValue(slot, SlotType.OBJ)
            }
            is ImplicitThisMethodCallRef -> compileEvalRef(ref)
            is IndexRef -> compileIndexRef(ref)
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

    private fun compileEvalRef(ref: ObjRef): CompiledValue? {
        val slot = allocSlot()
        val id = builder.addConst(BytecodeConst.Ref(ref))
        builder.emit(Opcode.EVAL_REF, id, slot)
        updateSlotType(slot, SlotType.OBJ)
        return CompiledValue(slot, SlotType.OBJ)
    }

    private fun compileListLiteral(ref: ListLiteralRef): CompiledValue? {
        val entries = ref.entries()
        val count = entries.size
        val baseSlot = nextSlot
        val entrySlots = IntArray(count) { allocSlot() }
        val spreads = ArrayList<Boolean>(count)
        for ((index, entry) in entries.withIndex()) {
            val value = when (entry) {
                is net.sergeych.lyng.ListEntry.Element ->
                    compileRefWithFallback(entry.ref, null, Pos.builtIn)
                is net.sergeych.lyng.ListEntry.Spread ->
                    compileRefWithFallback(entry.ref, null, Pos.builtIn)
            } ?: return null
            emitMove(value, entrySlots[index])
            spreads.add(entry is net.sergeych.lyng.ListEntry.Spread)
        }
        val planId = builder.addConst(BytecodeConst.ListLiteralPlan(spreads))
        val dst = allocSlot()
        builder.emit(Opcode.LIST_LITERAL, planId, baseSlot, count, dst)
        updateSlotType(dst, SlotType.OBJ)
        return CompiledValue(dst, SlotType.OBJ)
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
                when (a.type) {
                    SlotType.BOOL -> builder.emit(Opcode.NOT_BOOL, a.slot, out)
                    SlotType.INT -> {
                        val tmp = allocSlot()
                        builder.emit(Opcode.INT_TO_BOOL, a.slot, tmp)
                        builder.emit(Opcode.NOT_BOOL, tmp, out)
                    }
                    SlotType.OBJ, SlotType.UNKNOWN -> {
                        val obj = ensureObjSlot(a)
                        val tmp = allocSlot()
                        builder.emit(Opcode.OBJ_TO_BOOL, obj.slot, tmp)
                        builder.emit(Opcode.NOT_BOOL, tmp, out)
                    }
                    else -> return null
                }
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
        if (op == BinOp.IN || op == BinOp.NOTIN) {
            val leftValue = compileRefWithFallback(binaryLeft(ref), null, refPos(ref)) ?: return null
            val rightValue = compileRefWithFallback(binaryRight(ref), null, refPos(ref)) ?: return null
            val leftObj = ensureObjSlot(leftValue)
            val rightObj = ensureObjSlot(rightValue)
            val boolSlot = allocSlot()
            builder.emit(Opcode.CONTAINS_OBJ, rightObj.slot, leftObj.slot, boolSlot)
            updateSlotType(boolSlot, SlotType.BOOL)
            if (op == BinOp.NOTIN) {
                val outSlot = allocSlot()
                builder.emit(Opcode.NOT_BOOL, boolSlot, outSlot)
                updateSlotType(outSlot, SlotType.BOOL)
                return CompiledValue(outSlot, SlotType.BOOL)
            }
            return CompiledValue(boolSlot, SlotType.BOOL)
        }
        if (op == BinOp.IS || op == BinOp.NOTIS) {
            val objValue = compileRefWithFallback(binaryLeft(ref), null, refPos(ref)) ?: return null
            val typeValue = compileRefWithFallback(binaryRight(ref), null, refPos(ref)) ?: return null
            val objSlot = ensureObjSlot(objValue)
            val typeSlot = ensureObjSlot(typeValue)
            val checkSlot = allocSlot()
            builder.emit(Opcode.CHECK_IS, objSlot.slot, typeSlot.slot, checkSlot)
            updateSlotType(checkSlot, SlotType.BOOL)
            if (op == BinOp.NOTIS) {
                val outSlot = allocSlot()
                builder.emit(Opcode.NOT_BOOL, checkSlot, outSlot)
                updateSlotType(outSlot, SlotType.BOOL)
                return CompiledValue(outSlot, SlotType.BOOL)
            }
            return CompiledValue(checkSlot, SlotType.BOOL)
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
        val localTarget = assignTarget(ref)
        if (localTarget != null) {
            if (!allowLocalSlots) return null
            if (!localTarget.isMutable || localTarget.isDelegated) return null
            val value = compileRef(assignValue(ref)) ?: return null
            val slot = resolveSlot(localTarget) ?: return null
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
        val value = compileRef(assignValue(ref)) ?: return null
        val target = ref.target
        if (target is FieldRef) {
            val receiver = compileRefWithFallback(target.target, null, Pos.builtIn) ?: return null
            val nameId = builder.addConst(BytecodeConst.StringVal(target.name))
            if (nameId > 0xFFFF) return null
            if (!target.isOptional) {
                builder.emit(Opcode.SET_FIELD, receiver.slot, nameId, value.slot)
            } else {
                val nullSlot = allocSlot()
                builder.emit(Opcode.CONST_NULL, nullSlot)
                val cmpSlot = allocSlot()
                builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
                val endLabel = builder.label()
                builder.emit(
                    Opcode.JMP_IF_TRUE,
                    listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(endLabel))
                )
                builder.emit(Opcode.SET_FIELD, receiver.slot, nameId, value.slot)
                builder.mark(endLabel)
            }
            return value
        }
        if (target is ImplicitThisMemberRef) {
            val nameId = builder.addConst(BytecodeConst.StringVal(target.name))
            if (nameId > 0xFFFF) return null
            builder.emit(Opcode.SET_THIS_MEMBER, nameId, value.slot)
            return value
        }
        if (target is IndexRef) {
            val receiver = compileRefWithFallback(target.targetRef, null, Pos.builtIn) ?: return null
            if (!target.optionalRef) {
                val index = compileRefWithFallback(target.indexRef, null, Pos.builtIn) ?: return null
                builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, value.slot)
            } else {
                val nullSlot = allocSlot()
                builder.emit(Opcode.CONST_NULL, nullSlot)
                val cmpSlot = allocSlot()
                builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
                val endLabel = builder.label()
                builder.emit(
                    Opcode.JMP_IF_TRUE,
                    listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(endLabel))
                )
                val index = compileRefWithFallback(target.indexRef, null, Pos.builtIn) ?: return null
                builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, value.slot)
                builder.mark(endLabel)
            }
            return value
        }
        return null
    }

    private fun compileAssignOp(ref: AssignOpRef): CompiledValue? {
        val localTarget = ref.target as? LocalSlotRef
        if (localTarget != null) {
            if (!allowLocalSlots) return compileEvalRef(ref)
            if (localTarget.isDelegated) return compileEvalRef(ref)
            if (!localTarget.isMutable) return compileEvalRef(ref)
            val slot = resolveSlot(localTarget) ?: return null
            val targetType = slotTypes[slot] ?: SlotType.OBJ
            var rhs = compileRef(ref.value) ?: return compileEvalRef(ref)
            if (targetType == SlotType.OBJ && rhs.type != SlotType.OBJ) {
                rhs = ensureObjSlot(rhs)
            }
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
        val varTarget = ref.target as? LocalVarRef
        if (varTarget != null) {
            return compileEvalRef(ref)
        }
        val objOp = when (ref.op) {
            BinOp.PLUS -> Opcode.ADD_OBJ
            BinOp.MINUS -> Opcode.SUB_OBJ
            BinOp.STAR -> Opcode.MUL_OBJ
            BinOp.SLASH -> Opcode.DIV_OBJ
            BinOp.PERCENT -> Opcode.MOD_OBJ
            else -> null
        } ?: return compileEvalRef(ref)
        val fieldTarget = ref.target as? FieldRef
        if (fieldTarget != null) {
            val receiver = compileRefWithFallback(fieldTarget.target, null, Pos.builtIn) ?: return null
            val nameId = builder.addConst(BytecodeConst.StringVal(fieldTarget.name))
            if (nameId > 0xFFFF) return compileEvalRef(ref)
            val current = allocSlot()
            val result = allocSlot()
            val rhs = compileRef(ref.value) ?: return compileEvalRef(ref)
            if (!fieldTarget.isOptional) {
                builder.emit(Opcode.GET_FIELD, receiver.slot, nameId, current)
                builder.emit(objOp, current, rhs.slot, result)
                builder.emit(Opcode.SET_FIELD, receiver.slot, nameId, result)
                updateSlotType(result, SlotType.OBJ)
                return CompiledValue(result, SlotType.OBJ)
            }
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            builder.emit(Opcode.GET_FIELD, receiver.slot, nameId, current)
            builder.emit(objOp, current, rhs.slot, result)
            builder.emit(Opcode.SET_FIELD, receiver.slot, nameId, result)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, current)
            builder.emit(objOp, current, rhs.slot, result)
            builder.mark(endLabel)
            updateSlotType(result, SlotType.OBJ)
            return CompiledValue(result, SlotType.OBJ)
        }
        val implicitTarget = ref.target as? ImplicitThisMemberRef
        if (implicitTarget != null) {
            val nameId = builder.addConst(BytecodeConst.StringVal(implicitTarget.name))
            if (nameId > 0xFFFF) return compileEvalRef(ref)
            val current = allocSlot()
            val result = allocSlot()
            val rhs = compileRef(ref.value) ?: return compileEvalRef(ref)
            builder.emit(Opcode.GET_THIS_MEMBER, nameId, current)
            builder.emit(objOp, current, rhs.slot, result)
            builder.emit(Opcode.SET_THIS_MEMBER, nameId, result)
            updateSlotType(result, SlotType.OBJ)
            return CompiledValue(result, SlotType.OBJ)
        }
        val indexTarget = ref.target as? IndexRef
        if (indexTarget != null) {
            val receiver = compileRefWithFallback(indexTarget.targetRef, null, Pos.builtIn) ?: return null
            val current = allocSlot()
            val result = allocSlot()
            val rhs = compileRef(ref.value) ?: return compileEvalRef(ref)
            if (!indexTarget.optionalRef) {
                val index = compileRefWithFallback(indexTarget.indexRef, null, Pos.builtIn) ?: return null
                builder.emit(Opcode.GET_INDEX, receiver.slot, index.slot, current)
                builder.emit(objOp, current, rhs.slot, result)
                builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, result)
                updateSlotType(result, SlotType.OBJ)
                return CompiledValue(result, SlotType.OBJ)
            }
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            val index = compileRefWithFallback(indexTarget.indexRef, null, Pos.builtIn) ?: return null
            builder.emit(Opcode.GET_INDEX, receiver.slot, index.slot, current)
            builder.emit(objOp, current, rhs.slot, result)
            builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, result)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, current)
            builder.emit(objOp, current, rhs.slot, result)
            builder.mark(endLabel)
            updateSlotType(result, SlotType.OBJ)
            return CompiledValue(result, SlotType.OBJ)
        }
        return compileEvalRef(ref)
    }

    private fun compileAssignIfNull(ref: AssignIfNullRef): CompiledValue? {
        val target = ref.target
        val currentValue = compileRefWithFallback(target, null, Pos.builtIn) ?: return null
        val currentObj = ensureObjSlot(currentValue)
        val resultSlot = allocSlot()
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, currentObj.slot, nullSlot, cmpSlot)
        val assignLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(assignLabel))
        )
        builder.emit(Opcode.MOVE_OBJ, currentObj.slot, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(assignLabel)

        val newValue = compileRefWithFallback(ref.value, null, Pos.builtIn) ?: return null
        when (target) {
            is LocalSlotRef -> {
                if (!allowLocalSlots || !target.isMutable || target.isDelegated) return null
                val slot = resolveSlot(target) ?: return null
                if (slot < scopeSlotCount) {
                    val addrSlot = ensureScopeAddr(slot)
                    val storeType = if (newValue.type == SlotType.UNKNOWN) SlotType.OBJ else newValue.type
                    emitStoreToAddr(newValue.slot, addrSlot, storeType)
                } else {
                    when (newValue.type) {
                        SlotType.INT -> builder.emit(Opcode.MOVE_INT, newValue.slot, slot)
                        SlotType.REAL -> builder.emit(Opcode.MOVE_REAL, newValue.slot, slot)
                        SlotType.BOOL -> builder.emit(Opcode.MOVE_BOOL, newValue.slot, slot)
                        else -> builder.emit(Opcode.MOVE_OBJ, newValue.slot, slot)
                    }
                }
                updateSlotType(slot, newValue.type)
            }
            is FieldRef -> {
                val receiver = compileRefWithFallback(target.target, null, Pos.builtIn) ?: return null
                val nameId = builder.addConst(BytecodeConst.StringVal(target.name))
                if (nameId > 0xFFFF) return null
                if (!target.isOptional) {
                    builder.emit(Opcode.SET_FIELD, receiver.slot, nameId, newValue.slot)
                } else {
                    val recvNull = allocSlot()
                    builder.emit(Opcode.CONST_NULL, recvNull)
                    val recvCmp = allocSlot()
                    builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, recvNull, recvCmp)
                    val skipLabel = builder.label()
                    builder.emit(
                        Opcode.JMP_IF_TRUE,
                        listOf(CmdBuilder.Operand.IntVal(recvCmp), CmdBuilder.Operand.LabelRef(skipLabel))
                    )
                    builder.emit(Opcode.SET_FIELD, receiver.slot, nameId, newValue.slot)
                    builder.mark(skipLabel)
                }
            }
            is IndexRef -> {
                val receiver = compileRefWithFallback(target.targetRef, null, Pos.builtIn) ?: return null
                if (!target.optionalRef) {
                    val index = compileRefWithFallback(target.indexRef, null, Pos.builtIn) ?: return null
                    builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, newValue.slot)
                } else {
                    val recvNull = allocSlot()
                    builder.emit(Opcode.CONST_NULL, recvNull)
                    val recvCmp = allocSlot()
                    builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, recvNull, recvCmp)
                    val skipLabel = builder.label()
                    builder.emit(
                        Opcode.JMP_IF_TRUE,
                        listOf(CmdBuilder.Operand.IntVal(recvCmp), CmdBuilder.Operand.LabelRef(skipLabel))
                    )
                    val index = compileRefWithFallback(target.indexRef, null, Pos.builtIn) ?: return null
                    builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, newValue.slot)
                    builder.mark(skipLabel)
                }
            }
            else -> return null
        }
        val newObj = ensureObjSlot(newValue)
        builder.emit(Opcode.MOVE_OBJ, newObj.slot, resultSlot)
        builder.mark(endLabel)
        updateSlotType(resultSlot, SlotType.OBJ)
        return CompiledValue(resultSlot, SlotType.OBJ)
    }

    private fun compileFieldRef(ref: FieldRef): CompiledValue? {
        val receiver = compileRefWithFallback(ref.target, null, Pos.builtIn) ?: return null
        val dst = allocSlot()
        val nameId = builder.addConst(BytecodeConst.StringVal(ref.name))
        if (nameId > 0xFFFF) return null
        if (!ref.isOptional) {
            builder.emit(Opcode.GET_FIELD, receiver.slot, nameId, dst)
        } else {
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            builder.emit(Opcode.GET_FIELD, receiver.slot, nameId, dst)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, dst)
            builder.mark(endLabel)
        }
        updateSlotType(dst, SlotType.OBJ)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileIndexRef(ref: IndexRef): CompiledValue? {
        val receiver = compileRefWithFallback(ref.targetRef, null, Pos.builtIn) ?: return null
        val dst = allocSlot()
        if (!ref.optionalRef) {
            val index = compileRefWithFallback(ref.indexRef, null, Pos.builtIn) ?: return null
            builder.emit(Opcode.GET_INDEX, receiver.slot, index.slot, dst)
        } else {
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            val index = compileRefWithFallback(ref.indexRef, null, Pos.builtIn) ?: return null
            builder.emit(Opcode.GET_INDEX, receiver.slot, index.slot, dst)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, dst)
            builder.mark(endLabel)
        }
        updateSlotType(dst, SlotType.OBJ)
        return CompiledValue(dst, SlotType.OBJ)
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
        val fieldTarget = ref.target as? FieldRef
        if (fieldTarget != null) {
            val receiver = compileRefWithFallback(fieldTarget.target, null, Pos.builtIn) ?: return null
            val methodId = builder.addConst(BytecodeConst.StringVal(fieldTarget.name))
            if (methodId > 0xFFFF) return null
            val dst = allocSlot()
            if (!fieldTarget.isOptional && !ref.isOptionalInvoke) {
                val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
                val encodedCount = encodeCallArgCount(args) ?: return null
                builder.emit(Opcode.CALL_VIRTUAL, receiver.slot, methodId, args.base, encodedCount, dst)
                return CompiledValue(dst, SlotType.OBJ)
            }
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            builder.emit(Opcode.CALL_VIRTUAL, receiver.slot, methodId, args.base, encodedCount, dst)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, dst)
            builder.mark(endLabel)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val callee = compileRefWithFallback(ref.target, null, Pos.builtIn) ?: return null
        val dst = allocSlot()
        if (!ref.isOptionalInvoke) {
            val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            builder.emit(Opcode.CALL_SLOT, callee.slot, args.base, encodedCount, dst)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, callee.slot, nullSlot, cmpSlot)
        val nullLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
        )
        val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
        val encodedCount = encodeCallArgCount(args) ?: return null
        builder.emit(Opcode.CALL_SLOT, callee.slot, args.base, encodedCount, dst)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(nullLabel)
        builder.emit(Opcode.CONST_NULL, dst)
        builder.mark(endLabel)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileMethodCall(ref: MethodCallRef): CompiledValue? {
        val receiver = compileRefWithFallback(ref.receiver, null, Pos.builtIn) ?: return null
        val methodId = builder.addConst(BytecodeConst.StringVal(ref.name))
        if (methodId > 0xFFFF) return null
        val dst = allocSlot()
        if (!ref.isOptional) {
            val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            builder.emit(Opcode.CALL_VIRTUAL, receiver.slot, methodId, args.base, encodedCount, dst)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
        val nullLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
        )
        val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
        val encodedCount = encodeCallArgCount(args) ?: return null
        builder.emit(Opcode.CALL_VIRTUAL, receiver.slot, methodId, args.base, encodedCount, dst)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(nullLabel)
        builder.emit(Opcode.CONST_NULL, dst)
        builder.mark(endLabel)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileThisMethodSlotCall(ref: ThisMethodSlotCallRef): CompiledValue? {
        val receiver = compileNameLookup("this")
        val methodId = builder.addConst(BytecodeConst.StringVal(ref.methodName()))
        if (methodId > 0xFFFF) return null
        val dst = allocSlot()
        if (!ref.optionalInvoke()) {
            val args = compileCallArgs(ref.arguments(), ref.hasTailBlock()) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            builder.emit(Opcode.CALL_VIRTUAL, receiver.slot, methodId, args.base, encodedCount, dst)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
        val nullLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
        )
        val args = compileCallArgs(ref.arguments(), ref.hasTailBlock()) ?: return null
        val encodedCount = encodeCallArgCount(args) ?: return null
        builder.emit(Opcode.CALL_VIRTUAL, receiver.slot, methodId, args.base, encodedCount, dst)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(nullLabel)
        builder.emit(Opcode.CONST_NULL, dst)
        builder.mark(endLabel)
        return CompiledValue(dst, SlotType.OBJ)
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
                throw BytecodeFallbackException(
                    "Bytecode fallback: unsupported argument expression",
                    stmt.pos
                )
            }
        }
    }

    private fun encodeCallArgCount(args: CallArgs): Int? {
        val planId = args.planId ?: return args.count
        if (planId > 0x7FFF) return null
        return 0x8000 or planId
    }

    private fun compileIf(name: String, stmt: IfStatement): CmdFunction? {
        val conditionTarget = if (stmt.condition is BytecodeStatement) {
            stmt.condition.original
        } else {
            stmt.condition
        }
        val conditionStmt = conditionTarget as? ExpressionStatement ?: return null
        val condValue = compileRefWithFallback(conditionStmt.ref, SlotType.BOOL, stmt.pos) ?: return null
        if (condValue.type != SlotType.BOOL) return null

        val resultSlot = allocSlot()
        val elseLabel = builder.label()
        val endLabel = builder.label()

        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(condValue.slot), CmdBuilder.Operand.LabelRef(elseLabel))
        )
        val thenValue = compileStatementValueOrFallback(stmt.ifBody) ?: return null
        emitMove(thenValue, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))

        builder.mark(elseLabel)
        if (stmt.elseBody != null) {
            val elseValue = compileStatementValueOrFallback(stmt.elseBody) ?: return null
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
            returnLabels = returnLabels,
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
            returnLabels = returnLabels,
            scopeSlotDepths,
            scopeSlotIndices,
            scopeSlotNames,
            localSlotNames,
            localSlotMutables,
            localSlotDepths
        )
    }

    private fun compileWhile(name: String, stmt: net.sergeych.lyng.WhileStatement): CmdFunction? {
        if (!allowLocalSlots) return null
        val resultSlot = emitWhile(stmt, true) ?: return null
        builder.emit(Opcode.RET, resultSlot)
        val localCount = maxOf(nextSlot, resultSlot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotDepths,
            scopeSlotIndices,
            scopeSlotNames,
            localSlotNames,
            localSlotMutables,
            localSlotDepths
        )
    }

    private fun compileDoWhile(name: String, stmt: net.sergeych.lyng.DoWhileStatement): CmdFunction? {
        if (!allowLocalSlots) return null
        val resultSlot = emitDoWhile(stmt, true) ?: return null
        builder.emit(Opcode.RET, resultSlot)
        val localCount = maxOf(nextSlot, resultSlot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
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
            returnLabels = returnLabels,
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
            returnLabels = returnLabels,
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

    private fun emitFallbackStatement(stmt: Statement): CompiledValue {
        throw BytecodeFallbackException(
            "Bytecode fallback: unsupported statement",
            stmt.pos
        )
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
                is net.sergeych.lyng.WhileStatement -> {
                    if (!allowLocalSlots) emitFallbackStatement(target)
                    else {
                        val resultSlot = emitWhile(target, true) ?: return null
                        updateSlotType(resultSlot, SlotType.OBJ)
                        CompiledValue(resultSlot, SlotType.OBJ)
                    }
                }
                is net.sergeych.lyng.DoWhileStatement -> {
                    if (!allowLocalSlots) emitFallbackStatement(target)
                    else {
                        val resultSlot = emitDoWhile(target, true) ?: return null
                        updateSlotType(resultSlot, SlotType.OBJ)
                        CompiledValue(resultSlot, SlotType.OBJ)
                    }
                }
                is BlockStatement -> emitBlock(target, true)
                is VarDeclStatement -> emitVarDecl(target)
                is net.sergeych.lyng.ExtensionPropertyDeclStatement -> emitExtensionPropertyDecl(target)
                is net.sergeych.lyng.BreakStatement -> compileBreak(target)
                is net.sergeych.lyng.ContinueStatement -> compileContinue(target)
                is net.sergeych.lyng.ReturnStatement -> compileReturn(target)
                is net.sergeych.lyng.ThrowStatement -> compileThrow(target)
                else -> {
                    emitFallbackStatement(target)
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
                is net.sergeych.lyng.WhileStatement -> {
                    if (!allowLocalSlots) emitFallbackStatement(target)
                    else {
                        val resultSlot = emitWhile(target, false) ?: return null
                        CompiledValue(resultSlot, SlotType.OBJ)
                    }
                }
                is net.sergeych.lyng.DoWhileStatement -> {
                    if (!allowLocalSlots) emitFallbackStatement(target)
                    else {
                        val resultSlot = emitDoWhile(target, false) ?: return null
                        CompiledValue(resultSlot, SlotType.OBJ)
                    }
                }
                is BlockStatement -> emitBlock(target, false)
                is VarDeclStatement -> emitVarDecl(target)
                is net.sergeych.lyng.ExtensionPropertyDeclStatement -> emitExtensionPropertyDecl(target)
                is net.sergeych.lyng.BreakStatement -> compileBreak(target)
                is net.sergeych.lyng.ContinueStatement -> compileContinue(target)
                is net.sergeych.lyng.ReturnStatement -> compileReturn(target)
                is net.sergeych.lyng.ThrowStatement -> compileThrow(target)
                else -> {
                    emitFallbackStatement(target)
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
            val value = compileStatementValueOrFallback(statement, wantResult)
                ?: run {
                    val original = (statement as? BytecodeStatement)?.original
                    val name = original?.let { "${statement::class.simpleName}(${it::class.simpleName})" }
                        ?: statement::class.simpleName
                    throw BytecodeFallbackException(
                        "Bytecode fallback: failed to compile block statement ($name)",
                        statement.pos
                    )
                }
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

    private fun emitInlineBlock(stmt: BlockStatement, needResult: Boolean): CompiledValue? {
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
        return if (needResult) {
            lastValue ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                CompiledValue(slot, SlotType.OBJ)
            }
        } else {
            lastValue ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                CompiledValue(slot, SlotType.OBJ)
            }
        }
    }

    private fun compileLoopBody(stmt: Statement, needResult: Boolean): CompiledValue? {
        val target = if (stmt is BytecodeStatement) stmt.original else stmt
        return if (target is BlockStatement) emitInlineBlock(target, needResult)
        else compileStatementValueOrFallback(target, needResult)
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
        val range = stmt.constRange
        var rangeRef = if (range == null) extractRangeRef(stmt.source) else null
        if (range == null && rangeRef == null) {
            rangeRef = extractRangeFromLocal(stmt.source)
        }
        val typedRangeLocal = if (range == null && rangeRef == null) extractTypedRangeLocal(stmt.source) else null
        val loopLocalIndex = localSlotIndexByName[stmt.loopVarName]
        var usedOverride = false
        val loopSlotId = when {
            loopLocalIndex != null -> scopeSlotCount + loopLocalIndex
            else -> {
                val localKey = localSlotInfoMap.entries.firstOrNull { it.value.name == stmt.loopVarName }?.key
                val localIndex = localKey?.let { localSlotIndexByKey[it] }
                when {
                    localIndex != null -> scopeSlotCount + localIndex
                    else -> scopeSlotIndexByName[stmt.loopVarName]
                }
            }
        } ?: run {
            val slot = allocSlot()
            loopSlotOverrides[stmt.loopVarName] = slot
            usedOverride = true
            slot
        }

        try {
        if (range == null && rangeRef == null && typedRangeLocal == null) {
            val sourceValue = compileStatementValueOrFallback(stmt.source) ?: return null
            val sourceObj = ensureObjSlot(sourceValue)
            val typeId = builder.addConst(BytecodeConst.ObjRef(ObjIterable))
            val typeSlot = allocSlot()
            builder.emit(Opcode.CONST_OBJ, typeId, typeSlot)
            builder.emit(Opcode.ASSERT_IS, sourceObj.slot, typeSlot)

            val iterSlot = allocSlot()
            val iteratorId = builder.addConst(BytecodeConst.StringVal("iterator"))
            builder.emit(Opcode.CALL_VIRTUAL, sourceObj.slot, iteratorId, 0, 0, iterSlot)

            val breakFlagSlot = allocSlot()
            val falseId = builder.addConst(BytecodeConst.Bool(false))
            builder.emit(Opcode.CONST_BOOL, falseId, breakFlagSlot)

            val resultSlot = allocSlot()
            val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
            builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)

            val loopLabel = builder.label()
            val continueLabel = builder.label()
            val endLabel = builder.label()
            builder.mark(loopLabel)

            val hasNextSlot = allocSlot()
            val hasNextId = builder.addConst(BytecodeConst.StringVal("hasNext"))
            builder.emit(Opcode.CALL_VIRTUAL, iterSlot, hasNextId, 0, 0, hasNextSlot)
            val condSlot = allocSlot()
            builder.emit(Opcode.OBJ_TO_BOOL, hasNextSlot, condSlot)
            builder.emit(
                Opcode.JMP_IF_FALSE,
                listOf(CmdBuilder.Operand.IntVal(condSlot), CmdBuilder.Operand.LabelRef(endLabel))
            )

            val nextSlot = allocSlot()
            val nextId = builder.addConst(BytecodeConst.StringVal("next"))
            builder.emit(Opcode.CALL_VIRTUAL, iterSlot, nextId, 0, 0, nextSlot)
            val nextObj = ensureObjSlot(CompiledValue(nextSlot, SlotType.UNKNOWN))
            builder.emit(Opcode.MOVE_OBJ, nextObj.slot, loopSlotId)
            updateSlotType(loopSlotId, SlotType.OBJ)
            updateSlotTypeByName(stmt.loopVarName, SlotType.OBJ)

            loopStack.addLast(
                LoopContext(stmt.label, endLabel, continueLabel, breakFlagSlot, if (wantResult) resultSlot else null)
            )
            val bodyValue = compileLoopBody(stmt.body, wantResult) ?: return null
            loopStack.removeLast()
            if (wantResult) {
                val bodyObj = ensureObjSlot(bodyValue)
                builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
            }
            builder.mark(continueLabel)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(loopLabel)))

            builder.mark(endLabel)
            if (stmt.elseStatement != null) {
                val afterElse = builder.label()
                builder.emit(
                    Opcode.JMP_IF_TRUE,
                    listOf(CmdBuilder.Operand.IntVal(breakFlagSlot), CmdBuilder.Operand.LabelRef(afterElse))
                )
                val elseValue = compileStatementValueOrFallback(stmt.elseStatement, wantResult) ?: return null
                if (wantResult) {
                    val elseObj = ensureObjSlot(elseValue)
                    builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
                }
                builder.mark(afterElse)
            }
            return resultSlot
        }

        val iSlot = allocSlot()
        val endSlot = allocSlot()
        if (range != null) {
            val startId = builder.addConst(BytecodeConst.IntVal(range.start))
            val endId = builder.addConst(BytecodeConst.IntVal(range.endExclusive))
            builder.emit(Opcode.CONST_INT, startId, iSlot)
            builder.emit(Opcode.CONST_INT, endId, endSlot)
        } else {
            if (rangeRef != null) {
                val left = rangeRef.left ?: return null
                val right = rangeRef.right ?: return null
                val startValue = compileRef(left) ?: return null
                val endValue = compileRef(right) ?: return null
                if (startValue.type != SlotType.INT || endValue.type != SlotType.INT) return null
                emitMove(startValue, iSlot)
                emitMove(endValue, endSlot)
                if (rangeRef.isEndInclusive) {
                    builder.emit(Opcode.INC_INT, endSlot)
                }
            } else {
                val rangeLocal = typedRangeLocal ?: return null
                val rangeValue = compileRef(rangeLocal) ?: return null
                val rangeObj = ensureObjSlot(rangeValue)
                val okSlot = allocSlot()
                builder.emit(Opcode.RANGE_INT_BOUNDS, rangeObj.slot, iSlot, endSlot, okSlot)
                val badRangeLabel = builder.label()
                builder.emit(
                    Opcode.JMP_IF_FALSE,
                    listOf(CmdBuilder.Operand.IntVal(okSlot), CmdBuilder.Operand.LabelRef(badRangeLabel))
                )
                val breakFlagSlot = allocSlot()
                val falseId = builder.addConst(BytecodeConst.Bool(false))
                builder.emit(Opcode.CONST_BOOL, falseId, breakFlagSlot)

                val resultSlot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)

                val loopLabel = builder.label()
                val continueLabel = builder.label()
                val endLabel = builder.label()
                val doneLabel = builder.label()
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
                loopStack.addLast(
                    LoopContext(stmt.label, endLabel, continueLabel, breakFlagSlot, if (wantResult) resultSlot else null)
                )
                val bodyValue = compileLoopBody(stmt.body, wantResult) ?: return null
                loopStack.removeLast()
                if (wantResult) {
                    val bodyObj = ensureObjSlot(bodyValue)
                    builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
                }
                builder.mark(continueLabel)
                builder.emit(Opcode.INC_INT, iSlot)
                builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(loopLabel)))

                builder.mark(endLabel)
                if (stmt.elseStatement != null) {
                    val afterElse = builder.label()
                    builder.emit(
                        Opcode.JMP_IF_TRUE,
                        listOf(CmdBuilder.Operand.IntVal(breakFlagSlot), CmdBuilder.Operand.LabelRef(afterElse))
                    )
                    val elseValue = compileStatementValueOrFallback(stmt.elseStatement, wantResult) ?: return null
                    if (wantResult) {
                        val elseObj = ensureObjSlot(elseValue)
                        builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
                    }
                    builder.mark(afterElse)
                }
                builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(doneLabel)))
                builder.mark(badRangeLabel)
                val msgId = builder.addConst(BytecodeConst.StringVal("expected Int range"))
                builder.emit(Opcode.CONST_OBJ, msgId, resultSlot)
                val posId = builder.addConst(BytecodeConst.PosVal(stmt.pos))
                builder.emit(Opcode.THROW, posId, resultSlot)
                builder.mark(doneLabel)
                return resultSlot
            }
        }

        val breakFlagSlot = allocSlot()
        val falseId = builder.addConst(BytecodeConst.Bool(false))
        builder.emit(Opcode.CONST_BOOL, falseId, breakFlagSlot)

        val resultSlot = allocSlot()
        val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
        builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)

        val loopLabel = builder.label()
        val continueLabel = builder.label()
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
        loopStack.addLast(
            LoopContext(stmt.label, endLabel, continueLabel, breakFlagSlot, if (wantResult) resultSlot else null)
        )
        val bodyValue = compileLoopBody(stmt.body, wantResult) ?: return null
        loopStack.removeLast()
        if (wantResult) {
            val bodyObj = ensureObjSlot(bodyValue)
            builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
        }
        builder.mark(continueLabel)
        builder.emit(Opcode.INC_INT, iSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(loopLabel)))

        builder.mark(endLabel)
        if (stmt.elseStatement != null) {
            val afterElse = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(breakFlagSlot), CmdBuilder.Operand.LabelRef(afterElse))
            )
            val elseValue = compileStatementValueOrFallback(stmt.elseStatement, wantResult) ?: return null
            if (wantResult) {
                val elseObj = ensureObjSlot(elseValue)
                builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
            }
            builder.mark(afterElse)
        }
        return resultSlot
        } finally {
            if (usedOverride) {
                loopSlotOverrides.remove(stmt.loopVarName)
            }
        }
    }

    private fun emitWhile(stmt: net.sergeych.lyng.WhileStatement, wantResult: Boolean): Int? {
        val breakFlagSlot = allocSlot()
        val falseId = builder.addConst(BytecodeConst.Bool(false))
        builder.emit(Opcode.CONST_BOOL, falseId, breakFlagSlot)

        val resultSlot = allocSlot()
        val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
        builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)

        val loopLabel = builder.label()
        val continueLabel = builder.label()
        val endLabel = builder.label()
        builder.mark(loopLabel)
        val condition = compileCondition(stmt.condition, stmt.pos) ?: return null
        if (condition.type != SlotType.BOOL) return null
        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(condition.slot), CmdBuilder.Operand.LabelRef(endLabel))
        )
        loopStack.addLast(
            LoopContext(stmt.label, endLabel, continueLabel, breakFlagSlot, if (wantResult) resultSlot else null)
        )
        val bodyValue = compileLoopBody(stmt.body, wantResult) ?: return null
        loopStack.removeLast()
        if (wantResult) {
            val bodyObj = ensureObjSlot(bodyValue)
            builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
        }
        builder.mark(continueLabel)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(loopLabel)))

        builder.mark(endLabel)
        if (stmt.elseStatement != null) {
            val afterElse = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(breakFlagSlot), CmdBuilder.Operand.LabelRef(afterElse))
            )
            val elseValue = compileStatementValueOrFallback(stmt.elseStatement, wantResult) ?: return null
            if (wantResult) {
                val elseObj = ensureObjSlot(elseValue)
                builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
            }
            builder.mark(afterElse)
        }
        return resultSlot
    }

    private fun emitDoWhile(stmt: net.sergeych.lyng.DoWhileStatement, wantResult: Boolean): Int? {
        val breakFlagSlot = allocSlot()
        val falseId = builder.addConst(BytecodeConst.Bool(false))
        builder.emit(Opcode.CONST_BOOL, falseId, breakFlagSlot)

        val resultSlot = allocSlot()
        val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
        builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)

        val loopLabel = builder.label()
        val continueLabel = builder.label()
        val endLabel = builder.label()
        builder.mark(loopLabel)
        loopStack.addLast(
            LoopContext(stmt.label, endLabel, continueLabel, breakFlagSlot, if (wantResult) resultSlot else null)
        )
        val bodyValue = compileStatementValueOrFallback(stmt.body, wantResult) ?: return null
        loopStack.removeLast()
        if (wantResult) {
            val bodyObj = ensureObjSlot(bodyValue)
            builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
        }
        builder.mark(continueLabel)
        val condition = compileCondition(stmt.condition, stmt.pos) ?: return null
        if (condition.type != SlotType.BOOL) return null
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(condition.slot), CmdBuilder.Operand.LabelRef(loopLabel))
        )

        builder.mark(endLabel)
        if (stmt.elseStatement != null) {
            val afterElse = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(breakFlagSlot), CmdBuilder.Operand.LabelRef(afterElse))
            )
            val elseValue = compileStatementValueOrFallback(stmt.elseStatement, wantResult) ?: return null
            if (wantResult) {
                val elseObj = ensureObjSlot(elseValue)
                builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
            }
            builder.mark(afterElse)
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
        val target = if (stmt is BytecodeStatement) stmt.original else stmt
        return when (target) {
            is ExpressionStatement -> compileRefWithFallback(target.ref, SlotType.BOOL, target.pos)
            else -> {
                throw BytecodeFallbackException(
                    "Bytecode fallback: unsupported condition",
                    pos
                )
            }
        }
    }

    private fun findLoopContext(label: String?): LoopContext? {
        if (loopStack.isEmpty()) return null
        if (label == null) return loopStack.last()
        for (ctx in loopStack.reversed()) {
            if (ctx.label == label) return ctx
        }
        return null
    }

    private fun compileBreak(stmt: net.sergeych.lyng.BreakStatement): CompiledValue? {
        val ctx = findLoopContext(stmt.label) ?: return null
        val value = stmt.resultExpr?.let { compileStatementValueOrFallback(it) }
        if (ctx.resultSlot != null) {
            val objValue = value?.let { ensureObjSlot(it) } ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                updateSlotType(slot, SlotType.OBJ)
                CompiledValue(slot, SlotType.OBJ)
            }
            builder.emit(Opcode.MOVE_OBJ, objValue.slot, ctx.resultSlot)
        } else if (value != null) {
            ensureObjSlot(value)
        }
        val trueId = builder.addConst(BytecodeConst.Bool(true))
        builder.emit(Opcode.CONST_BOOL, trueId, ctx.breakFlagSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(ctx.breakLabel)))
        return CompiledValue(ctx.breakFlagSlot, SlotType.BOOL)
    }

    private fun compileContinue(stmt: net.sergeych.lyng.ContinueStatement): CompiledValue? {
        val ctx = findLoopContext(stmt.label) ?: return null
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(ctx.continueLabel)))
        return CompiledValue(ctx.breakFlagSlot, SlotType.BOOL)
    }

    private fun compileReturn(stmt: net.sergeych.lyng.ReturnStatement): CompiledValue? {
        val value = stmt.resultExpr?.let { compileStatementValueOrFallback(it) } ?: run {
            val slot = allocSlot()
            val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
            builder.emit(Opcode.CONST_OBJ, voidId, slot)
            updateSlotType(slot, SlotType.OBJ)
            CompiledValue(slot, SlotType.OBJ)
        }
        val label = stmt.label
        if (label == null || returnLabels.contains(label)) {
            builder.emit(Opcode.RET, value.slot)
        } else {
            val labelId = builder.addConst(BytecodeConst.StringVal(label))
            builder.emit(Opcode.RET_LABEL, labelId, value.slot)
        }
        return value
    }

    private fun compileThrow(stmt: net.sergeych.lyng.ThrowStatement): CompiledValue? {
        val value = compileStatementValueOrFallback(stmt.throwExpr) ?: return null
        val objValue = ensureObjSlot(value)
        val posId = builder.addConst(BytecodeConst.PosVal(stmt.pos))
        builder.emit(Opcode.THROW, posId, objValue.slot)
        return objValue
    }

    private fun emitExtensionPropertyDecl(
        stmt: net.sergeych.lyng.ExtensionPropertyDeclStatement
    ): CompiledValue {
        val constId = builder.addConst(
            BytecodeConst.ExtensionPropertyDecl(
                stmt.extTypeName,
                stmt.property,
                stmt.visibility,
                stmt.setterVisibility
            )
        )
        val slot = allocSlot()
        builder.emit(Opcode.DECL_EXT_PROPERTY, constId, slot)
        updateSlotType(slot, SlotType.OBJ)
        return CompiledValue(slot, SlotType.OBJ)
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
            SlotType.OBJ -> builder.emit(Opcode.MOVE_OBJ, srcSlot, dstSlot)
            else -> builder.emit(Opcode.BOX_OBJ, srcSlot, dstSlot)
        }
    }

    private fun compileRefWithFallback(ref: ObjRef, forceType: SlotType?, pos: Pos): CompiledValue? {
        var compiled = compileRef(ref)
        if (compiled != null) {
            if (forceType == null) return compiled
            if (compiled.type == forceType) return compiled
            if (forceType == SlotType.BOOL) {
                val converted = when (compiled.type) {
                    SlotType.INT -> {
                        val dst = allocSlot()
                        builder.emit(Opcode.INT_TO_BOOL, compiled.slot, dst)
                        updateSlotType(dst, SlotType.BOOL)
                        CompiledValue(dst, SlotType.BOOL)
                    }
                    SlotType.OBJ -> {
                        val dst = allocSlot()
                        builder.emit(Opcode.OBJ_TO_BOOL, compiled.slot, dst)
                        updateSlotType(dst, SlotType.BOOL)
                        CompiledValue(dst, SlotType.BOOL)
                    }
                    else -> null
                }
                if (converted != null) return converted
            }
            if (compiled.type == SlotType.UNKNOWN) {
                compiled = null
            }
        }
        val refInfo = when (ref) {
            is LocalVarRef -> "LocalVarRef(${ref.name})"
            is LocalSlotRef -> "LocalSlotRef(${ref.name})"
            is FieldRef -> "FieldRef(${ref.name})"
            else -> ref::class.simpleName ?: "UnknownRef"
        }
        val extra = if (ref is LocalVarRef) {
            val names = scopeSlotNameMap.values.joinToString(prefix = "[", postfix = "]")
            " scopeSlots=$names"
        } else {
            ""
        }
        throw BytecodeFallbackException(
            "Bytecode fallback: unsupported expression ($refInfo)$extra",
            pos
        )
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
        loopSlotOverrides[ref.name]?.let { return it }
        val localKey = ScopeSlotKey(refScopeDepth(ref), refSlot(ref))
        val localIndex = localSlotIndexByKey[localKey]
        if (localIndex != null) return scopeSlotCount + localIndex
        val nameIndex = localSlotIndexByName[ref.name]
        if (nameIndex != null) return scopeSlotCount + nameIndex
        val scopeKey = ScopeSlotKey(effectiveScopeDepth(ref), refSlot(ref))
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
        scopeSlotNameMap.clear()
        localSlotInfoMap.clear()
        localSlotIndexByKey.clear()
        localSlotIndexByName.clear()
        loopSlotOverrides.clear()
        scopeSlotIndexByName.clear()
        pendingScopeNameRefs.clear()
        localSlotNames = emptyArray()
        localSlotMutables = BooleanArray(0)
        localSlotDepths = IntArray(0)
        declaredLocalKeys.clear()
        localRangeRefs.clear()
        intLoopVarNames.clear()
        addrSlotByScopeSlot.clear()
        loopStack.clear()
        virtualScopeDepths.clear()
        if (allowLocalSlots) {
            collectLoopVarNames(stmt)
        }
        collectVirtualScopeDepths(stmt, 0)
        collectScopeSlots(stmt)
        if (allowLocalSlots) {
            collectLoopSlotPlans(stmt, 0)
        }
        if (pendingScopeNameRefs.isNotEmpty()) {
            val existingNames = HashSet<String>(scopeSlotNameMap.values)
            var maxSlotIndex = scopeSlotMap.keys.maxOfOrNull { it.slot } ?: -1
            for (name in pendingScopeNameRefs) {
                if (!existingNames.add(name)) continue
                maxSlotIndex += 1
                val key = ScopeSlotKey(0, maxSlotIndex)
                scopeSlotMap[key] = scopeSlotMap.size
                scopeSlotNameMap[key] = name
            }
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
                depths[index] = effectiveLocalDepth(info.depth)
                index += 1
            }
            localSlotNames = names.toTypedArray()
            localSlotMutables = mutables
            localSlotDepths = depths
        }
        if (scopeSlotCount > 0) {
            for ((key, index) in scopeSlotMap) {
                val name = scopeSlotNameMap[key] ?: continue
                if (!scopeSlotIndexByName.containsKey(name)) {
                    scopeSlotIndexByName[name] = index
                }
            }
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
                    val key = ScopeSlotKey(slotDepth, slotIndex)
                    declaredLocalKeys.add(key)
                    if (!localSlotInfoMap.containsKey(key)) {
                        localSlotInfoMap[key] = LocalSlotInfo(stmt.name, stmt.isMutable, slotDepth)
                    }
                    if (!stmt.isMutable) {
                        extractDeclaredRange(stmt.initializer)?.let { range ->
                            localRangeRefs[key] = range
                        }
                    }
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
            is net.sergeych.lyng.WhileStatement -> {
                collectScopeSlots(stmt.condition)
                collectScopeSlots(stmt.body)
                stmt.elseStatement?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.DoWhileStatement -> {
                collectScopeSlots(stmt.body)
                collectScopeSlots(stmt.condition)
                stmt.elseStatement?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.BreakStatement -> {
                stmt.resultExpr?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.ReturnStatement -> {
                stmt.resultExpr?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.ThrowStatement -> {
                collectScopeSlots(stmt.throwExpr)
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
            is net.sergeych.lyng.WhileStatement -> {
                collectLoopSlotPlans(stmt.condition, scopeDepth)
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
            is net.sergeych.lyng.DoWhileStatement -> {
                val loopDepth = scopeDepth + 1
                for ((name, slotIndex) in stmt.loopSlotPlan) {
                    val key = ScopeSlotKey(loopDepth, slotIndex)
                    if (!localSlotInfoMap.containsKey(key)) {
                        localSlotInfoMap[key] = LocalSlotInfo(name, isMutable = true, depth = loopDepth)
                    }
                }
                collectLoopSlotPlans(stmt.body, loopDepth)
                collectLoopSlotPlans(stmt.condition, loopDepth)
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
            is net.sergeych.lyng.BreakStatement -> {
                stmt.resultExpr?.let { collectLoopSlotPlans(it, scopeDepth) }
            }
            is net.sergeych.lyng.ReturnStatement -> {
                stmt.resultExpr?.let { collectLoopSlotPlans(it, scopeDepth) }
            }
            is net.sergeych.lyng.ThrowStatement -> {
                collectLoopSlotPlans(stmt.throwExpr, scopeDepth)
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
            is net.sergeych.lyng.WhileStatement -> {
                collectLoopVarNames(stmt.condition)
                collectLoopVarNames(stmt.body)
                stmt.elseStatement?.let { collectLoopVarNames(it) }
            }
            is net.sergeych.lyng.DoWhileStatement -> {
                collectLoopVarNames(stmt.body)
                collectLoopVarNames(stmt.condition)
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
            is net.sergeych.lyng.BreakStatement -> {
                stmt.resultExpr?.let { collectLoopVarNames(it) }
            }
            is net.sergeych.lyng.ReturnStatement -> {
                stmt.resultExpr?.let { collectLoopVarNames(it) }
            }
            is net.sergeych.lyng.ThrowStatement -> {
                collectLoopVarNames(stmt.throwExpr)
            }
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
            is FieldRef -> collectLoopVarNamesRef(ref.target)
            is IndexRef -> {
                collectLoopVarNamesRef(ref.targetRef)
                collectLoopVarNamesRef(ref.indexRef)
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
                val key = ScopeSlotKey(effectiveScopeDepth(ref), refSlot(ref))
                if (!scopeSlotMap.containsKey(key)) {
                    scopeSlotMap[key] = scopeSlotMap.size
                }
                if (!scopeSlotNameMap.containsKey(key)) {
                    scopeSlotNameMap[key] = ref.name
                }
            }
            is LocalVarRef -> {}
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
                        val key = ScopeSlotKey(effectiveScopeDepth(target), refSlot(target))
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
            is AssignIfNullRef -> {
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
            is FieldRef -> collectScopeSlotsRef(ref.target)
            is IndexRef -> {
                collectScopeSlotsRef(ref.targetRef)
                collectScopeSlotsRef(ref.indexRef)
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

    private fun collectVirtualScopeDepths(stmt: Statement, scopeDepth: Int) {
        if (stmt is BytecodeStatement) {
            collectVirtualScopeDepths(stmt.original, scopeDepth)
            return
        }
        when (stmt) {
            is net.sergeych.lyng.ForInStatement -> {
                collectVirtualScopeDepths(stmt.source, scopeDepth)
                val loopDepth = scopeDepth + 1
                virtualScopeDepths.add(loopDepth)
                val bodyTarget = if (stmt.body is BytecodeStatement) stmt.body.original else stmt.body
                if (bodyTarget is BlockStatement) {
                    // Loop bodies are inlined in bytecode, so their block scope is virtual.
                    virtualScopeDepths.add(loopDepth + 1)
                }
                collectVirtualScopeDepths(stmt.body, loopDepth)
                stmt.elseStatement?.let { collectVirtualScopeDepths(it, loopDepth) }
            }
            is net.sergeych.lyng.WhileStatement -> {
                collectVirtualScopeDepths(stmt.condition, scopeDepth)
                val loopDepth = scopeDepth + 1
                virtualScopeDepths.add(loopDepth)
                collectVirtualScopeDepths(stmt.body, loopDepth)
                stmt.elseStatement?.let { collectVirtualScopeDepths(it, loopDepth) }
            }
            is net.sergeych.lyng.DoWhileStatement -> {
                val loopDepth = scopeDepth + 1
                virtualScopeDepths.add(loopDepth)
                collectVirtualScopeDepths(stmt.body, loopDepth)
                collectVirtualScopeDepths(stmt.condition, loopDepth)
                stmt.elseStatement?.let { collectVirtualScopeDepths(it, loopDepth) }
            }
            is BlockStatement -> {
                val nextDepth = scopeDepth + 1
                for (child in stmt.statements()) {
                    collectVirtualScopeDepths(child, nextDepth)
                }
            }
            is IfStatement -> {
                collectVirtualScopeDepths(stmt.condition, scopeDepth)
                collectVirtualScopeDepths(stmt.ifBody, scopeDepth)
                stmt.elseBody?.let { collectVirtualScopeDepths(it, scopeDepth) }
            }
            is VarDeclStatement -> {
                stmt.initializer?.let { collectVirtualScopeDepths(it, scopeDepth) }
            }
            is ExpressionStatement -> {
                // no-op
            }
            is net.sergeych.lyng.BreakStatement -> {
                stmt.resultExpr?.let { collectVirtualScopeDepths(it, scopeDepth) }
            }
            is net.sergeych.lyng.ReturnStatement -> {
                stmt.resultExpr?.let { collectVirtualScopeDepths(it, scopeDepth) }
            }
            is net.sergeych.lyng.ThrowStatement -> {
                collectVirtualScopeDepths(stmt.throwExpr, scopeDepth)
            }
            else -> {}
        }
    }

    private fun effectiveScopeDepth(ref: LocalSlotRef): Int {
        val baseDepth = refDepth(ref)
        if (baseDepth == 0 || virtualScopeDepths.isEmpty()) return baseDepth
        val targetDepth = refScopeDepth(ref)
        val currentDepth = targetDepth + baseDepth
        var virtualCount = 0
        for (depth in virtualScopeDepths) {
            if (depth > targetDepth && depth <= currentDepth) {
                virtualCount += 1
            }
        }
        return baseDepth - virtualCount
    }

    private fun extractRangeRef(source: Statement): RangeRef? {
        val target = if (source is BytecodeStatement) source.original else source
        val expr = target as? ExpressionStatement ?: return null
        return expr.ref as? RangeRef
    }

    private fun extractDeclaredRange(stmt: Statement?): RangeRef? {
        if (stmt == null) return null
        val target = if (stmt is BytecodeStatement) stmt.original else stmt
        val expr = target as? ExpressionStatement ?: return null
        return expr.ref as? RangeRef
    }

    private fun extractRangeFromLocal(source: Statement): RangeRef? {
        val target = if (source is BytecodeStatement) source.original else source
        val expr = target as? ExpressionStatement ?: return null
        val localRef = expr.ref as? LocalSlotRef ?: return null
        val key = ScopeSlotKey(refScopeDepth(localRef), refSlot(localRef))
        return localRangeRefs[key]
    }

    private fun extractTypedRangeLocal(source: Statement): LocalSlotRef? {
        if (rangeLocalNames.isEmpty()) return null
        val target = if (source is BytecodeStatement) source.original else source
        val expr = target as? ExpressionStatement ?: return null
        val localRef = expr.ref as? LocalSlotRef ?: return null
        if (localRef.isDelegated) return null
        return if (rangeLocalNames.contains(localRef.name)) localRef else null
    }

    private fun effectiveLocalDepth(depth: Int): Int {
        if (depth == 0 || virtualScopeDepths.isEmpty()) return depth
        var virtualCount = 0
        for (virtualDepth in virtualScopeDepths) {
            if (virtualDepth <= depth) {
                virtualCount += 1
            }
        }
        return depth - virtualCount
    }

    private data class ScopeSlotKey(val depth: Int, val slot: Int)
}
