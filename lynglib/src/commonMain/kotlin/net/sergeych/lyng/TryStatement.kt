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
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjUnknownException
import net.sergeych.lyng.obj.ObjVoid

class TryStatement(
    val body: Statement,
    val catches: List<CatchBlock>,
    val finallyClause: Statement?,
    private val startPos: Pos,
) : Statement() {
    override val pos: Pos = startPos

    data class CatchBlock(
        val catchVarName: String,
        val catchVarPos: Pos,
        val classNames: List<String>,
        val block: Statement
    )

    override suspend fun execute(scope: Scope): Obj {
        var result: Obj = ObjVoid
        try {
            result = body.execute(scope)
        } catch (e: ReturnException) {
            throw e
        } catch (e: LoopBreakContinueException) {
            throw e
        } catch (e: Exception) {
            val caughtObj = when (e) {
                is ExecutionError -> e.errorObject
                else -> ObjUnknownException(scope, e.message ?: e.toString())
            }
            var isCaught = false
            for (cdata in catches) {
                var match: Obj? = null
                for (exceptionClassName in cdata.classNames) {
                    val exObj = resolveExceptionClass(scope, exceptionClassName)
                    if (caughtObj.isInstanceOf(exObj)) {
                        match = caughtObj
                        break
                    }
                }
                if (match != null) {
                    val catchContext = scope.createChildScope(pos = cdata.catchVarPos).apply {
                        skipScopeCreation = true
                    }
                    catchContext.addItem(cdata.catchVarName, false, caughtObj)
                    result = cdata.block.execute(catchContext)
                    isCaught = true
                    break
                }
            }
            if (!isCaught) throw e
        } finally {
            finallyClause?.execute(scope)
        }
        return result
    }

    private fun resolveExceptionClass(scope: Scope, name: String): ObjClass {
        val rec = scope[name]
        val cls = rec?.value as? ObjClass
        if (cls != null) return cls
        if (name == "Exception") return ObjException.Root
        scope.raiseSymbolNotFound("error class does not exist or is not a class: $name")
    }
}
