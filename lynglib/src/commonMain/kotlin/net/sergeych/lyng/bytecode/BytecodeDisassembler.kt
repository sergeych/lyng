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

object BytecodeDisassembler {
    fun disassemble(fn: BytecodeFunction): String {
        val decoder = when (fn.slotWidth) {
            1 -> Decoder8
            2 -> Decoder16
            4 -> Decoder32
            else -> error("Unsupported slot width: ${fn.slotWidth}")
        }
        val out = StringBuilder()
        val code = fn.code
        var ip = 0
        while (ip < code.size) {
            val op = decoder.readOpcode(code, ip)
            val startIp = ip
            ip += 1
            val kinds = operandKinds(op)
            val operands = ArrayList<String>(kinds.size)
            for (kind in kinds) {
                when (kind) {
                    OperandKind.SLOT -> {
                        val v = decoder.readSlot(code, ip)
                        ip += fn.slotWidth
                        operands += "s$v"
                    }
                    OperandKind.CONST -> {
                        val v = decoder.readConstId(code, ip, fn.constIdWidth)
                        ip += fn.constIdWidth
                        operands += "k$v"
                    }
                    OperandKind.IP -> {
                        val v = decoder.readIp(code, ip, fn.ipWidth)
                        ip += fn.ipWidth
                        operands += "ip$v"
                    }
                    OperandKind.COUNT -> {
                        val v = decoder.readConstId(code, ip, 2)
                        ip += 2
                        operands += "n$v"
                    }
                    OperandKind.ID -> {
                        val v = decoder.readConstId(code, ip, 2)
                        ip += 2
                        operands += "#$v"
                    }
                }
            }
            out.append(startIp).append(": ").append(op.name)
            if (operands.isNotEmpty()) {
                out.append(' ').append(operands.joinToString(", "))
            }
            out.append('\n')
        }
        return out.toString()
    }

    private enum class OperandKind {
        SLOT,
        CONST,
        IP,
        COUNT,
        ID,
    }

    private fun operandKinds(op: Opcode): List<OperandKind> {
        return when (op) {
            Opcode.NOP, Opcode.RET_VOID -> emptyList()
            Opcode.MOVE_OBJ, Opcode.MOVE_INT, Opcode.MOVE_REAL, Opcode.MOVE_BOOL,
            Opcode.INT_TO_REAL, Opcode.REAL_TO_INT, Opcode.BOOL_TO_INT, Opcode.INT_TO_BOOL,
            Opcode.NEG_INT, Opcode.NEG_REAL, Opcode.NOT_BOOL, Opcode.INV_INT ->
                listOf(OperandKind.SLOT, OperandKind.SLOT)
            Opcode.CONST_NULL ->
                listOf(OperandKind.SLOT)
            Opcode.CONST_OBJ, Opcode.CONST_INT, Opcode.CONST_REAL, Opcode.CONST_BOOL ->
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
            Opcode.CMP_GTE_INT_REAL, Opcode.CMP_GTE_REAL_INT,
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
}
