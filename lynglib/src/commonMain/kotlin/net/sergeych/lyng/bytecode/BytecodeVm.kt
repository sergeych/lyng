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

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjVoid

class BytecodeVm {
    suspend fun execute(fn: BytecodeFunction, scope: Scope, args: List<Obj>): Obj {
        val frame = BytecodeFrame(fn.localCount, args.size)
        for (i in args.indices) {
            frame.setObj(frame.argBase + i, args[i])
        }
        val decoder = when (fn.slotWidth) {
            1 -> Decoder8
            2 -> Decoder16
            4 -> Decoder32
            else -> error("Unsupported slot width: ${fn.slotWidth}")
        }
        var ip = 0
        val code = fn.code
        while (ip < code.size) {
            val op = decoder.readOpcode(code, ip)
            ip += 1
            when (op) {
                Opcode.NOP -> {
                    // no-op
                }
                Opcode.RET_VOID -> return ObjVoid
                else -> error("Opcode not implemented: $op")
            }
        }
        return ObjVoid
    }
}
