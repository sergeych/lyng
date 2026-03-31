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

class CmdBuilder {
    sealed interface Operand {
        data class IntVal(val value: Int) : Operand
        data class LabelRef(val label: Label) : Operand
    }

    data class Label(val id: Int)

    data class Instr(val op: Opcode, val operands: List<Operand>)

    private val instructions = mutableListOf<Instr>()
    private val posByInstr = mutableListOf<net.sergeych.lyng.Pos?>()
    private val constPool = mutableListOf<BytecodeConst>()
    private val labelPositions = mutableMapOf<Label, Int>()
    private var nextLabelId = 0
    private var currentPos: net.sergeych.lyng.Pos? = null

    fun addConst(c: BytecodeConst): Int {
        constPool += c
        return constPool.lastIndex
    }

    fun emit(op: Opcode, vararg operands: Int) {
        instructions += Instr(op, operands.map { Operand.IntVal(it) })
        posByInstr += currentPos
    }

    fun emit(op: Opcode, operands: List<Operand>) {
        instructions += Instr(op, operands)
        posByInstr += currentPos
    }

    fun setPos(pos: net.sergeych.lyng.Pos?) {
        currentPos = pos
    }

    fun label(): Label = Label(nextLabelId++)

    fun mark(label: Label) {
        labelPositions[label] = instructions.size
    }

    fun build(
        name: String,
        localCount: Int,
        addrCount: Int = 0,
        returnLabels: Set<String> = emptySet(),
        scopeSlotIndices: IntArray = IntArray(0),
        scopeSlotNames: Array<String?> = emptyArray(),
        scopeSlotIsModule: BooleanArray = BooleanArray(0),
        scopeSlotRequiresPreparedBinding: BooleanArray = BooleanArray(0),
        scopeSlotRefPos: Array<net.sergeych.lyng.Pos?> = emptyArray(),
        localSlotNames: Array<String?> = emptyArray(),
        localSlotMutables: BooleanArray = BooleanArray(0),
        localSlotDelegated: BooleanArray = BooleanArray(0),
        localSlotCaptures: BooleanArray = BooleanArray(0)
    ): CmdFunction {
        val scopeSlotCount = scopeSlotIndices.size
        require(scopeSlotNames.isEmpty() || scopeSlotNames.size == scopeSlotCount) {
            "scope slot name mapping size mismatch"
        }
        require(scopeSlotIsModule.isEmpty() || scopeSlotIsModule.size == scopeSlotCount) {
            "scope slot module mapping size mismatch"
        }
        require(scopeSlotRequiresPreparedBinding.isEmpty() || scopeSlotRequiresPreparedBinding.size == scopeSlotCount) {
            "scope slot prepared-binding mapping size mismatch"
        }
        require(scopeSlotRefPos.isEmpty() || scopeSlotRefPos.size == scopeSlotCount) {
            "scope slot position mapping size mismatch"
        }
        require(localSlotNames.size == localSlotMutables.size) { "local slot metadata size mismatch" }
        require(localSlotNames.size == localSlotDelegated.size) { "local slot delegation size mismatch" }
        require(localSlotNames.size == localSlotCaptures.size) { "local slot capture size mismatch" }
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
            cmds.add(createCmd(ins.op, operands, scopeSlotCount, localSlotCaptures))
        }
        return CmdFunction(
            name = name,
            localCount = localCount,
            addrCount = addrCount,
            returnLabels = returnLabels,
            scopeSlotCount = scopeSlotCount,
            scopeSlotIndices = scopeSlotIndices,
            scopeSlotNames = if (scopeSlotNames.isEmpty()) Array(scopeSlotCount) { null } else scopeSlotNames,
            scopeSlotIsModule = if (scopeSlotIsModule.isEmpty()) BooleanArray(scopeSlotCount) else scopeSlotIsModule,
            scopeSlotRequiresPreparedBinding = if (scopeSlotRequiresPreparedBinding.isEmpty()) BooleanArray(scopeSlotCount) else scopeSlotRequiresPreparedBinding,
            scopeSlotRefPos = if (scopeSlotRefPos.isEmpty()) Array(scopeSlotCount) { null } else scopeSlotRefPos,
            localSlotNames = localSlotNames,
            localSlotMutables = localSlotMutables,
            localSlotDelegated = localSlotDelegated,
            localSlotCaptures = localSlotCaptures,
            constants = constPool.toList(),
            cmds = cmds.toTypedArray(),
            posByIp = posByInstr.toTypedArray()
        )
    }

    private fun operandKinds(op: Opcode): List<OperandKind> {
        return when (op) {
            Opcode.NOP, Opcode.RET_VOID, Opcode.POP_SCOPE, Opcode.POP_SLOT_PLAN, Opcode.POP_TRY,
            Opcode.CLEAR_PENDING_THROWABLE, Opcode.RETHROW_PENDING -> emptyList()
            Opcode.MOVE_OBJ, Opcode.MOVE_INT, Opcode.MOVE_REAL, Opcode.MOVE_BOOL, Opcode.BOX_OBJ,
            Opcode.UNBOX_INT_OBJ, Opcode.UNBOX_REAL_OBJ,
            Opcode.INT_TO_REAL, Opcode.REAL_TO_INT, Opcode.BOOL_TO_INT, Opcode.INT_TO_BOOL,
            Opcode.OBJ_TO_BOOL, Opcode.GET_OBJ_CLASS,
            Opcode.NEG_INT, Opcode.NEG_REAL, Opcode.NOT_BOOL, Opcode.INV_INT,
            Opcode.ASSERT_IS ->
                listOf(OperandKind.SLOT, OperandKind.SLOT)
            Opcode.CHECK_IS, Opcode.MAKE_QUALIFIED_VIEW ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.RANGE_INT_BOUNDS ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.RET_LABEL, Opcode.THROW ->
                listOf(OperandKind.CONST, OperandKind.SLOT)
            Opcode.RESOLVE_SCOPE_SLOT ->
                listOf(OperandKind.SLOT, OperandKind.ADDR)
            Opcode.DELEGATED_GET_LOCAL ->
                listOf(OperandKind.SLOT, OperandKind.CONST, OperandKind.SLOT)
            Opcode.DELEGATED_SET_LOCAL ->
                listOf(OperandKind.SLOT, OperandKind.CONST, OperandKind.SLOT)
            Opcode.BIND_DELEGATE_LOCAL ->
                listOf(OperandKind.SLOT, OperandKind.CONST, OperandKind.CONST, OperandKind.SLOT)
            Opcode.LOAD_OBJ_ADDR, Opcode.LOAD_INT_ADDR, Opcode.LOAD_REAL_ADDR, Opcode.LOAD_BOOL_ADDR ->
                listOf(OperandKind.ADDR, OperandKind.SLOT)
            Opcode.STORE_OBJ_ADDR, Opcode.STORE_INT_ADDR, Opcode.STORE_REAL_ADDR, Opcode.STORE_BOOL_ADDR ->
                listOf(OperandKind.SLOT, OperandKind.ADDR)
            Opcode.CONST_NULL ->
                listOf(OperandKind.SLOT)
            Opcode.CONST_OBJ, Opcode.CONST_INT, Opcode.CONST_REAL, Opcode.CONST_BOOL,
            Opcode.MAKE_LAMBDA_FN ->
                listOf(OperandKind.CONST, OperandKind.SLOT)
            Opcode.PUSH_SCOPE, Opcode.PUSH_SLOT_PLAN ->
                listOf(OperandKind.CONST)
            Opcode.PUSH_TRY ->
                listOf(OperandKind.SLOT, OperandKind.IP, OperandKind.IP)
            Opcode.DECL_LOCAL, Opcode.DECL_EXT_PROPERTY, Opcode.DECL_DELEGATED, Opcode.DECL_DESTRUCTURE,
            Opcode.DECL_ENUM, Opcode.DECL_FUNCTION, Opcode.DECL_CLASS, Opcode.DECL_CLASS_FIELD,
            Opcode.DECL_CLASS_DELEGATED, Opcode.DECL_CLASS_INSTANCE_INIT, Opcode.DECL_CLASS_INSTANCE_FIELD,
            Opcode.DECL_CLASS_INSTANCE_PROPERTY, Opcode.DECL_CLASS_INSTANCE_DELEGATED, Opcode.DECL_INSTANCE_FIELD,
            Opcode.DECL_INSTANCE_PROPERTY, Opcode.DECL_INSTANCE_DELEGATED,
            Opcode.ASSIGN_DESTRUCTURE ->
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
            Opcode.CMP_EQ_STR, Opcode.CMP_NEQ_STR, Opcode.CMP_LT_STR, Opcode.CMP_LTE_STR,
            Opcode.CMP_GT_STR, Opcode.CMP_GTE_STR,
            Opcode.CMP_EQ_INT_OBJ, Opcode.CMP_NEQ_INT_OBJ, Opcode.CMP_LT_INT_OBJ, Opcode.CMP_LTE_INT_OBJ,
            Opcode.CMP_GT_INT_OBJ, Opcode.CMP_GTE_INT_OBJ, Opcode.CMP_EQ_REAL_OBJ, Opcode.CMP_NEQ_REAL_OBJ,
            Opcode.CMP_LT_REAL_OBJ, Opcode.CMP_LTE_REAL_OBJ, Opcode.CMP_GT_REAL_OBJ, Opcode.CMP_GTE_REAL_OBJ,
            Opcode.CMP_LT_OBJ, Opcode.CMP_LTE_OBJ, Opcode.CMP_GT_OBJ, Opcode.CMP_GTE_OBJ,
            Opcode.ADD_INT_OBJ, Opcode.SUB_INT_OBJ, Opcode.MUL_INT_OBJ, Opcode.DIV_INT_OBJ, Opcode.MOD_INT_OBJ,
            Opcode.ADD_REAL_OBJ, Opcode.SUB_REAL_OBJ, Opcode.MUL_REAL_OBJ, Opcode.DIV_REAL_OBJ, Opcode.MOD_REAL_OBJ,
            Opcode.ADD_OBJ, Opcode.SUB_OBJ, Opcode.MUL_OBJ, Opcode.DIV_OBJ, Opcode.MOD_OBJ, Opcode.CONTAINS_OBJ,
            Opcode.AND_BOOL, Opcode.OR_BOOL ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.ASSIGN_OP_OBJ ->
                listOf(OperandKind.ID, OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT, OperandKind.CONST)
            Opcode.INC_INT, Opcode.DEC_INT, Opcode.RET, Opcode.LOAD_THIS ->
                listOf(OperandKind.SLOT)
            Opcode.LOAD_THIS_VARIANT ->
                listOf(OperandKind.ID, OperandKind.SLOT)
            Opcode.JMP ->
                listOf(OperandKind.IP)
            Opcode.JMP_IF_TRUE, Opcode.JMP_IF_FALSE ->
                listOf(OperandKind.SLOT, OperandKind.IP)
            Opcode.JMP_IF_EQ_INT, Opcode.JMP_IF_NEQ_INT,
            Opcode.JMP_IF_LT_INT, Opcode.JMP_IF_LTE_INT,
            Opcode.JMP_IF_GT_INT, Opcode.JMP_IF_GTE_INT ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.IP)
            Opcode.CALL_DIRECT ->
                listOf(OperandKind.ID, OperandKind.SLOT, OperandKind.COUNT, OperandKind.SLOT)
            Opcode.CALL_MEMBER_SLOT ->
                listOf(OperandKind.SLOT, OperandKind.ID, OperandKind.SLOT, OperandKind.COUNT, OperandKind.SLOT)
            Opcode.CALL_SLOT, Opcode.CALL_BRIDGE_SLOT ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.COUNT, OperandKind.SLOT)
            Opcode.CALL_DYNAMIC_MEMBER ->
                listOf(OperandKind.SLOT, OperandKind.CONST, OperandKind.SLOT, OperandKind.COUNT, OperandKind.SLOT)
            Opcode.GET_INDEX ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.SET_INDEX ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.MAKE_RANGE ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.LIST_LITERAL ->
                listOf(OperandKind.CONST, OperandKind.SLOT, OperandKind.COUNT, OperandKind.SLOT)
            Opcode.GET_MEMBER_SLOT ->
                listOf(OperandKind.SLOT, OperandKind.ID, OperandKind.ID, OperandKind.SLOT)
            Opcode.SET_MEMBER_SLOT ->
                listOf(OperandKind.SLOT, OperandKind.ID, OperandKind.ID, OperandKind.SLOT)
            Opcode.GET_CLASS_SCOPE ->
                listOf(OperandKind.SLOT, OperandKind.CONST, OperandKind.SLOT)
            Opcode.SET_CLASS_SCOPE ->
                listOf(OperandKind.SLOT, OperandKind.CONST, OperandKind.SLOT)
            Opcode.GET_DYNAMIC_MEMBER ->
                listOf(OperandKind.SLOT, OperandKind.CONST, OperandKind.SLOT)
            Opcode.SET_DYNAMIC_MEMBER ->
                listOf(OperandKind.SLOT, OperandKind.CONST, OperandKind.SLOT)
            Opcode.ITER_PUSH ->
                listOf(OperandKind.SLOT)
            Opcode.ITER_POP, Opcode.ITER_CANCEL ->
                emptyList()
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

    private fun createCmd(
        op: Opcode,
        operands: IntArray,
        scopeSlotCount: Int,
        localSlotCaptures: BooleanArray
    ): Cmd {
        fun isFastLocal(slot: Int): Boolean {
            if (slot < scopeSlotCount) return false
            val localIndex = slot - scopeSlotCount
            return localSlotCaptures.getOrNull(localIndex) != true
        }
        return when (op) {
            Opcode.NOP -> CmdNop()
            Opcode.MOVE_OBJ -> CmdMoveObj(operands[0], operands[1])
            Opcode.MOVE_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdMoveIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdMoveInt(operands[0], operands[1])
            }
            Opcode.MOVE_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdMoveRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdMoveReal(operands[0], operands[1])
            }
            Opcode.MOVE_BOOL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdMoveBoolLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdMoveBool(operands[0], operands[1])
            }
            Opcode.CONST_OBJ -> CmdConstObj(operands[0], operands[1])
            Opcode.CONST_INT -> if (isFastLocal(operands[1])) {
                CmdConstIntLocal(operands[0], operands[1] - scopeSlotCount)
            } else {
                CmdConstInt(operands[0], operands[1])
            }
            Opcode.CONST_REAL -> CmdConstReal(operands[0], operands[1])
            Opcode.CONST_BOOL -> CmdConstBool(operands[0], operands[1])
            Opcode.CONST_NULL -> CmdConstNull(operands[0])
            Opcode.MAKE_LAMBDA_FN -> CmdMakeLambda(operands[0], operands[1])
            Opcode.BOX_OBJ -> CmdBoxObj(operands[0], operands[1])
            Opcode.UNBOX_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdUnboxIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdUnboxIntObj(operands[0], operands[1])
            }
            Opcode.UNBOX_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdUnboxRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdUnboxRealObj(operands[0], operands[1])
            }
            Opcode.OBJ_TO_BOOL -> CmdObjToBool(operands[0], operands[1])
            Opcode.GET_OBJ_CLASS -> CmdGetObjClass(operands[0], operands[1])
            Opcode.RANGE_INT_BOUNDS -> CmdRangeIntBounds(operands[0], operands[1], operands[2], operands[3])
            Opcode.LOAD_THIS -> CmdLoadThis(operands[0])
            Opcode.LOAD_THIS_VARIANT -> CmdLoadThisVariant(operands[0], operands[1])
            Opcode.MAKE_RANGE -> CmdMakeRange(operands[0], operands[1], operands[2], operands[3], operands[4])
            Opcode.CHECK_IS -> CmdCheckIs(operands[0], operands[1], operands[2])
            Opcode.ASSERT_IS -> CmdAssertIs(operands[0], operands[1])
            Opcode.MAKE_QUALIFIED_VIEW -> CmdMakeQualifiedView(operands[0], operands[1], operands[2])
            Opcode.RET_LABEL -> CmdRetLabel(operands[0], operands[1])
            Opcode.THROW -> CmdThrow(operands[0], operands[1])
            Opcode.RETHROW_PENDING -> CmdRethrowPending()
            Opcode.RESOLVE_SCOPE_SLOT -> CmdResolveScopeSlot(operands[0], operands[1])
            Opcode.DELEGATED_GET_LOCAL -> CmdDelegatedGetLocal(operands[0], operands[1], operands[2])
            Opcode.DELEGATED_SET_LOCAL -> CmdDelegatedSetLocal(operands[0], operands[1], operands[2])
            Opcode.BIND_DELEGATE_LOCAL -> CmdBindDelegateLocal(operands[0], operands[1], operands[2], operands[3])
            Opcode.LOAD_OBJ_ADDR -> CmdLoadObjAddr(operands[0], operands[1])
            Opcode.STORE_OBJ_ADDR -> CmdStoreObjAddr(operands[0], operands[1])
            Opcode.LOAD_INT_ADDR -> CmdLoadIntAddr(operands[0], operands[1])
            Opcode.STORE_INT_ADDR -> CmdStoreIntAddr(operands[0], operands[1])
            Opcode.LOAD_REAL_ADDR -> CmdLoadRealAddr(operands[0], operands[1])
            Opcode.STORE_REAL_ADDR -> CmdStoreRealAddr(operands[0], operands[1])
            Opcode.LOAD_BOOL_ADDR -> CmdLoadBoolAddr(operands[0], operands[1])
            Opcode.STORE_BOOL_ADDR -> CmdStoreBoolAddr(operands[0], operands[1])
            Opcode.INT_TO_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdIntToRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdIntToReal(operands[0], operands[1])
            }
            Opcode.REAL_TO_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdRealToIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdRealToInt(operands[0], operands[1])
            }
            Opcode.BOOL_TO_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdBoolToIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdBoolToInt(operands[0], operands[1])
            }
            Opcode.INT_TO_BOOL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdIntToBoolLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdIntToBool(operands[0], operands[1])
            }
            Opcode.ADD_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdAddIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdAddInt(operands[0], operands[1], operands[2])
            }
            Opcode.SUB_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdSubIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdSubInt(operands[0], operands[1], operands[2])
            }
            Opcode.MUL_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdMulIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdMulInt(operands[0], operands[1], operands[2])
            }
            Opcode.DIV_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdDivIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdDivInt(operands[0], operands[1], operands[2])
            }
            Opcode.MOD_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdModIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdModInt(operands[0], operands[1], operands[2])
            }
            Opcode.NEG_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdNegIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdNegInt(operands[0], operands[1])
            }
            Opcode.INC_INT -> if (isFastLocal(operands[0])) {
                CmdIncIntLocal(operands[0] - scopeSlotCount)
            } else {
                CmdIncInt(operands[0])
            }
            Opcode.DEC_INT -> if (isFastLocal(operands[0])) {
                CmdDecIntLocal(operands[0] - scopeSlotCount)
            } else {
                CmdDecInt(operands[0])
            }
            Opcode.ADD_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdAddRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdAddReal(operands[0], operands[1], operands[2])
            }
            Opcode.SUB_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdSubRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdSubReal(operands[0], operands[1], operands[2])
            }
            Opcode.MUL_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdMulRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdMulReal(operands[0], operands[1], operands[2])
            }
            Opcode.DIV_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdDivRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdDivReal(operands[0], operands[1], operands[2])
            }
            Opcode.NEG_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdNegRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdNegReal(operands[0], operands[1])
            }
            Opcode.AND_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdAndIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdAndInt(operands[0], operands[1], operands[2])
            }
            Opcode.OR_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdOrIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdOrInt(operands[0], operands[1], operands[2])
            }
            Opcode.XOR_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdXorIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdXorInt(operands[0], operands[1], operands[2])
            }
            Opcode.SHL_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdShlIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdShlInt(operands[0], operands[1], operands[2])
            }
            Opcode.SHR_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdShrIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdShrInt(operands[0], operands[1], operands[2])
            }
            Opcode.USHR_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdUshrIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdUshrInt(operands[0], operands[1], operands[2])
            }
            Opcode.INV_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdInvIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdInvInt(operands[0], operands[1])
            }
            Opcode.CMP_EQ_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpEqIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpEqInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_NEQ_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpNeqIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpNeqInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LT_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLtIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLtInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LTE_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLteIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLteInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GT_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGtIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGtInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GTE_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGteIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGteInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_EQ_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpEqRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpEqReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_NEQ_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpNeqRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpNeqReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LT_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLtRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLtReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LTE_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLteRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLteReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GT_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGtRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGtReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GTE_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGteRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGteReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_EQ_BOOL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpEqBoolLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpEqBool(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_NEQ_BOOL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpNeqBoolLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpNeqBool(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_EQ_INT_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpEqIntRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpEqIntReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_EQ_REAL_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpEqRealIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpEqRealInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LT_INT_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLtIntRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLtIntReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LT_REAL_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLtRealIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLtRealInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LTE_INT_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLteIntRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLteIntReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LTE_REAL_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLteRealIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLteRealInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GT_INT_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGtIntRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGtIntReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GT_REAL_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGtRealIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGtRealInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GTE_INT_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGteIntRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGteIntReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GTE_REAL_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGteRealIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGteRealInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_NEQ_INT_REAL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpNeqIntRealLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpNeqIntReal(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_NEQ_REAL_INT -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpNeqRealIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpNeqRealInt(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_EQ_OBJ -> CmdCmpEqObj(operands[0], operands[1], operands[2])
            Opcode.CMP_NEQ_OBJ -> CmdCmpNeqObj(operands[0], operands[1], operands[2])
            Opcode.CMP_REF_EQ_OBJ -> CmdCmpRefEqObj(operands[0], operands[1], operands[2])
            Opcode.CMP_REF_NEQ_OBJ -> CmdCmpRefNeqObj(operands[0], operands[1], operands[2])
            Opcode.CMP_EQ_STR -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpEqStrLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpEqStr(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_NEQ_STR -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpNeqStrLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpNeqStr(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LT_STR -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLtStrLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLtStr(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LTE_STR -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLteStrLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLteStr(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GT_STR -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGtStrLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGtStr(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GTE_STR -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGteStrLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGteStr(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_EQ_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpEqIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpEqIntObj(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_NEQ_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpNeqIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpNeqIntObj(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LT_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLtIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLtIntObj(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LTE_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLteIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLteIntObj(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GT_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGtIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGtIntObj(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GTE_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGteIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGteIntObj(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_EQ_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpEqRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpEqRealObj(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_NEQ_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpNeqRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpNeqRealObj(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LT_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLtRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLtRealObj(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LTE_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpLteRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpLteRealObj(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GT_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGtRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGtRealObj(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_GTE_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdCmpGteRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdCmpGteRealObj(operands[0], operands[1], operands[2])
            }
            Opcode.NOT_BOOL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1])) {
                CmdNotBoolLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount)
            } else {
                CmdNotBool(operands[0], operands[1])
            }
            Opcode.AND_BOOL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdAndBoolLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdAndBool(operands[0], operands[1], operands[2])
            }
            Opcode.OR_BOOL -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdOrBoolLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdOrBool(operands[0], operands[1], operands[2])
            }
            Opcode.CMP_LT_OBJ -> CmdCmpLtObj(operands[0], operands[1], operands[2])
            Opcode.CMP_LTE_OBJ -> CmdCmpLteObj(operands[0], operands[1], operands[2])
            Opcode.CMP_GT_OBJ -> CmdCmpGtObj(operands[0], operands[1], operands[2])
            Opcode.CMP_GTE_OBJ -> CmdCmpGteObj(operands[0], operands[1], operands[2])
            Opcode.ADD_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdAddIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdAddIntObj(operands[0], operands[1], operands[2])
            }
            Opcode.SUB_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdSubIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdSubIntObj(operands[0], operands[1], operands[2])
            }
            Opcode.MUL_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdMulIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdMulIntObj(operands[0], operands[1], operands[2])
            }
            Opcode.DIV_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdDivIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdDivIntObj(operands[0], operands[1], operands[2])
            }
            Opcode.MOD_INT_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdModIntObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdModIntObj(operands[0], operands[1], operands[2])
            }
            Opcode.ADD_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdAddRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdAddRealObj(operands[0], operands[1], operands[2])
            }
            Opcode.SUB_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdSubRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdSubRealObj(operands[0], operands[1], operands[2])
            }
            Opcode.MUL_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdMulRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdMulRealObj(operands[0], operands[1], operands[2])
            }
            Opcode.DIV_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdDivRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdDivRealObj(operands[0], operands[1], operands[2])
            }
            Opcode.MOD_REAL_OBJ -> if (isFastLocal(operands[0]) && isFastLocal(operands[1]) && isFastLocal(operands[2])) {
                CmdModRealObjLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2] - scopeSlotCount)
            } else {
                CmdModRealObj(operands[0], operands[1], operands[2])
            }
            Opcode.ADD_OBJ -> CmdAddObj(operands[0], operands[1], operands[2])
            Opcode.SUB_OBJ -> CmdSubObj(operands[0], operands[1], operands[2])
            Opcode.MUL_OBJ -> CmdMulObj(operands[0], operands[1], operands[2])
            Opcode.DIV_OBJ -> CmdDivObj(operands[0], operands[1], operands[2])
            Opcode.MOD_OBJ -> CmdModObj(operands[0], operands[1], operands[2])
            Opcode.CONTAINS_OBJ -> CmdContainsObj(operands[0], operands[1], operands[2])
            Opcode.ASSIGN_OP_OBJ -> CmdAssignOpObj(operands[0], operands[1], operands[2], operands[3], operands[4])
            Opcode.JMP -> CmdJmp(operands[0])
            Opcode.JMP_IF_TRUE -> if (operands[0] >= scopeSlotCount) {
                CmdJmpIfTrueLocal(operands[0] - scopeSlotCount, operands[1])
            } else {
                CmdJmpIfTrue(operands[0], operands[1])
            }
            Opcode.JMP_IF_FALSE -> if (operands[0] >= scopeSlotCount) {
                CmdJmpIfFalseLocal(operands[0] - scopeSlotCount, operands[1])
            } else {
                CmdJmpIfFalse(operands[0], operands[1])
            }
            Opcode.JMP_IF_EQ_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount) {
                CmdJmpIfEqIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2])
            } else {
                CmdJmpIfEqInt(operands[0], operands[1], operands[2])
            }
            Opcode.JMP_IF_NEQ_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount) {
                CmdJmpIfNeqIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2])
            } else {
                CmdJmpIfNeqInt(operands[0], operands[1], operands[2])
            }
            Opcode.JMP_IF_LT_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount) {
                CmdJmpIfLtIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2])
            } else {
                CmdJmpIfLtInt(operands[0], operands[1], operands[2])
            }
            Opcode.JMP_IF_LTE_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount) {
                CmdJmpIfLteIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2])
            } else {
                CmdJmpIfLteInt(operands[0], operands[1], operands[2])
            }
            Opcode.JMP_IF_GT_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount) {
                CmdJmpIfGtIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2])
            } else {
                CmdJmpIfGtInt(operands[0], operands[1], operands[2])
            }
            Opcode.JMP_IF_GTE_INT -> if (operands[0] >= scopeSlotCount && operands[1] >= scopeSlotCount) {
                CmdJmpIfGteIntLocal(operands[0] - scopeSlotCount, operands[1] - scopeSlotCount, operands[2])
            } else {
                CmdJmpIfGteInt(operands[0], operands[1], operands[2])
            }
            Opcode.RET -> CmdRet(operands[0])
            Opcode.RET_VOID -> CmdRetVoid()
            Opcode.PUSH_SCOPE -> CmdPushScope(operands[0])
            Opcode.POP_SCOPE -> CmdPopScope()
            Opcode.PUSH_SLOT_PLAN -> CmdPushSlotPlan(operands[0])
            Opcode.POP_SLOT_PLAN -> CmdPopSlotPlan()
            Opcode.PUSH_TRY -> CmdPushTry(operands[0], operands[1], operands[2])
            Opcode.POP_TRY -> CmdPopTry()
            Opcode.CLEAR_PENDING_THROWABLE -> CmdClearPendingThrowable()
            Opcode.DECL_LOCAL -> CmdDeclLocal(operands[0], operands[1])
            Opcode.DECL_DELEGATED -> CmdDeclDelegated(operands[0], operands[1])
            Opcode.DECL_DESTRUCTURE -> CmdDeclDestructure(operands[0], operands[1])
            Opcode.DECL_ENUM -> CmdDeclEnum(operands[0], operands[1])
            Opcode.DECL_FUNCTION -> CmdDeclFunction(operands[0], operands[1])
            Opcode.DECL_CLASS -> CmdDeclClass(operands[0], operands[1])
            Opcode.DECL_CLASS_FIELD -> CmdDeclClassField(operands[0], operands[1])
            Opcode.DECL_CLASS_DELEGATED -> CmdDeclClassDelegated(operands[0], operands[1])
            Opcode.DECL_CLASS_INSTANCE_INIT -> CmdDeclClassInstanceInit(operands[0], operands[1])
            Opcode.DECL_CLASS_INSTANCE_FIELD -> CmdDeclClassInstanceField(operands[0], operands[1])
            Opcode.DECL_CLASS_INSTANCE_PROPERTY -> CmdDeclClassInstanceProperty(operands[0], operands[1])
            Opcode.DECL_CLASS_INSTANCE_DELEGATED -> CmdDeclClassInstanceDelegated(operands[0], operands[1])
            Opcode.DECL_INSTANCE_FIELD -> CmdDeclInstanceField(operands[0], operands[1])
            Opcode.DECL_INSTANCE_PROPERTY -> CmdDeclInstanceProperty(operands[0], operands[1])
            Opcode.DECL_INSTANCE_DELEGATED -> CmdDeclInstanceDelegated(operands[0], operands[1])
            Opcode.DECL_EXT_PROPERTY -> CmdDeclExtProperty(operands[0], operands[1])
            Opcode.CALL_DIRECT -> CmdCallDirect(operands[0], operands[1], operands[2], operands[3])
            Opcode.ASSIGN_DESTRUCTURE -> CmdAssignDestructure(operands[0], operands[1])
            Opcode.CALL_MEMBER_SLOT -> CmdCallMemberSlot(operands[0], operands[1], operands[2], operands[3], operands[4])
            Opcode.CALL_SLOT -> CmdCallSlot(operands[0], operands[1], operands[2], operands[3])
            Opcode.CALL_BRIDGE_SLOT -> CmdCallBridgeSlot(operands[0], operands[1], operands[2], operands[3])
            Opcode.CALL_DYNAMIC_MEMBER -> CmdCallDynamicMember(operands[0], operands[1], operands[2], operands[3], operands[4])
            Opcode.GET_INDEX -> CmdGetIndex(operands[0], operands[1], operands[2])
            Opcode.SET_INDEX -> CmdSetIndex(operands[0], operands[1], operands[2])
            Opcode.LIST_LITERAL -> CmdListLiteral(operands[0], operands[1], operands[2], operands[3])
            Opcode.GET_MEMBER_SLOT -> CmdGetMemberSlot(operands[0], operands[1], operands[2], operands[3])
            Opcode.SET_MEMBER_SLOT -> CmdSetMemberSlot(operands[0], operands[1], operands[2], operands[3])
            Opcode.GET_CLASS_SCOPE -> CmdGetClassScope(operands[0], operands[1], operands[2])
            Opcode.SET_CLASS_SCOPE -> CmdSetClassScope(operands[0], operands[1], operands[2])
            Opcode.GET_DYNAMIC_MEMBER -> CmdGetDynamicMember(operands[0], operands[1], operands[2])
            Opcode.SET_DYNAMIC_MEMBER -> CmdSetDynamicMember(operands[0], operands[1], operands[2])
            Opcode.ITER_PUSH -> CmdIterPush(operands[0])
            Opcode.ITER_POP -> CmdIterPop()
            Opcode.ITER_CANCEL -> CmdIterCancel()
        }
    }
}
