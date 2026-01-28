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

object CmdDisassembler {
    fun disassemble(fn: CmdFunction): String {
        val out = StringBuilder()
        val cmds = fn.cmds
        for (i in cmds.indices) {
            val (op, opValues) = opAndOperands(fn, cmds[i])
            val kinds = operandKinds(op)
            val operands = ArrayList<String>(kinds.size)
            for (k in kinds.indices) {
                val v = opValues.getOrElse(k) { 0 }
                when (kinds[k]) {
                    OperandKind.SLOT -> {
                        val name = when {
                            v < fn.scopeSlotCount -> fn.scopeSlotNames[v]
                            else -> {
                                val localIndex = v - fn.scopeSlotCount
                                fn.localSlotNames.getOrNull(localIndex)
                            }
                        }
                        operands += if (name != null) "s$v($name)" else "s$v"
                    }
                    OperandKind.ADDR -> operands += "a$v"
                    OperandKind.CONST -> operands += "k$v"
                    OperandKind.IP -> operands += "ip$v"
                    OperandKind.COUNT -> operands += "n$v"
                    OperandKind.ID -> operands += "#$v"
                }
            }
            out.append(i).append(": ").append(op.name)
            if (operands.isNotEmpty()) {
                out.append(' ').append(operands.joinToString(", "))
            }
            out.append('\n')
        }
        return out.toString()
    }

    private fun opAndOperands(fn: CmdFunction, cmd: Cmd): Pair<Opcode, IntArray> {
        return when (cmd) {
            is CmdNop -> Opcode.NOP to intArrayOf()
            is CmdMoveObj -> Opcode.MOVE_OBJ to intArrayOf(cmd.src, cmd.dst)
            is CmdMoveInt -> Opcode.MOVE_INT to intArrayOf(cmd.src, cmd.dst)
            is CmdMoveIntLocal -> Opcode.MOVE_INT to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdMoveReal -> Opcode.MOVE_REAL to intArrayOf(cmd.src, cmd.dst)
            is CmdMoveBool -> Opcode.MOVE_BOOL to intArrayOf(cmd.src, cmd.dst)
            is CmdConstObj -> Opcode.CONST_OBJ to intArrayOf(cmd.constId, cmd.dst)
            is CmdConstInt -> Opcode.CONST_INT to intArrayOf(cmd.constId, cmd.dst)
            is CmdConstIntLocal -> Opcode.CONST_INT to intArrayOf(cmd.constId, cmd.dst + fn.scopeSlotCount)
            is CmdConstReal -> Opcode.CONST_REAL to intArrayOf(cmd.constId, cmd.dst)
            is CmdConstBool -> Opcode.CONST_BOOL to intArrayOf(cmd.constId, cmd.dst)
            is CmdConstNull -> Opcode.CONST_NULL to intArrayOf(cmd.dst)
            is CmdBoxObj -> Opcode.BOX_OBJ to intArrayOf(cmd.src, cmd.dst)
            is CmdObjToBool -> Opcode.OBJ_TO_BOOL to intArrayOf(cmd.src, cmd.dst)
            is CmdCheckIs -> Opcode.CHECK_IS to intArrayOf(cmd.objSlot, cmd.typeSlot, cmd.dst)
            is CmdAssertIs -> Opcode.ASSERT_IS to intArrayOf(cmd.objSlot, cmd.typeSlot)
            is CmdRangeIntBounds -> Opcode.RANGE_INT_BOUNDS to intArrayOf(cmd.src, cmd.startSlot, cmd.endSlot, cmd.okSlot)
            is CmdResolveScopeSlot -> Opcode.RESOLVE_SCOPE_SLOT to intArrayOf(cmd.scopeSlot, cmd.addrSlot)
            is CmdLoadObjAddr -> Opcode.LOAD_OBJ_ADDR to intArrayOf(cmd.addrSlot, cmd.dst)
            is CmdStoreObjAddr -> Opcode.STORE_OBJ_ADDR to intArrayOf(cmd.src, cmd.addrSlot)
            is CmdLoadIntAddr -> Opcode.LOAD_INT_ADDR to intArrayOf(cmd.addrSlot, cmd.dst)
            is CmdStoreIntAddr -> Opcode.STORE_INT_ADDR to intArrayOf(cmd.src, cmd.addrSlot)
            is CmdLoadRealAddr -> Opcode.LOAD_REAL_ADDR to intArrayOf(cmd.addrSlot, cmd.dst)
            is CmdStoreRealAddr -> Opcode.STORE_REAL_ADDR to intArrayOf(cmd.src, cmd.addrSlot)
            is CmdLoadBoolAddr -> Opcode.LOAD_BOOL_ADDR to intArrayOf(cmd.addrSlot, cmd.dst)
            is CmdStoreBoolAddr -> Opcode.STORE_BOOL_ADDR to intArrayOf(cmd.src, cmd.addrSlot)
            is CmdIntToReal -> Opcode.INT_TO_REAL to intArrayOf(cmd.src, cmd.dst)
            is CmdRealToInt -> Opcode.REAL_TO_INT to intArrayOf(cmd.src, cmd.dst)
            is CmdBoolToInt -> Opcode.BOOL_TO_INT to intArrayOf(cmd.src, cmd.dst)
            is CmdIntToBool -> Opcode.INT_TO_BOOL to intArrayOf(cmd.src, cmd.dst)
            is CmdAddInt -> Opcode.ADD_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdAddIntLocal -> Opcode.ADD_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdSubInt -> Opcode.SUB_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdSubIntLocal -> Opcode.SUB_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdMulInt -> Opcode.MUL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdMulIntLocal -> Opcode.MUL_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdDivInt -> Opcode.DIV_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdDivIntLocal -> Opcode.DIV_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdModInt -> Opcode.MOD_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdModIntLocal -> Opcode.MOD_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdNegInt -> Opcode.NEG_INT to intArrayOf(cmd.src, cmd.dst)
            is CmdIncInt -> Opcode.INC_INT to intArrayOf(cmd.slot)
            is CmdIncIntLocal -> Opcode.INC_INT to intArrayOf(cmd.slot + fn.scopeSlotCount)
            is CmdDecInt -> Opcode.DEC_INT to intArrayOf(cmd.slot)
            is CmdDecIntLocal -> Opcode.DEC_INT to intArrayOf(cmd.slot + fn.scopeSlotCount)
            is CmdAddReal -> Opcode.ADD_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdSubReal -> Opcode.SUB_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdMulReal -> Opcode.MUL_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdDivReal -> Opcode.DIV_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdNegReal -> Opcode.NEG_REAL to intArrayOf(cmd.src, cmd.dst)
            is CmdAndInt -> Opcode.AND_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdOrInt -> Opcode.OR_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdXorInt -> Opcode.XOR_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdShlInt -> Opcode.SHL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdShrInt -> Opcode.SHR_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdUshrInt -> Opcode.USHR_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdInvInt -> Opcode.INV_INT to intArrayOf(cmd.src, cmd.dst)
            is CmdCmpEqInt -> Opcode.CMP_EQ_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqIntLocal -> Opcode.CMP_EQ_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpNeqInt -> Opcode.CMP_NEQ_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqIntLocal -> Opcode.CMP_NEQ_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpLtInt -> Opcode.CMP_LT_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLtIntLocal -> Opcode.CMP_LT_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpLteInt -> Opcode.CMP_LTE_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteIntLocal -> Opcode.CMP_LTE_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpGtInt -> Opcode.CMP_GT_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtIntLocal -> Opcode.CMP_GT_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpGteInt -> Opcode.CMP_GTE_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteIntLocal -> Opcode.CMP_GTE_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpEqReal -> Opcode.CMP_EQ_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqReal -> Opcode.CMP_NEQ_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLtReal -> Opcode.CMP_LT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteReal -> Opcode.CMP_LTE_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtReal -> Opcode.CMP_GT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteReal -> Opcode.CMP_GTE_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqBool -> Opcode.CMP_EQ_BOOL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqBool -> Opcode.CMP_NEQ_BOOL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqIntReal -> Opcode.CMP_EQ_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqRealInt -> Opcode.CMP_EQ_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLtIntReal -> Opcode.CMP_LT_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLtRealInt -> Opcode.CMP_LT_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteIntReal -> Opcode.CMP_LTE_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteRealInt -> Opcode.CMP_LTE_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtIntReal -> Opcode.CMP_GT_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtRealInt -> Opcode.CMP_GT_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteIntReal -> Opcode.CMP_GTE_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteRealInt -> Opcode.CMP_GTE_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqIntReal -> Opcode.CMP_NEQ_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqRealInt -> Opcode.CMP_NEQ_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqObj -> Opcode.CMP_EQ_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqObj -> Opcode.CMP_NEQ_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpRefEqObj -> Opcode.CMP_REF_EQ_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpRefNeqObj -> Opcode.CMP_REF_NEQ_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdNotBool -> Opcode.NOT_BOOL to intArrayOf(cmd.src, cmd.dst)
            is CmdAndBool -> Opcode.AND_BOOL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdOrBool -> Opcode.OR_BOOL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLtObj -> Opcode.CMP_LT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteObj -> Opcode.CMP_LTE_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtObj -> Opcode.CMP_GT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteObj -> Opcode.CMP_GTE_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdAddObj -> Opcode.ADD_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdSubObj -> Opcode.SUB_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdMulObj -> Opcode.MUL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdDivObj -> Opcode.DIV_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdModObj -> Opcode.MOD_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdContainsObj -> Opcode.CONTAINS_OBJ to intArrayOf(cmd.target, cmd.value, cmd.dst)
            is CmdJmp -> Opcode.JMP to intArrayOf(cmd.target)
            is CmdJmpIfTrue -> Opcode.JMP_IF_TRUE to intArrayOf(cmd.cond, cmd.target)
            is CmdJmpIfFalse -> Opcode.JMP_IF_FALSE to intArrayOf(cmd.cond, cmd.target)
            is CmdRet -> Opcode.RET to intArrayOf(cmd.slot)
            is CmdRetLabel -> Opcode.RET_LABEL to intArrayOf(cmd.labelId, cmd.slot)
            is CmdRetVoid -> Opcode.RET_VOID to intArrayOf()
            is CmdThrow -> Opcode.THROW to intArrayOf(cmd.posId, cmd.slot)
            is CmdPushScope -> Opcode.PUSH_SCOPE to intArrayOf(cmd.planId)
            is CmdPopScope -> Opcode.POP_SCOPE to intArrayOf()
            is CmdPushSlotPlan -> Opcode.PUSH_SLOT_PLAN to intArrayOf(cmd.planId)
            is CmdPopSlotPlan -> Opcode.POP_SLOT_PLAN to intArrayOf()
            is CmdDeclLocal -> Opcode.DECL_LOCAL to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclExtProperty -> Opcode.DECL_EXT_PROPERTY to intArrayOf(cmd.constId, cmd.slot)
            is CmdCallDirect -> Opcode.CALL_DIRECT to intArrayOf(cmd.id, cmd.argBase, cmd.argCount, cmd.dst)
            is CmdCallVirtual -> Opcode.CALL_VIRTUAL to intArrayOf(cmd.recvSlot, cmd.methodId, cmd.argBase, cmd.argCount, cmd.dst)
            is CmdCallFallback -> Opcode.CALL_FALLBACK to intArrayOf(cmd.id, cmd.argBase, cmd.argCount, cmd.dst)
            is CmdCallSlot -> Opcode.CALL_SLOT to intArrayOf(cmd.calleeSlot, cmd.argBase, cmd.argCount, cmd.dst)
            is CmdGetField -> Opcode.GET_FIELD to intArrayOf(cmd.recvSlot, cmd.fieldId, cmd.dst)
            is CmdSetField -> Opcode.SET_FIELD to intArrayOf(cmd.recvSlot, cmd.fieldId, cmd.valueSlot)
            is CmdGetName -> Opcode.GET_NAME to intArrayOf(cmd.nameId, cmd.dst)
            is CmdGetIndex -> Opcode.GET_INDEX to intArrayOf(cmd.targetSlot, cmd.indexSlot, cmd.dst)
            is CmdSetIndex -> Opcode.SET_INDEX to intArrayOf(cmd.targetSlot, cmd.indexSlot, cmd.valueSlot)
            is CmdEvalFallback -> Opcode.EVAL_FALLBACK to intArrayOf(cmd.id, cmd.dst)
            is CmdEvalRef -> Opcode.EVAL_REF to intArrayOf(cmd.id, cmd.dst)
            is CmdEvalStmt -> Opcode.EVAL_STMT to intArrayOf(cmd.id, cmd.dst)
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

    private fun operandKinds(op: Opcode): List<OperandKind> {
        return when (op) {
            Opcode.NOP, Opcode.RET_VOID, Opcode.POP_SCOPE, Opcode.POP_SLOT_PLAN -> emptyList()
            Opcode.MOVE_OBJ, Opcode.MOVE_INT, Opcode.MOVE_REAL, Opcode.MOVE_BOOL, Opcode.BOX_OBJ,
            Opcode.INT_TO_REAL, Opcode.REAL_TO_INT, Opcode.BOOL_TO_INT, Opcode.INT_TO_BOOL,
            Opcode.NEG_INT, Opcode.NEG_REAL, Opcode.NOT_BOOL, Opcode.INV_INT ->
                listOf(OperandKind.SLOT, OperandKind.SLOT)
            Opcode.OBJ_TO_BOOL, Opcode.ASSERT_IS ->
                listOf(OperandKind.SLOT, OperandKind.SLOT)
            Opcode.CHECK_IS ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
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
            Opcode.DECL_LOCAL, Opcode.DECL_EXT_PROPERTY ->
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
            Opcode.ADD_OBJ, Opcode.SUB_OBJ, Opcode.MUL_OBJ, Opcode.DIV_OBJ, Opcode.MOD_OBJ, Opcode.CONTAINS_OBJ,
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
            Opcode.GET_NAME ->
                listOf(OperandKind.ID, OperandKind.SLOT)
            Opcode.GET_INDEX ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.SET_INDEX ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.EVAL_FALLBACK, Opcode.EVAL_REF, Opcode.EVAL_STMT ->
                listOf(OperandKind.ID, OperandKind.SLOT)
        }
    }
}
