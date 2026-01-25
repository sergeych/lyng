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

class BytecodeBuilder {
    data class Instr(val op: Opcode, val operands: IntArray)

    private val instructions = mutableListOf<Instr>()
    private val constPool = mutableListOf<BytecodeConst>()

    fun addConst(c: BytecodeConst): Int {
        constPool += c
        return constPool.lastIndex
    }

    fun emit(op: Opcode, vararg operands: Int) {
        instructions += Instr(op, operands.copyOf())
    }

    fun build(name: String, localCount: Int): BytecodeFunction {
        val slotWidth = when {
            localCount < 256 -> 1
            localCount < 65536 -> 2
            else -> 4
        }
        val constIdWidth = if (constPool.size < 65536) 2 else 4
        val ipWidth = 2
        val code = ByteArrayOutput()
        for (ins in instructions) {
            code.writeU8(ins.op.code.toInt() and 0xFF)
            val kinds = operandKinds(ins.op)
            if (kinds.size != ins.operands.size) {
                error("Operand count mismatch for ${ins.op}: expected ${kinds.size}, got ${ins.operands.size}")
            }
            for (i in kinds.indices) {
                val v = ins.operands[i]
                when (kinds[i]) {
                    OperandKind.SLOT -> code.writeUInt(v, slotWidth)
                    OperandKind.CONST -> code.writeUInt(v, constIdWidth)
                    OperandKind.IP -> code.writeUInt(v, ipWidth)
                    OperandKind.COUNT -> code.writeUInt(v, 2)
                    OperandKind.ID -> code.writeUInt(v, 2)
                }
            }
        }
        return BytecodeFunction(
            name = name,
            localCount = localCount,
            slotWidth = slotWidth,
            ipWidth = ipWidth,
            constIdWidth = constIdWidth,
            constants = constPool.toList(),
            code = code.toByteArray()
        )
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

    private enum class OperandKind {
        SLOT,
        CONST,
        IP,
        COUNT,
        ID,
    }

    private class ByteArrayOutput {
        private val data = ArrayList<Byte>(256)

        fun writeU8(v: Int) {
            data.add((v and 0xFF).toByte())
        }

        fun writeUInt(v: Int, width: Int) {
            var value = v
            var remaining = width
            while (remaining-- > 0) {
                writeU8(value)
                value = value ushr 8
            }
        }

        fun toByteArray(): ByteArray = data.toByteArray()
    }
}
