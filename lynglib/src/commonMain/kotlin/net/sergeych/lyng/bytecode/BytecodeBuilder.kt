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
        scopeSlotDepths: IntArray = IntArray(0),
        scopeSlotIndices: IntArray = IntArray(0),
        scopeSlotNames: Array<String?> = emptyArray()
    ): BytecodeFunction {
        val scopeSlotCount = scopeSlotDepths.size
        require(scopeSlotIndices.size == scopeSlotCount) { "scope slot mapping size mismatch" }
        require(scopeSlotNames.isEmpty() || scopeSlotNames.size == scopeSlotCount) {
            "scope slot name mapping size mismatch"
        }
        val totalSlots = localCount + scopeSlotCount
        val slotWidth = when {
            totalSlots < 256 -> 1
            totalSlots < 65536 -> 2
            else -> 4
        }
        val constIdWidth = if (constPool.size < 65536) 2 else 4
        val ipWidth = 2
        val instrOffsets = IntArray(instructions.size)
        var currentIp = 0
        for (i in instructions.indices) {
            instrOffsets[i] = currentIp
            val kinds = operandKinds(instructions[i].op)
            currentIp += 1 + kinds.sumOf { operandWidth(it, slotWidth, constIdWidth, ipWidth) }
        }
        val labelIps = mutableMapOf<Label, Int>()
        for ((label, idx) in labelPositions) {
            labelIps[label] = instrOffsets.getOrNull(idx) ?: error("Invalid label index: $idx")
        }

        val code = ByteArrayOutput()
        for (ins in instructions) {
            code.writeU8(ins.op.code and 0xFF)
            val kinds = operandKinds(ins.op)
            if (kinds.size != ins.operands.size) {
                error("Operand count mismatch for ${ins.op}: expected ${kinds.size}, got ${ins.operands.size}")
            }
            for (i in kinds.indices) {
                val operand = ins.operands[i]
                val v = when (operand) {
                    is Operand.IntVal -> operand.value
                    is Operand.LabelRef -> labelIps[operand.label]
                        ?: error("Unknown label ${operand.label.id} for ${ins.op}")
                }
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
            scopeSlotCount = scopeSlotCount,
            scopeSlotDepths = scopeSlotDepths,
            scopeSlotIndices = scopeSlotIndices,
            scopeSlotNames = if (scopeSlotNames.isEmpty()) Array(scopeSlotCount) { null } else scopeSlotNames,
            slotWidth = slotWidth,
            ipWidth = ipWidth,
            constIdWidth = constIdWidth,
            constants = constPool.toList(),
            fallbackStatements = fallbackStatements.toList(),
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

    private fun operandWidth(kind: OperandKind, slotWidth: Int, constIdWidth: Int, ipWidth: Int): Int {
        return when (kind) {
            OperandKind.SLOT -> slotWidth
            OperandKind.CONST -> constIdWidth
            OperandKind.IP -> ipWidth
            OperandKind.COUNT -> 2
            OperandKind.ID -> 2
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
