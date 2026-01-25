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

import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Statement
import net.sergeych.lyng.obj.Obj

class BytecodeStatement private constructor(
    val original: Statement,
    private val function: BytecodeFunction,
) : Statement(original.isStaticConst, original.isConst, original.returnType) {
    override val pos: Pos = original.pos

    override suspend fun execute(scope: Scope): Obj {
        return BytecodeVm().execute(function, scope, emptyList())
    }

    companion object {
        fun wrap(statement: Statement, nameHint: String, allowLocalSlots: Boolean): Statement {
            if (statement is BytecodeStatement) return statement
            val compiler = BytecodeCompiler(allowLocalSlots = allowLocalSlots)
            val compiled = compiler.compileStatement(nameHint, statement)
            val fn = compiled ?: run {
                val builder = BytecodeBuilder()
                val slot = 0
                val id = builder.addFallback(statement)
                builder.emit(Opcode.EVAL_FALLBACK, id, slot)
                builder.emit(Opcode.RET, slot)
                builder.build(nameHint, localCount = 1)
            }
            return BytecodeStatement(statement, fn)
        }
    }
}
