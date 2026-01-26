/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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

class BlockStatement(
    val block: Script,
    val slotPlan: Map<String, Int>,
    private val startPos: Pos,
) : Statement() {
    override val pos: Pos = startPos

    override suspend fun execute(scope: Scope): Obj {
        val target = if (scope.skipScopeCreation) scope else scope.createChildScope(startPos)
        if (slotPlan.isNotEmpty()) target.applySlotPlan(slotPlan)
        return block.execute(target)
    }

    fun statements(): List<Statement> = block.debugStatements()
}
