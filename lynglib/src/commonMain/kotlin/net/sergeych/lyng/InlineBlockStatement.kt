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

package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjVoid

class InlineBlockStatement(
    private val statements: List<Statement>,
    private val startPos: Pos,
) : Statement() {
    override val pos: Pos = startPos

    override suspend fun execute(scope: Scope): Obj {
        var last: Obj = ObjVoid
        for (stmt in statements) {
            last = requireBytecodeBody(scope, stmt, "inline block").execute(scope)
        }
        return last
    }

    fun statements(): List<Statement> = statements

    private suspend fun requireBytecodeBody(scope: Scope, stmt: Statement, label: String): net.sergeych.lyng.bytecode.BytecodeStatement {
        val bytecode = when (stmt) {
            is net.sergeych.lyng.bytecode.BytecodeStatement -> stmt
            is BytecodeBodyProvider -> stmt.bytecodeBody()
            else -> null
        }
        return bytecode ?: scope.raiseIllegalState("$label requires bytecode statement")
    }
}
