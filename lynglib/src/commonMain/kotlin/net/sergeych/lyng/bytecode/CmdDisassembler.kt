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
        val captureConsts = fn.constants.withIndex().mapNotNull { (idx, constVal) ->
            val table = constVal as? BytecodeConst.CaptureTable ?: return@mapNotNull null
            idx to table
        }
        if (captureConsts.isNotEmpty()) {
            out.append("consts:\n")
            for ((idx, table) in captureConsts) {
                val entries = if (table.entries.isEmpty()) {
                    "[]"
                } else {
                    table.entries.joinToString(prefix = "[", postfix = "]") { entry ->
                        "${entry.ownerKind}#${entry.ownerScopeId}:${entry.ownerSlotId}@s${entry.slotIndex}"
                    }
                }
                out.append("k").append(idx).append(" CAPTURE_TABLE ").append(entries).append('\n')
            }
        }
        return out.toString()
    }

    private fun opAndOperands(fn: CmdFunction, cmd: Cmd): Pair<Opcode, IntArray> {
        return when (cmd) {
            is CmdNop -> Opcode.NOP to intArrayOf()
            is CmdMoveObj -> Opcode.MOVE_OBJ to intArrayOf(cmd.src, cmd.dst)
            is CmdMoveInt -> Opcode.MOVE_INT to intArrayOf(cmd.src, cmd.dst)
            is CmdMoveIntLocal -> Opcode.MOVE_INT to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdMoveRealLocal -> Opcode.MOVE_REAL to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdMoveBoolLocal -> Opcode.MOVE_BOOL to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdMoveReal -> Opcode.MOVE_REAL to intArrayOf(cmd.src, cmd.dst)
            is CmdMoveBool -> Opcode.MOVE_BOOL to intArrayOf(cmd.src, cmd.dst)
            is CmdConstObj -> Opcode.CONST_OBJ to intArrayOf(cmd.constId, cmd.dst)
            is CmdConstInt -> Opcode.CONST_INT to intArrayOf(cmd.constId, cmd.dst)
            is CmdConstIntLocal -> Opcode.CONST_INT to intArrayOf(cmd.constId, cmd.dst + fn.scopeSlotCount)
            is CmdConstReal -> Opcode.CONST_REAL to intArrayOf(cmd.constId, cmd.dst)
            is CmdConstBool -> Opcode.CONST_BOOL to intArrayOf(cmd.constId, cmd.dst)
            is CmdLoadThis -> Opcode.LOAD_THIS to intArrayOf(cmd.dst)
            is CmdLoadThisVariant -> Opcode.LOAD_THIS_VARIANT to intArrayOf(cmd.typeId, cmd.dst)
            is CmdConstNull -> Opcode.CONST_NULL to intArrayOf(cmd.dst)
            is CmdMakeLambda -> Opcode.MAKE_LAMBDA_FN to intArrayOf(cmd.id, cmd.dst)
            is CmdBoxObj -> Opcode.BOX_OBJ to intArrayOf(cmd.src, cmd.dst)
            is CmdObjToBool -> Opcode.OBJ_TO_BOOL to intArrayOf(cmd.src, cmd.dst)
            is CmdGetObjClass -> Opcode.GET_OBJ_CLASS to intArrayOf(cmd.src, cmd.dst)
            is CmdCheckIs -> Opcode.CHECK_IS to intArrayOf(cmd.objSlot, cmd.typeSlot, cmd.dst)
            is CmdAssertIs -> Opcode.ASSERT_IS to intArrayOf(cmd.objSlot, cmd.typeSlot)
            is CmdMakeQualifiedView -> Opcode.MAKE_QUALIFIED_VIEW to intArrayOf(cmd.objSlot, cmd.typeSlot, cmd.dst)
            is CmdRangeIntBounds -> Opcode.RANGE_INT_BOUNDS to intArrayOf(cmd.src, cmd.startSlot, cmd.endSlot, cmd.descendingSlot, cmd.okSlot)
            is CmdMakeRange -> Opcode.MAKE_RANGE to intArrayOf(
                cmd.startSlot,
                cmd.endSlot,
                cmd.inclusiveSlot,
                cmd.descendingSlot,
                cmd.stepSlot,
                cmd.dst
            )
            is CmdResolveScopeSlot -> Opcode.RESOLVE_SCOPE_SLOT to intArrayOf(cmd.scopeSlot, cmd.addrSlot)
            is CmdDelegatedGetLocal -> Opcode.DELEGATED_GET_LOCAL to intArrayOf(cmd.delegateSlot, cmd.nameId, cmd.dst)
            is CmdDelegatedSetLocal -> Opcode.DELEGATED_SET_LOCAL to intArrayOf(cmd.delegateSlot, cmd.nameId, cmd.valueSlot)
            is CmdBindDelegateLocal -> Opcode.BIND_DELEGATE_LOCAL to intArrayOf(cmd.delegateSlot, cmd.nameId, cmd.accessId, cmd.dst)
            is CmdLoadObjAddr -> Opcode.LOAD_OBJ_ADDR to intArrayOf(cmd.addrSlot, cmd.dst)
            is CmdStoreObjAddr -> Opcode.STORE_OBJ_ADDR to intArrayOf(cmd.src, cmd.addrSlot)
            is CmdLoadIntAddr -> Opcode.LOAD_INT_ADDR to intArrayOf(cmd.addrSlot, cmd.dst)
            is CmdStoreIntAddr -> Opcode.STORE_INT_ADDR to intArrayOf(cmd.src, cmd.addrSlot)
            is CmdLoadRealAddr -> Opcode.LOAD_REAL_ADDR to intArrayOf(cmd.addrSlot, cmd.dst)
            is CmdStoreRealAddr -> Opcode.STORE_REAL_ADDR to intArrayOf(cmd.src, cmd.addrSlot)
            is CmdLoadBoolAddr -> Opcode.LOAD_BOOL_ADDR to intArrayOf(cmd.addrSlot, cmd.dst)
            is CmdStoreBoolAddr -> Opcode.STORE_BOOL_ADDR to intArrayOf(cmd.src, cmd.addrSlot)
            is CmdIntToReal -> Opcode.INT_TO_REAL to intArrayOf(cmd.src, cmd.dst)
            is CmdIntToRealLocal -> Opcode.INT_TO_REAL to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdRealToInt -> Opcode.REAL_TO_INT to intArrayOf(cmd.src, cmd.dst)
            is CmdRealToIntLocal -> Opcode.REAL_TO_INT to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdBoolToInt -> Opcode.BOOL_TO_INT to intArrayOf(cmd.src, cmd.dst)
            is CmdBoolToIntLocal -> Opcode.BOOL_TO_INT to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdIntToBool -> Opcode.INT_TO_BOOL to intArrayOf(cmd.src, cmd.dst)
            is CmdIntToBoolLocal -> Opcode.INT_TO_BOOL to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
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
            is CmdNegIntLocal -> Opcode.NEG_INT to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdIncInt -> Opcode.INC_INT to intArrayOf(cmd.slot)
            is CmdIncIntLocal -> Opcode.INC_INT to intArrayOf(cmd.slot + fn.scopeSlotCount)
            is CmdDecInt -> Opcode.DEC_INT to intArrayOf(cmd.slot)
            is CmdDecIntLocal -> Opcode.DEC_INT to intArrayOf(cmd.slot + fn.scopeSlotCount)
            is CmdAddReal -> Opcode.ADD_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdAddRealLocal -> Opcode.ADD_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdSubReal -> Opcode.SUB_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdSubRealLocal -> Opcode.SUB_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdMulReal -> Opcode.MUL_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdMulRealLocal -> Opcode.MUL_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdDivReal -> Opcode.DIV_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdDivRealLocal -> Opcode.DIV_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdNegReal -> Opcode.NEG_REAL to intArrayOf(cmd.src, cmd.dst)
            is CmdNegRealLocal -> Opcode.NEG_REAL to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdAndInt -> Opcode.AND_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdAndIntLocal -> Opcode.AND_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdOrInt -> Opcode.OR_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdOrIntLocal -> Opcode.OR_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdXorInt -> Opcode.XOR_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdXorIntLocal -> Opcode.XOR_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdShlInt -> Opcode.SHL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdShlIntLocal -> Opcode.SHL_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdShrInt -> Opcode.SHR_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdShrIntLocal -> Opcode.SHR_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdUshrInt -> Opcode.USHR_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdUshrIntLocal -> Opcode.USHR_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdInvInt -> Opcode.INV_INT to intArrayOf(cmd.src, cmd.dst)
            is CmdInvIntLocal -> Opcode.INV_INT to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
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
            is CmdCmpEqRealLocal -> Opcode.CMP_EQ_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpNeqReal -> Opcode.CMP_NEQ_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqRealLocal -> Opcode.CMP_NEQ_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpLtReal -> Opcode.CMP_LT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLtRealLocal -> Opcode.CMP_LT_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpLteReal -> Opcode.CMP_LTE_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteRealLocal -> Opcode.CMP_LTE_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpGtReal -> Opcode.CMP_GT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtRealLocal -> Opcode.CMP_GT_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpGteReal -> Opcode.CMP_GTE_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteRealLocal -> Opcode.CMP_GTE_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpEqBool -> Opcode.CMP_EQ_BOOL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqBoolLocal -> Opcode.CMP_EQ_BOOL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpNeqBool -> Opcode.CMP_NEQ_BOOL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqBoolLocal -> Opcode.CMP_NEQ_BOOL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpEqIntReal -> Opcode.CMP_EQ_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqIntRealLocal -> Opcode.CMP_EQ_INT_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpEqRealInt -> Opcode.CMP_EQ_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqRealIntLocal -> Opcode.CMP_EQ_REAL_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpLtIntReal -> Opcode.CMP_LT_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLtIntRealLocal -> Opcode.CMP_LT_INT_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpLtRealInt -> Opcode.CMP_LT_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLtRealIntLocal -> Opcode.CMP_LT_REAL_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpLteIntReal -> Opcode.CMP_LTE_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteIntRealLocal -> Opcode.CMP_LTE_INT_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpLteRealInt -> Opcode.CMP_LTE_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteRealIntLocal -> Opcode.CMP_LTE_REAL_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpGtIntReal -> Opcode.CMP_GT_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtIntRealLocal -> Opcode.CMP_GT_INT_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpGtRealInt -> Opcode.CMP_GT_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtRealIntLocal -> Opcode.CMP_GT_REAL_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpGteIntReal -> Opcode.CMP_GTE_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteIntRealLocal -> Opcode.CMP_GTE_INT_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpGteRealInt -> Opcode.CMP_GTE_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteRealIntLocal -> Opcode.CMP_GTE_REAL_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpNeqIntReal -> Opcode.CMP_NEQ_INT_REAL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqIntRealLocal -> Opcode.CMP_NEQ_INT_REAL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdCmpNeqRealInt -> Opcode.CMP_NEQ_REAL_INT to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqRealIntLocal -> Opcode.CMP_NEQ_REAL_INT to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdJmpIfEqInt -> Opcode.JMP_IF_EQ_INT to intArrayOf(cmd.a, cmd.b, cmd.target)
            is CmdJmpIfEqIntLocal -> Opcode.JMP_IF_EQ_INT to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.target
            )
            is CmdJmpIfNeqInt -> Opcode.JMP_IF_NEQ_INT to intArrayOf(cmd.a, cmd.b, cmd.target)
            is CmdJmpIfNeqIntLocal -> Opcode.JMP_IF_NEQ_INT to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.target
            )
            is CmdJmpIfLtInt -> Opcode.JMP_IF_LT_INT to intArrayOf(cmd.a, cmd.b, cmd.target)
            is CmdJmpIfLtIntLocal -> Opcode.JMP_IF_LT_INT to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.target
            )
            is CmdJmpIfLteInt -> Opcode.JMP_IF_LTE_INT to intArrayOf(cmd.a, cmd.b, cmd.target)
            is CmdJmpIfLteIntLocal -> Opcode.JMP_IF_LTE_INT to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.target
            )
            is CmdJmpIfGtInt -> Opcode.JMP_IF_GT_INT to intArrayOf(cmd.a, cmd.b, cmd.target)
            is CmdJmpIfGtIntLocal -> Opcode.JMP_IF_GT_INT to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.target
            )
            is CmdJmpIfGteInt -> Opcode.JMP_IF_GTE_INT to intArrayOf(cmd.a, cmd.b, cmd.target)
            is CmdJmpIfGteIntLocal -> Opcode.JMP_IF_GTE_INT to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.target
            )
            is CmdCmpEqObj -> Opcode.CMP_EQ_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqObj -> Opcode.CMP_NEQ_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpRefEqObj -> Opcode.CMP_REF_EQ_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpRefNeqObj -> Opcode.CMP_REF_NEQ_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqStr -> Opcode.CMP_EQ_STR to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqStrLocal -> Opcode.CMP_EQ_STR to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpNeqStr -> Opcode.CMP_NEQ_STR to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqStrLocal -> Opcode.CMP_NEQ_STR to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpLtStr -> Opcode.CMP_LT_STR to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLtStrLocal -> Opcode.CMP_LT_STR to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpLteStr -> Opcode.CMP_LTE_STR to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteStrLocal -> Opcode.CMP_LTE_STR to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpGtStr -> Opcode.CMP_GT_STR to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtStrLocal -> Opcode.CMP_GT_STR to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpGteStr -> Opcode.CMP_GTE_STR to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteStrLocal -> Opcode.CMP_GTE_STR to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpEqIntObj -> Opcode.CMP_EQ_INT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqIntObjLocal -> Opcode.CMP_EQ_INT_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpNeqIntObj -> Opcode.CMP_NEQ_INT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqIntObjLocal -> Opcode.CMP_NEQ_INT_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpLtIntObj -> Opcode.CMP_LT_INT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLtIntObjLocal -> Opcode.CMP_LT_INT_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpLteIntObj -> Opcode.CMP_LTE_INT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteIntObjLocal -> Opcode.CMP_LTE_INT_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpGtIntObj -> Opcode.CMP_GT_INT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtIntObjLocal -> Opcode.CMP_GT_INT_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpGteIntObj -> Opcode.CMP_GTE_INT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteIntObjLocal -> Opcode.CMP_GTE_INT_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpEqRealObj -> Opcode.CMP_EQ_REAL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpEqRealObjLocal -> Opcode.CMP_EQ_REAL_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpNeqRealObj -> Opcode.CMP_NEQ_REAL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpNeqRealObjLocal -> Opcode.CMP_NEQ_REAL_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpLtRealObj -> Opcode.CMP_LT_REAL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLtRealObjLocal -> Opcode.CMP_LT_REAL_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpLteRealObj -> Opcode.CMP_LTE_REAL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteRealObjLocal -> Opcode.CMP_LTE_REAL_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpGtRealObj -> Opcode.CMP_GT_REAL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtRealObjLocal -> Opcode.CMP_GT_REAL_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpGteRealObj -> Opcode.CMP_GTE_REAL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteRealObjLocal -> Opcode.CMP_GTE_REAL_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdNotBool -> Opcode.NOT_BOOL to intArrayOf(cmd.src, cmd.dst)
            is CmdNotBoolLocal -> Opcode.NOT_BOOL to intArrayOf(cmd.src + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdAndBool -> Opcode.AND_BOOL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdAndBoolLocal -> Opcode.AND_BOOL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdOrBool -> Opcode.OR_BOOL to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdOrBoolLocal -> Opcode.OR_BOOL to intArrayOf(cmd.a + fn.scopeSlotCount, cmd.b + fn.scopeSlotCount, cmd.dst + fn.scopeSlotCount)
            is CmdUnboxIntObj -> Opcode.UNBOX_INT_OBJ to intArrayOf(cmd.src, cmd.dst)
            is CmdUnboxIntObjLocal -> Opcode.UNBOX_INT_OBJ to intArrayOf(
                cmd.src + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdUnboxRealObj -> Opcode.UNBOX_REAL_OBJ to intArrayOf(cmd.src, cmd.dst)
            is CmdUnboxRealObjLocal -> Opcode.UNBOX_REAL_OBJ to intArrayOf(
                cmd.src + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdCmpLtObj -> Opcode.CMP_LT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpLteObj -> Opcode.CMP_LTE_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGtObj -> Opcode.CMP_GT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdCmpGteObj -> Opcode.CMP_GTE_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdAddIntObj -> Opcode.ADD_INT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdAddIntObjLocal -> Opcode.ADD_INT_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdSubIntObj -> Opcode.SUB_INT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdSubIntObjLocal -> Opcode.SUB_INT_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdMulIntObj -> Opcode.MUL_INT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdMulIntObjLocal -> Opcode.MUL_INT_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdDivIntObj -> Opcode.DIV_INT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdDivIntObjLocal -> Opcode.DIV_INT_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdModIntObj -> Opcode.MOD_INT_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdModIntObjLocal -> Opcode.MOD_INT_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdAddRealObj -> Opcode.ADD_REAL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdAddRealObjLocal -> Opcode.ADD_REAL_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdSubRealObj -> Opcode.SUB_REAL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdSubRealObjLocal -> Opcode.SUB_REAL_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdMulRealObj -> Opcode.MUL_REAL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdMulRealObjLocal -> Opcode.MUL_REAL_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdDivRealObj -> Opcode.DIV_REAL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdDivRealObjLocal -> Opcode.DIV_REAL_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdModRealObj -> Opcode.MOD_REAL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdModRealObjLocal -> Opcode.MOD_REAL_OBJ to intArrayOf(
                cmd.a + fn.scopeSlotCount,
                cmd.b + fn.scopeSlotCount,
                cmd.dst + fn.scopeSlotCount
            )
            is CmdAddObj -> Opcode.ADD_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdSubObj -> Opcode.SUB_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdMulObj -> Opcode.MUL_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdDivObj -> Opcode.DIV_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdModObj -> Opcode.MOD_OBJ to intArrayOf(cmd.a, cmd.b, cmd.dst)
            is CmdContainsObj -> Opcode.CONTAINS_OBJ to intArrayOf(cmd.target, cmd.value, cmd.dst)
            is CmdAssignOpObj -> Opcode.ASSIGN_OP_OBJ to intArrayOf(cmd.opId, cmd.targetSlot, cmd.valueSlot, cmd.dst, cmd.nameId)
            is CmdJmp -> Opcode.JMP to intArrayOf(cmd.target)
            is CmdJmpIfTrue -> Opcode.JMP_IF_TRUE to intArrayOf(cmd.cond, cmd.target)
            is CmdJmpIfTrueLocal -> Opcode.JMP_IF_TRUE to intArrayOf(cmd.cond + fn.scopeSlotCount, cmd.target)
            is CmdJmpIfFalse -> Opcode.JMP_IF_FALSE to intArrayOf(cmd.cond, cmd.target)
            is CmdJmpIfFalseLocal -> Opcode.JMP_IF_FALSE to intArrayOf(cmd.cond + fn.scopeSlotCount, cmd.target)
            is CmdRet -> Opcode.RET to intArrayOf(cmd.slot)
            is CmdRetLabel -> Opcode.RET_LABEL to intArrayOf(cmd.labelId, cmd.slot)
            is CmdRetVoid -> Opcode.RET_VOID to intArrayOf()
            is CmdThrow -> Opcode.THROW to intArrayOf(cmd.posId, cmd.slot)
            is CmdRethrowPending -> Opcode.RETHROW_PENDING to intArrayOf()
            is CmdPushScope -> Opcode.PUSH_SCOPE to intArrayOf(cmd.planId)
            is CmdPopScope -> Opcode.POP_SCOPE to intArrayOf()
            is CmdPushSlotPlan -> Opcode.PUSH_SLOT_PLAN to intArrayOf(cmd.planId)
            is CmdPopSlotPlan -> Opcode.POP_SLOT_PLAN to intArrayOf()
            is CmdPushTry -> Opcode.PUSH_TRY to intArrayOf(cmd.exceptionSlot, cmd.catchIp, cmd.finallyIp)
            is CmdPopTry -> Opcode.POP_TRY to intArrayOf()
            is CmdClearPendingThrowable -> Opcode.CLEAR_PENDING_THROWABLE to intArrayOf()
            is CmdDeclLocal -> Opcode.DECL_LOCAL to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclDelegated -> Opcode.DECL_DELEGATED to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclDestructure -> Opcode.DECL_DESTRUCTURE to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclEnum -> Opcode.DECL_ENUM to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclFunction -> Opcode.DECL_FUNCTION to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclClass -> Opcode.DECL_CLASS to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclClassField -> Opcode.DECL_CLASS_FIELD to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclClassDelegated -> Opcode.DECL_CLASS_DELEGATED to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclClassInstanceInit -> Opcode.DECL_CLASS_INSTANCE_INIT to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclClassInstanceField -> Opcode.DECL_CLASS_INSTANCE_FIELD to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclClassInstanceProperty -> Opcode.DECL_CLASS_INSTANCE_PROPERTY to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclClassInstanceDelegated -> Opcode.DECL_CLASS_INSTANCE_DELEGATED to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclInstanceField -> Opcode.DECL_INSTANCE_FIELD to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclInstanceProperty -> Opcode.DECL_INSTANCE_PROPERTY to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclInstanceDelegated -> Opcode.DECL_INSTANCE_DELEGATED to intArrayOf(cmd.constId, cmd.slot)
            is CmdDeclExtProperty -> Opcode.DECL_EXT_PROPERTY to intArrayOf(cmd.constId, cmd.slot)
            is CmdCallDirect -> Opcode.CALL_DIRECT to intArrayOf(cmd.id, cmd.argBase, cmd.argCount, cmd.dst)
            is CmdAssignDestructure -> Opcode.ASSIGN_DESTRUCTURE to intArrayOf(cmd.constId, cmd.slot)
            is CmdCallMemberSlot -> Opcode.CALL_MEMBER_SLOT to intArrayOf(cmd.recvSlot, cmd.methodId, cmd.argBase, cmd.argCount, cmd.dst)
            is CmdCallSlot -> Opcode.CALL_SLOT to intArrayOf(cmd.calleeSlot, cmd.argBase, cmd.argCount, cmd.dst)
            is CmdCallBridgeSlot -> Opcode.CALL_BRIDGE_SLOT to intArrayOf(cmd.calleeSlot, cmd.argBase, cmd.argCount, cmd.dst)
            is CmdCallDynamicMember -> Opcode.CALL_DYNAMIC_MEMBER to intArrayOf(cmd.recvSlot, cmd.nameId, cmd.argBase, cmd.argCount, cmd.dst)
            is CmdGetIndex -> Opcode.GET_INDEX to intArrayOf(cmd.targetSlot, cmd.indexSlot, cmd.dst)
            is CmdSetIndex -> Opcode.SET_INDEX to intArrayOf(cmd.targetSlot, cmd.indexSlot, cmd.valueSlot)
            is CmdGetIndexInt -> Opcode.GET_INDEX_INT to intArrayOf(cmd.targetSlot, cmd.indexSlot, cmd.dst)
            is CmdSetIndexInt -> Opcode.SET_INDEX_INT to intArrayOf(cmd.targetSlot, cmd.indexSlot, cmd.valueSlot)
            is CmdListFillInt -> Opcode.LIST_FILL_INT to intArrayOf(cmd.sizeSlot, cmd.callableSlot, cmd.dst)
            is CmdListLiteral -> Opcode.LIST_LITERAL to intArrayOf(cmd.planId, cmd.baseSlot, cmd.count, cmd.dst)
            is CmdGetMemberSlot -> Opcode.GET_MEMBER_SLOT to intArrayOf(cmd.recvSlot, cmd.fieldId, cmd.methodId, cmd.dst)
            is CmdSetMemberSlot -> Opcode.SET_MEMBER_SLOT to intArrayOf(cmd.recvSlot, cmd.fieldId, cmd.methodId, cmd.valueSlot)
            is CmdGetClassScope -> Opcode.GET_CLASS_SCOPE to intArrayOf(cmd.classSlot, cmd.nameId, cmd.dst)
            is CmdSetClassScope -> Opcode.SET_CLASS_SCOPE to intArrayOf(cmd.classSlot, cmd.nameId, cmd.valueSlot)
            is CmdGetDynamicMember -> Opcode.GET_DYNAMIC_MEMBER to intArrayOf(cmd.recvSlot, cmd.nameId, cmd.dst)
            is CmdSetDynamicMember -> Opcode.SET_DYNAMIC_MEMBER to intArrayOf(cmd.recvSlot, cmd.nameId, cmd.valueSlot)
            is CmdIterPush -> Opcode.ITER_PUSH to intArrayOf(cmd.iterSlot)
            is CmdIterPop -> Opcode.ITER_POP to intArrayOf()
            is CmdIterCancel -> Opcode.ITER_CANCEL to intArrayOf()
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
            Opcode.NOP, Opcode.RET_VOID, Opcode.POP_SCOPE, Opcode.POP_SLOT_PLAN, Opcode.POP_TRY,
            Opcode.CLEAR_PENDING_THROWABLE, Opcode.RETHROW_PENDING,
            Opcode.ITER_POP, Opcode.ITER_CANCEL -> emptyList()
            Opcode.MOVE_OBJ, Opcode.MOVE_INT, Opcode.MOVE_REAL, Opcode.MOVE_BOOL, Opcode.BOX_OBJ,
            Opcode.UNBOX_INT_OBJ, Opcode.UNBOX_REAL_OBJ,
            Opcode.INT_TO_REAL, Opcode.REAL_TO_INT, Opcode.BOOL_TO_INT, Opcode.INT_TO_BOOL,
            Opcode.NEG_INT, Opcode.NEG_REAL, Opcode.NOT_BOOL, Opcode.INV_INT ->
                listOf(OperandKind.SLOT, OperandKind.SLOT)
            Opcode.OBJ_TO_BOOL, Opcode.GET_OBJ_CLASS, Opcode.ASSERT_IS ->
                listOf(OperandKind.SLOT, OperandKind.SLOT)
            Opcode.CHECK_IS, Opcode.MAKE_QUALIFIED_VIEW ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.RANGE_INT_BOUNDS ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.MAKE_RANGE ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
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
            Opcode.INC_INT, Opcode.DEC_INT, Opcode.RET, Opcode.ITER_PUSH, Opcode.LOAD_THIS ->
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
            Opcode.CALL_SLOT, Opcode.CALL_BRIDGE_SLOT ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.COUNT, OperandKind.SLOT)
            Opcode.CALL_MEMBER_SLOT ->
                listOf(OperandKind.SLOT, OperandKind.ID, OperandKind.SLOT, OperandKind.COUNT, OperandKind.SLOT)
            Opcode.CALL_DYNAMIC_MEMBER ->
                listOf(OperandKind.SLOT, OperandKind.CONST, OperandKind.SLOT, OperandKind.COUNT, OperandKind.SLOT)
            Opcode.GET_INDEX ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.SET_INDEX ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.GET_INDEX_INT ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.SET_INDEX_INT ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
            Opcode.LIST_FILL_INT ->
                listOf(OperandKind.SLOT, OperandKind.SLOT, OperandKind.SLOT)
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
        }
    }
}
