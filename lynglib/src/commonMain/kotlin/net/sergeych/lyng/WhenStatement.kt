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

sealed class WhenCondition(open val expr: Statement, open val pos: Pos) {
    abstract suspend fun matches(scope: Scope, value: Obj): Boolean
}

class WhenEqualsCondition(
    override val expr: Statement,
    override val pos: Pos,
) : WhenCondition(expr, pos) {
    override suspend fun matches(scope: Scope, value: Obj): Boolean {
        return expr.execute(scope).compareTo(scope, value) == 0
    }
}

class WhenInCondition(
    override val expr: Statement,
    val negated: Boolean,
    override val pos: Pos,
) : WhenCondition(expr, pos) {
    override suspend fun matches(scope: Scope, value: Obj): Boolean {
        val result = expr.execute(scope).contains(scope, value)
        return if (negated) !result else result
    }
}

class WhenIsCondition(
    override val expr: Statement,
    val negated: Boolean,
    override val pos: Pos,
) : WhenCondition(expr, pos) {
    override suspend fun matches(scope: Scope, value: Obj): Boolean {
        val result = value.isInstanceOf(expr.execute(scope))
        return if (negated) !result else result
    }
}

data class WhenCase(val conditions: List<WhenCondition>, val block: Statement)

class WhenStatement(
    val value: Statement,
    val cases: List<WhenCase>,
    val elseCase: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        val whenValue = value.execute(scope)
        for (case in cases) {
            for (condition in case.conditions) {
                if (condition.matches(scope, whenValue)) {
                    return case.block.execute(scope)
                }
            }
        }
        return elseCase?.execute(scope) ?: ObjVoid
    }
}
