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

interface BytecodeDecoder {
    fun readOpcode(code: ByteArray, ip: Int): Opcode
    fun readSlot(code: ByteArray, ip: Int): Int
    fun readConstId(code: ByteArray, ip: Int, width: Int): Int
    fun readIp(code: ByteArray, ip: Int, width: Int): Int
}

object Decoder8 : BytecodeDecoder {
    override fun readOpcode(code: ByteArray, ip: Int): Opcode =
        Opcode.fromCode(code[ip].toInt() and 0xFF) ?: error("Unknown opcode: ${code[ip]}")

    override fun readSlot(code: ByteArray, ip: Int): Int = code[ip].toInt() and 0xFF

    override fun readConstId(code: ByteArray, ip: Int, width: Int): Int =
        readUInt(code, ip, width)

    override fun readIp(code: ByteArray, ip: Int, width: Int): Int =
        readUInt(code, ip, width)
}

object Decoder16 : BytecodeDecoder {
    override fun readOpcode(code: ByteArray, ip: Int): Opcode =
        Opcode.fromCode(code[ip].toInt() and 0xFF) ?: error("Unknown opcode: ${code[ip]}")

    override fun readSlot(code: ByteArray, ip: Int): Int =
        (code[ip].toInt() and 0xFF) or ((code[ip + 1].toInt() and 0xFF) shl 8)

    override fun readConstId(code: ByteArray, ip: Int, width: Int): Int =
        readUInt(code, ip, width)

    override fun readIp(code: ByteArray, ip: Int, width: Int): Int =
        readUInt(code, ip, width)
}

object Decoder32 : BytecodeDecoder {
    override fun readOpcode(code: ByteArray, ip: Int): Opcode =
        Opcode.fromCode(code[ip].toInt() and 0xFF) ?: error("Unknown opcode: ${code[ip]}")

    override fun readSlot(code: ByteArray, ip: Int): Int = readUInt(code, ip, 4)

    override fun readConstId(code: ByteArray, ip: Int, width: Int): Int =
        readUInt(code, ip, width)

    override fun readIp(code: ByteArray, ip: Int, width: Int): Int =
        readUInt(code, ip, width)
}

private fun readUInt(code: ByteArray, ip: Int, width: Int): Int {
    var result = 0
    var shift = 0
    var idx = ip
    var remaining = width
    while (remaining-- > 0) {
        result = result or ((code[idx].toInt() and 0xFF) shl shift)
        shift += 8
        idx++
    }
    return result
}
