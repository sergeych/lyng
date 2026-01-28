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

class CmdBuilder {
    sealed interface Operand {
        data class IntVal(val value: Int) : Operand
        data class LabelRef(val label: Label) : Operand
    }

    data class Label(val id: Int)

    data class Instr(val op: Opcode, val operands: List<Operand>)

    private val instructions = mutableListOf<Instr>()
    private val constPool = mutableListOf<BytecodeConst>()
    private val labelPositions = mutableMapOf<Label, Int>()
    private var nextLabelId = 0
    private val fallbackStatements = mutableListOf<net.sergeych.lyng.Statement>()

    fun addConst(c: BytecodeConst): Int {
        constPool += c
        return constPool.lastIndex
    }

    fun emit(op: Opcode, vararg operands: Int) {
        instructions += Instr(op, operands.map { Operand.IntVal(it) })
    }

    fun emit(op: Opcode, operands: List<Operand>) {
        instructions += Instr(op, operands)
    }

    fun label(): Label = Label(nextLabelId++)

    fun mark(label: Label) {
        labelPositions[label] = instructions.size
    }

    fun addFallback(stmt: net.sergeych.lyng.Statement): Int {
        fallbackStatements += stmt
        return fallbackStatements.lastIndex
    }

    fun build(
        name: String,
        localCount: Int,
        addrCount: Int = 0,
        returnLabels: Set<String> = emptySet(),
        scopeSlotDepths: IntArray = IntArray(0),
        scopeSlotIndices: IntArray = IntArray(0),
        scopeSlotNames: Array<String?> = emptyArray(),
        localSlotNames: Array<String?> = emptyArray(),
        localSlotMutables: BooleanArray = BooleanArray(0),
        localSlotDepths: IntArray = IntArray(0)
    ): CmdFunction {
        val scopeSlotCount = scopeSlotDepths.size
        require(scopeSlotIndices.size == scopeSlotCount) { "scope slot mapping size mismatch" }
        require(scopeSlotNames.isEmpty() || scopeSlotNames.size == scopeSlotCount) {
            "scope slot name mapping size mismatch"
        }
        require(localSlotNames.size == localSlotMutables.size) { "local slot metadata size mismatch" }
        require(localSlotNames.size == localSlotDepths.size) { "local slot depth metadata size mismatch" }
        val labelIps = mutableMapOf<Label, Int>()
        for ((label, idx) in labelPositions) {
            labelIps[label] = idx
        }
        val cmds = ArrayList<Cmd>(instructions.size)
        for (ins in instructions) {
            val kinds = operandKinds(ins.op)
            if (kinds.size != ins.operands.size) {
                error("Operand count mismatch for ${ins.op}: expected ${kinds.size}, got ${ins.operands.size}")
            }
            val operands = IntArray(kinds.size)
            for (i in kinds.indices) {
                val operand = ins.operands[i]
                val v = when (operand) {
                    is Operand.IntVal -> operand.value
                    is Operand.LabelRef -> labelIps[operand.label]
                        ?: error("Unknown label ${operand.label.id} for ${ins.op}")
                }
                operands[i] = v
            }
            cmds.add(createCmd(ins.op, operands, scopeSlotCount))
        }
        return CmdFunction(
            name = name,
            localCount = localCount,
            addrCount = addrCount,
            returnLabels = returnLabels,
            scopeSlotCount = scopeSlotCount,
            scopeSlotDepths = scopeSlotDepths,
            scopeSlotIndices = scopeSlotIndices,
            scopeSlotNames = if (scopeSlotNames.isEmpty()) Array(scopeSlotCount) { null } else scopeSlotNames,
            localSlotNames = localSlotNames,
            localSlotMutables = localSlotMutables,
            localSlotDepths = localSlotDepths,
            constants = constPool.toList(),
            fallbackStatements = fallbackStatements.toList(),
            cmds = cmds.toTypedArray()
        )
    }

    private fun operandKinds(op: Opcode): List<OperandKind> {
        return when (op) {
            Opcode.NOP, Opcode.RET_VOID, Opcode.POP_SCOPE, Opcode.POP_SLOT_PLAN -> emptyList()
            Opcode.MOVE_OBJ, Opcode.MOVE_INT, Opcode.MOVE_REAL, Opcode.MOVE_BOOL, Opcode.BOX_OBJ,
            Opcode.INT_TO_REAL, Opcode.REAL_TO_INT, Opcode.BOOL_TO_INT, Opcode.INT_TO_BOOL,
            Opcode.NEG_INT, Opcode.NEG_REAL, Opcode.NOT_BOOL, Opcode.INV_INT ->
                listOf(OperandKind.SLOT, OperandKind.SLOT)
            Opcode.RANGE_INT_BOUNDS ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.RET_LABEL, Opcode.THROW ->
                listOf(OperandKind.CONST, OperandKind.SLOT)
            Opcode.RESOLVE_SCOPE_SLOT ->
                listOf(OperandKind.SLOT, OperandKind.ADDR)
            Opcode.LOAD_OBJ_ADDR, Opcode.LOAD_INT_ADDR, Opcode.LOAD_REAL_ADDR, Opcode.LOAD_BOOL_ADDR ->
                listOf(OperandKind.ADDR, OperandKind.SLOT)
            Opcode.STORE_OBJ_ADDR, Opcode.STORE_INT_ADDR, Opcode.STORE_REAL_ADDR, Opcode.STORE_BOOL_ADDR ->
                listOf(OperandKind.SLOT, OperandKind.ADDR)
            Opcode.CONST_NULL ->
                listOf(OperandKind.SLOT)
            Opcode.CONST_OBJ, Opcode.CONST_INT, Opcode.CONST_REAL, Opcode.CONST_BOOL ->
                listOf(OperandKind.CONST, OperandKind.SLOT)
            Opcode.PUSH_SCOPE, Opcode.PUSH_SLOT_PLAN ->
                listOf(OperandKind.CONST)
            Opcode.DECL_LOCAL ->
                listOf(OperandKind.CONST, OperandKind.SLOT)
            Opcode.ADD_INT, Opcode.SUB_INT, Opcode.MUL_INT, Opcode.DIV_INT, Opcode.MOD_INT,
            Opcode.ADD_REAL, Opcode.SUB_REAL, Opcode.MUL_REAL, Opcode.DIV_REAL,
            Opcode.AND_INT, Opcode.OR_INT, Opcode.XOR_INT, Opcode.SHL_INT, Opcode.SHR_INT, Opcode.USHR_INT,
            Opcode.CMP_EQ_INT, Opcode.CMP_NEQ_INT, Opcode.CMP_LT_INT, Opcode.CMP_LTE_INT,
            Opcode.CMP_GT_INT, Opcode.CMP_GTE_INT,
            Opcode.CMP_EQ_REAL, Opcode.CMP_NEQ_REAL, Opcode.CMP_LT_REAL, Opcode.CMP_LTE_REAL,
            Opcode.CMP_GT_REAL, Opcode.CMP_GTE_REAL,
            Opcode.CMP_EQ_BOOL, Opcode.CMP_NEQ_BOOL,
            Opcode.CMP_EQ_INT_REAL, Opcode.CMP_EQ_REAL_INT, Opcode.CMP_LT_INT_REAL, Opcode.CMP_LT_REAL_INT,
            Opcode.CMP_LTE_INT_REAL, Opcode.CMP_LTE_REAL_INT, Opcode.CMP_GT_INT_REAL, Opcode.CMP_GT_REAL_INT,
            Opcode.CMP_GTE_INT_REAL, Opcode.CMP_GTE_REAL_INT, Opcode.CMP_NEQ_INT_REAL, Opcode.CMP_NEQ_REAL_INT,
            Opcode.CMP_EQ_OBJ, Opcode.CMP_NEQ_OBJ, Opcode.CMP_REF_EQ_OBJ, Opcode.CMP_REF_NEQ_OBJ,
            Opcode.CMP_LT_OBJ, Opcode.CMP_LTE_OBJ, Opcode.CMP_GT_OBJ, Opcode.CMP_GTE_OBJ,
            Opcode.ADD_OBJ, Opcode.SUB_OBJ, Opcode.MUL_OBJ, Opcode.DIV_OBJ, Opcode.MOD_OBJ,
            Opcode.AND_BOOL, Opcode.OR_BOOL ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.INC_INT, Opcode.DEC_INT, Opcode.RET ->
                listOf(OperandKind.SLOT)
            Opcode.JMP ->
                listOf(OperandKind.IP)
            Opcode.JMP_IF_TRUE, Opcode.JMP_IF_FALSE ->
                listOf(OperandKind.SLOT, OperandKind.IP)
            Opcode.CALL_DIRECT, Opcode.CALL_FALLBACK ->
                listOf(OperandKind.ID, OperandKind.SLOT, OperandKind.COUNT, OperandKind.SLOT)
            Opcode.CALL_SLOT ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.COUNT, OperandKind.SLOT)
            Opcode.CALL_VIRTUAL ->
                listOf(OperandKind.SLOT, OperandKind.ID, OperandKind.SLOT, OperandKind.COUNT, OperandKind.SLOT)
            Opcode.GET_FIELD ->
                listOf(OperandKind.SLOT, OperandKind.ID, OperandKind.SLOT)
            Opcode.SET_FIELD ->
                listOf(OperandKind.SLOT, OperandKind.ID, OperandKind.SLOT)
            Opcode.GET_INDEX ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.SET_INDEX ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.EVAL_FALLBACK ->
                listOf(OperandKind.ID, OperandKind.SLOT)
        }
    }

    private enum class OperandKind {
        SLOT,
        ADDR,
        CONST,
        IP,
        COUNT,
        ID,
    }

    private fun createCmd(op: Opcode, operands: IntArray, scopeSlotCount: Int): Cmd {
        return when (op) {
            Opcode.NOP -> CmdNop()
            Opcode.MOVE_OBJ -> CmdMoveObj(operands[0], operands[1])
            Opcode.MOVE_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount) {
                CmdMoveIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdMoveInt(operands[0], operands[1])
            }
            Opcode.MOVE_REAL -> CmdMoveReal(operands[0], operands[1])
            Opcode.MOVE_BOOL -> CmdMoveBool(operands[0], operands[1])
            Opcode.CONST_OBJ -> CmdConstObj(operands[0], operands[1])
            Opcode.CONST_INT -> if (operands[1] >= scopeSlotCount) {
                CmdConstIntLocal(operands[0], operands[1] - scopeSlotCount)
            } else {
                CmdConstInt(operands[0], operands[1])
            }
            Opcode.CONST_REAL -> CmdConstReal(operands[0], operands[1])
            Opcode.CONST_BOOL -> CmdConstBool(operands[0], operands[1])
            Opcode.CONST_NULL -> CmdConstNull(operands[0])
            Opcode.BOX_OBJ -> CmdBoxObj(operands[0], operands[1])
            Opcode.RANGE_INT_BOUNDS -> CmdRangeIntBounds(operands[0], operands[1], operands[2], operands[3])
            Opcode.RET_LABEL -> CmdRetLabel(operands[0], operands[1])
            Opcode.THROW -> CmdThrow(operands[0], operands[1])
            Opcode.RESOLVE_SCOPE_SLOT -> CmdResolveScopeSlot(operands[0], operands[1])
            Opcode.LOAD_OBJ_ADDR -> CmdLoadObjAddr(operands[0], operands[1])
            Opcode.STORE_OBJ_ADDR -> CmdStoreObjAddr(operands[0], operands[1])
            Opcode.LOAD_INT_ADDR -> CmdLoadIntAddr(operands[0], operands[1])
            Opcode.STORE_INT_ADDR -> CmdStoreIntAddr(operands[0], operands[1])
            Opcode.LOAD_REAL_ADDR -> CmdLoadRealAddr(operands[0], operands[1])
            Opcode.STORE_REAL_ADDR -> CmdStoreRealAddr(operands[0], operands[1])
            Opcode.LOAD_BOOL_ADDR -> CmdLoadBoolAddr(operands[0], operands[1])
            Opcode.STORE_BOOL_ADDR -> CmdStoreBoolAddr(operands[0], operands[1])
            Opcode.INT_TO_REAL -> CmdIntToReal(operands[0], operands[1])
            Opcode.REAL_TO_INT -> CmdRealToInt(operands[0], operands[1])
            Opcode.BOOL_TO_INT -> CmdBoolToInt(operands[0], operands[1])
            Opcode.INT_TO_BOOL -> CmdIntToBool(operands[0], operands[1])
            Opcode.ADD_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount && operands[2] >= scopeSlotCount) {
                CmdAddIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdAddInt(operands[0], operands[1], operands[2])
            }
            Opcode.SUB_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount && operands[2] >= scopeSlotCount) {
                CmdSubIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdSubInt(operands[0], operands[1], operands[2])
            }
            Opcode.MUL_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount && operands[2] >= scopeSlotCount) {
                CmdMulIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdMulInt(operands[0], operands[1], operands[2])
            }
            Opcode.DIV_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount && operands[2] >= scopeSlotCount) {
                CmdDivIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdDivInt(operands[0], operands[1], operands[2])
            }
            Opcode.MOD_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount && operands[2] >= scopeSlotCount) {
                CmdModIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdModInt(operands[0], operands[1], operands[2])
            }
            Opcode.NEG_INT -> CmdNegInt(operands[0], operands[1])
            Opcode.INC_INT -> if (operands[0] >= scopeSlotCount) {
                CmdIncIntLocal(operands[0] - scopeSlotCount)
            } else {
                CmdIncInt(operands[0])
            }
            Opcode.DEC_INT -> if (operands[0] >= scopeSlotCount) {
                CmdDecIntLocal(operands[0] - scopeSlotCount)
            } else {
                CmdDecInt(operands[0])
            }
            Opcode.ADD_REAL -> CmdAddReal(operands[0], operands[1], operands[2])
            Opcode.SUB_REAL -> CmdSubReal(operands[0], operands[1], operands[2])
            Opcode.MUL_REAL -> CmdMulReal(operands[0], operands[1], operands[2])
            Opcode.DIV_REAL -> CmdDivReal(operands[0], operands[1], operands[2])
            Opcode.NEG_REAL -> CmdNegReal(operands[0], operands[1])
            Opcode.AND_INT -> CmdAndInt(operands[0], operands[1], operands[2])
            Opcode.OR_INT -> CmdOrInt(operands[0], operands[1], operands[2])
            Opcode.XOR_INT -> CmdXorInt(operands[0], operands[1], operands[2])
            Opcode.SHL_INT -> CmdShlInt(operands[0], operands[1], operands[2])
            Opcode.SHR_INT -> CmdShrInt(operands[0], operands[1], operands[2])
            Opcode.USHR_INT -> CmdUshrInt(operands[0], operands[1], operands[2])
            Opcode.INV_INT -> CmdInvInt(operands[0], operands[1])
            Opcode.CMP_EQ_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount && operands[2] >= scopeSlotCount) {
                CmdCmpEqIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpEqInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_NEQ_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount && operands[2] >= scopeSlotCount) {
                CmdCmpNeqIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpNeqInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LT_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount && operands[2] >= scopeSlotCount) {
                CmdCmpLtIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLtInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LTE_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount && operands[2] >= scopeSlotCount) {
                CmdCmpLteIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLteInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GT_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount && operands[2] >= scopeSlotCount) {
                CmdCmpGtIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGtInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GTE_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount && operands[2] >= scopeSlotCount) {
                CmdCmpGteIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGteInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_EQ_REAL -> CmdCmpEqReal(operands[0], operands[1], operands[2])
            Opcode.CMP_NEQ_REAL -> CmdCmpNeqReal(operands[0], operands[1], operands[2])
            Opcode.CMP_LT_REAL -> CmdCmpLtReal(operands[0], operands[1], operands[2])
            Opcode.CMP_LTE_REAL -> CmdCmpLteReal(operands[0], operands[1], operands[2])
            Opcode.CMP_GT_REAL -> CmdCmpGtReal(operands[0], operands[1], operands[2])
            Opcode.CMP_GTE_REAL -> CmdCmpGteReal(operands[0], operands[1], operands[2])
            Opcode.CMP_EQ_BOOL -> CmdCmpEqBool(operands[0], operands[1], operands[2])
            Opcode.CMP_NEQ_BOOL -> CmdCmpNeqBool(operands[0], operands[1], operands[2])
            Opcode.CMP_EQ_INT_REAL -> CmdCmpEqIntReal(operands[0], operands[1], operands[2])
            Opcode.CMP_EQ_REAL_INT -> CmdCmpEqRealInt(operands[0], operands[1], operands[2])
            Opcode.CMP_LT_INT_REAL -> CmdCmpLtIntReal(operands[0], operands[1], operands[2])
            Opcode.CMP_LT_REAL_INT -> CmdCmpLtRealInt(operands[0], operands[1], operands[2])
            Opcode.CMP_LTE_INT_REAL -> CmdCmpLteIntReal(operands[0], operands[1], operands[2])
            Opcode.CMP_LTE_REAL_INT -> CmdCmpLteRealInt(operands[0], operands[1], operands[2])
            Opcode.CMP_GT_INT_REAL -> CmdCmpGtIntReal(operands[0], operands[1], operands[2])
            Opcode.CMP_GT_REAL_INT -> CmdCmpGtRealInt(operands[0], operands[1], operands[2])
            Opcode.CMP_GTE_INT_REAL -> CmdCmpGteIntReal(operands[0], operands[1], operands[2])
            Opcode.CMP_GTE_REAL_INT -> CmdCmpGteRealInt(operands[0], operands[1], operands[2])
            Opcode.CMP_NEQ_INT_REAL -> CmdCmpNeqIntReal(operands[0], operands[1], operands[2])
            Opcode.CMP_NEQ_REAL_INT -> CmdCmpNeqRealInt(operands[0], operands[1], operands[2])
            Opcode.CMP_EQ_OBJ -> CmdCmpEqObj(operands[0], operands[1], operands[2])
            Opcode.CMP_NEQ_OBJ -> CmdCmpNeqObj(operands[0], operands[1], operands[2])
            Opcode.CMP_REF_EQ_OBJ -> CmdCmpRefEqObj(operands[0], operands[1], operands[2])
            Opcode.CMP_REF_NEQ_OBJ -> CmdCmpRefNeqObj(operands[0], operands[1], operands[2])
            Opcode.NOT_BOOL -> CmdNotBool(operands[0], operands[1])
            Opcode.AND_BOOL -> CmdAndBool(operands[0], operands[1], operands[2])
            Opcode.OR_BOOL -> CmdOrBool(operands[0], operands[1], operands[2])
            Opcode.CMP_LT_OBJ -> CmdCmpLtObj(operands[0], operands[1], operands[2])
            Opcode.CMP_LTE_OBJ -> CmdCmpLteObj(operands[0], operands[1], operands[2])
            Opcode.CMP_GT_OBJ -> CmdCmpGtObj(operands[0], operands[1], operands[2])
            Opcode.CMP_GTE_OBJ -> CmdCmpGteObj(operands[0], operands[1], operands[2])
            Opcode.ADD_OBJ -> CmdAddObj(operands[0], operands[1], operands[2])
            Opcode.SUB_OBJ -> CmdSubObj(operands[0], operands[1], operands[2])
            Opcode.MUL_OBJ -> CmdMulObj(operands[0], operands[1], operands[2])
            Opcode.DIV_OBJ -> CmdDivObj(operands[0], operands[1], operands[2])
            Opcode.MOD_OBJ -> CmdModObj(operands[0], operands[1], operands[2])
            Opcode.JMP -> CmdJmp(operands[0])
            Opcode.JMP_IF_TRUE -> CmdJmpIfTrue(operands[0], operands[1])
            Opcode.JMP_IF_FALSE -> CmdJmpIfFalse(operands[0], operands[1])
            Opcode.RET -> CmdRet(operands[0])
            Opcode.RET_VOID -> CmdRetVoid()
            Opcode.PUSH_SCOPE -> CmdPushScope(operands[0])
            Opcode.POP_SCOPE -> CmdPopScope()
            Opcode.PUSH_SLOT_PLAN -> CmdPushSlotPlan(operands[0])
            Opcode.POP_SLOT_PLAN -> CmdPopSlotPlan()
            Opcode.DECL_LOCAL -> CmdDeclLocal(operands[0], operands[1])
            Opcode.CALL_DIRECT -> CmdCallDirect(operands[0], operands[1], operands[2], operands[3])
            Opcode.CALL_VIRTUAL -> CmdCallVirtual(operands[0], operands[1], operands[2], operands[3], operands[4])
            Opcode.CALL_FALLBACK -> CmdCallFallback(operands[0], operands[1], operands[2], operands[3])
            Opcode.CALL_SLOT -> CmdCallSlot(operands[0], operands[1], operands[2], operands[3])
            Opcode.GET_FIELD -> CmdGetField(operands[0], operands[1], operands[2])
            Opcode.SET_FIELD -> CmdSetField(operands[0], operands[1], operands[2])
            Opcode.GET_INDEX -> CmdGetIndex(operands[0], operands[1], operands[2])
            Opcode.SET_INDEX -> CmdSetIndex(operands[0], operands[1], operands[2])
            Opcode.EVAL_FALLBACK -> CmdEvalFallback(operands[0], operands[1])
        }
    }
}
