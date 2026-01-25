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

enum class Opcode(val code: Int) {
    NOP(0x00),
    MOVE_OBJ(0x01),
    MOVE_INT(0x02),
    MOVE_REAL(0x03),
    MOVE_BOOL(0x04),
    CONST_OBJ(0x05),
    CONST_INT(0x06),
    CONST_REAL(0x07),
    CONST_BOOL(0x08),
    CONST_NULL(0x09),

    INT_TO_REAL(0x10),
    REAL_TO_INT(0x11),
    BOOL_TO_INT(0x12),
    INT_TO_BOOL(0x13),

    ADD_INT(0x20),
    SUB_INT(0x21),
    MUL_INT(0x22),
    DIV_INT(0x23),
    MOD_INT(0x24),
    NEG_INT(0x25),
    INC_INT(0x26),
    DEC_INT(0x27),

    ADD_REAL(0x30),
    SUB_REAL(0x31),
    MUL_REAL(0x32),
    DIV_REAL(0x33),
    NEG_REAL(0x34),

    AND_INT(0x40),
    OR_INT(0x41),
    XOR_INT(0x42),
    SHL_INT(0x43),
    SHR_INT(0x44),
    USHR_INT(0x45),
    INV_INT(0x46),

    CMP_EQ_INT(0x50),
    CMP_NEQ_INT(0x51),
    CMP_LT_INT(0x52),
    CMP_LTE_INT(0x53),
    CMP_GT_INT(0x54),
    CMP_GTE_INT(0x55),
    CMP_EQ_REAL(0x56),
    CMP_NEQ_REAL(0x57),
    CMP_LT_REAL(0x58),
    CMP_LTE_REAL(0x59),
    CMP_GT_REAL(0x5A),
    CMP_GTE_REAL(0x5B),
    CMP_EQ_BOOL(0x5C),
    CMP_NEQ_BOOL(0x5D),

    CMP_EQ_INT_REAL(0x60),
    CMP_EQ_REAL_INT(0x61),
    CMP_LT_INT_REAL(0x62),
    CMP_LT_REAL_INT(0x63),
    CMP_LTE_INT_REAL(0x64),
    CMP_LTE_REAL_INT(0x65),
    CMP_GT_INT_REAL(0x66),
    CMP_GT_REAL_INT(0x67),
    CMP_GTE_INT_REAL(0x68),
    CMP_GTE_REAL_INT(0x69),
    CMP_NEQ_INT_REAL(0x6A),
    CMP_NEQ_REAL_INT(0x6B),
    CMP_EQ_OBJ(0x6C),
    CMP_NEQ_OBJ(0x6D),
    CMP_REF_EQ_OBJ(0x6E),
    CMP_REF_NEQ_OBJ(0x6F),

    NOT_BOOL(0x70),
    AND_BOOL(0x71),
    OR_BOOL(0x72),

    JMP(0x80),
    JMP_IF_TRUE(0x81),
    JMP_IF_FALSE(0x82),
    RET(0x83),
    RET_VOID(0x84),

    CALL_DIRECT(0x90),
    CALL_VIRTUAL(0x91),
    CALL_FALLBACK(0x92),

    GET_FIELD(0xA0),
    SET_FIELD(0xA1),
    GET_INDEX(0xA2),
    SET_INDEX(0xA3),

    EVAL_FALLBACK(0xB0),
    ;

    companion object {
        private val byCode: Map<Int, Opcode> = values().associateBy { it.code }
        fun fromCode(code: Int): Opcode? = byCode[code]
    }
}
