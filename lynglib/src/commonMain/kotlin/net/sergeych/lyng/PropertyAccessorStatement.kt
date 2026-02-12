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
import net.sergeych.lyng.obj.ObjRecord

class PropertyAccessorStatement(
    val body: Statement,
    val argName: String?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        if (argName != null) {
            val value = scope.args.list.firstOrNull() ?: ObjNull
            val prev = scope.skipScopeCreation
            scope.skipScopeCreation = true
            return try {
                if (body is net.sergeych.lyng.bytecode.BytecodeStatement) {
                    val fn = body.bytecodeFunction()
                    val binder: suspend (CmdFrame, Arguments) -> Unit = { frame, arguments ->
                        val slotPlan = fn.localSlotPlanByName()
                        val slotIndex = slotPlan[argName]
                        val argValue = arguments.list.firstOrNull() ?: ObjNull
                        if (slotIndex != null) {
                            frame.frame.setObj(slotIndex, argValue)
                        } else if (scope.getLocalRecordDirect(argName) == null) {
                            scope.addItem(argName, true, argValue, recordType = ObjRecord.Type.Argument)
                        }
                    }
                    scope.pos = pos
                    CmdVm().execute(fn, scope, scope.args, binder)
                } else {
                    scope.addItem(argName, true, value, recordType = ObjRecord.Type.Argument)
                    body.execute(scope)
                }
            } finally {
                scope.skipScopeCreation = prev
            }
        }
        return body.execute(scope)
    }
}
