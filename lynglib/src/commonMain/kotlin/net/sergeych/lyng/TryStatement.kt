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
        return bytecodeOnly(scope, "try statement")
    }

    private fun resolveExceptionClass(scope: Scope, name: String): ObjClass {
        val rec = scope[name]
        val cls = rec?.value as? ObjClass
        if (cls != null) return cls
        if (name == "Exception") return ObjException.Root
        scope.raiseSymbolNotFound("error class does not exist or is not a class: $name")
    }
}
