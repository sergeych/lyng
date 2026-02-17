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

import net.sergeych.lyng.bytecode.CmdFrame
import net.sergeych.lyng.bytecode.CmdVm
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjNull

class PropertyAccessorStatement(
    val body: Statement,
    val argName: String?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        if (argName != null) {
            val prev = scope.skipScopeCreation
            scope.skipScopeCreation = true
            return try {
                val bytecodeStmt = requireBytecodeBody(scope, body, "property accessor")
                val fn = bytecodeStmt.bytecodeFunction()
                val binder: suspend (CmdFrame, Arguments) -> Unit = { frame, arguments ->
                    val slotPlan = fn.localSlotPlanByName()
                    val slotIndex = slotPlan[argName]
                        ?: scope.raiseIllegalState("property accessor argument $argName missing from slot plan")
                    val argValue = arguments.list.firstOrNull() ?: ObjNull
                    frame.frame.setObj(slotIndex, argValue)
                }
                scope.pos = pos
                CmdVm().execute(fn, scope, scope.args, binder)
            } finally {
                scope.skipScopeCreation = prev
            }
        }
        return requireBytecodeBody(scope, body, "property accessor").execute(scope)
    }

    private suspend fun requireBytecodeBody(scope: Scope, stmt: Statement, label: String): net.sergeych.lyng.bytecode.BytecodeStatement {
        val bytecode = when (stmt) {
            is net.sergeych.lyng.bytecode.BytecodeStatement -> stmt
            is BytecodeBodyProvider -> stmt.bytecodeBody()
            else -> null
        }
        return bytecode ?: scope.raiseIllegalState("$label requires bytecode statement")
    }
}
