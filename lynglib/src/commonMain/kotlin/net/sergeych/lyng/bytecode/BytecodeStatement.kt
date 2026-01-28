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
import net.sergeych.lyng.obj.RangeRef

class BytecodeStatement private constructor(
    val original: Statement,
    private val function: CmdFunction,
) : Statement(original.isStaticConst, original.isConst, original.returnType) {
    override val pos: Pos = original.pos

    override suspend fun execute(scope: Scope): Obj {
        return CmdVm().execute(function, scope, emptyList())
    }

    internal fun bytecodeFunction(): CmdFunction = function

    companion object {
        fun wrap(
            statement: Statement,
            nameHint: String,
            allowLocalSlots: Boolean,
            returnLabels: Set<String> = emptySet(),
            rangeLocalNames: Set<String> = emptySet(),
        ): Statement {
            if (statement is BytecodeStatement) return statement
            val hasUnsupported = containsUnsupportedStatement(statement)
            if (hasUnsupported) {
                throw BytecodeFallbackException(
                    "Bytecode fallback: unsupported statement in '$nameHint'",
                    statement.pos
                )
            }
            val safeLocals = allowLocalSlots
            val compiler = BytecodeCompiler(
                allowLocalSlots = safeLocals,
                returnLabels = returnLabels,
                rangeLocalNames = rangeLocalNames
            )
            val compiled = compiler.compileStatement(nameHint, statement)
            val fn = compiled ?: throw BytecodeFallbackException(
                "Bytecode fallback: failed to compile '$nameHint'",
                statement.pos
            )
            return BytecodeStatement(statement, fn)
        }

        private fun containsUnsupportedStatement(stmt: Statement): Boolean {
            val target = if (stmt is BytecodeStatement) stmt.original else stmt
            return when (target) {
                is net.sergeych.lyng.ExpressionStatement -> false
                is net.sergeych.lyng.IfStatement -> {
                    containsUnsupportedStatement(target.condition) ||
                        containsUnsupportedStatement(target.ifBody) ||
                        (target.elseBody?.let { containsUnsupportedStatement(it) } ?: false)
                }
                is net.sergeych.lyng.ForInStatement -> {
                    val unsupported = containsUnsupportedStatement(target.source) ||
                        containsUnsupportedStatement(target.body) ||
                        (target.elseStatement?.let { containsUnsupportedStatement(it) } ?: false)
                    unsupported
                }
                is net.sergeych.lyng.WhileStatement -> {
                    containsUnsupportedStatement(target.condition) ||
                        containsUnsupportedStatement(target.body) ||
                        (target.elseStatement?.let { containsUnsupportedStatement(it) } ?: false)
                }
                is net.sergeych.lyng.DoWhileStatement -> {
                    containsUnsupportedStatement(target.body) ||
                        containsUnsupportedStatement(target.condition) ||
                        (target.elseStatement?.let { containsUnsupportedStatement(it) } ?: false)
                }
                is net.sergeych.lyng.BlockStatement ->
                    target.statements().any { containsUnsupportedStatement(it) }
                is net.sergeych.lyng.VarDeclStatement ->
                    target.initializer?.let { containsUnsupportedStatement(it) } ?: false
                is net.sergeych.lyng.BreakStatement ->
                    target.resultExpr?.let { containsUnsupportedStatement(it) } ?: false
                is net.sergeych.lyng.ContinueStatement -> false
                is net.sergeych.lyng.ReturnStatement ->
                    target.resultExpr?.let { containsUnsupportedStatement(it) } ?: false
                is net.sergeych.lyng.ThrowStatement ->
                    containsUnsupportedStatement(target.throwExpr)
                else -> true
            }
        }

        private fun unwrapDeep(stmt: Statement): Statement {
            return when (stmt) {
                is BytecodeStatement -> unwrapDeep(stmt.original)
                is net.sergeych.lyng.BlockStatement -> {
                    val unwrapped = stmt.statements().map { unwrapDeep(it) }
                    net.sergeych.lyng.BlockStatement(
                        net.sergeych.lyng.Script(stmt.pos, unwrapped),
                        stmt.slotPlan,
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.VarDeclStatement -> {
                    net.sergeych.lyng.VarDeclStatement(
                        stmt.name,
                        stmt.isMutable,
                        stmt.visibility,
                        stmt.initializer?.let { unwrapDeep(it) },
                        stmt.isTransient,
                        stmt.slotIndex,
                        stmt.slotDepth,
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.IfStatement -> {
                    net.sergeych.lyng.IfStatement(
                        unwrapDeep(stmt.condition),
                        unwrapDeep(stmt.ifBody),
                        stmt.elseBody?.let { unwrapDeep(it) },
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.ForInStatement -> {
                    net.sergeych.lyng.ForInStatement(
                        stmt.loopVarName,
                        unwrapDeep(stmt.source),
                        stmt.constRange,
                        unwrapDeep(stmt.body),
                        stmt.elseStatement?.let { unwrapDeep(it) },
                        stmt.label,
                        stmt.canBreak,
                        stmt.loopSlotPlan,
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.WhileStatement -> {
                    net.sergeych.lyng.WhileStatement(
                        unwrapDeep(stmt.condition),
                        unwrapDeep(stmt.body),
                        stmt.elseStatement?.let { unwrapDeep(it) },
                        stmt.label,
                        stmt.canBreak,
                        stmt.loopSlotPlan,
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.DoWhileStatement -> {
                    net.sergeych.lyng.DoWhileStatement(
                        unwrapDeep(stmt.body),
                        unwrapDeep(stmt.condition),
                        stmt.elseStatement?.let { unwrapDeep(it) },
                        stmt.label,
                        stmt.loopSlotPlan,
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.BreakStatement -> {
                    val resultExpr = stmt.resultExpr?.let { unwrapDeep(it) }
                    net.sergeych.lyng.BreakStatement(stmt.label, resultExpr, stmt.pos)
                }
                is net.sergeych.lyng.ContinueStatement ->
                    net.sergeych.lyng.ContinueStatement(stmt.label, stmt.pos)
                is net.sergeych.lyng.ReturnStatement -> {
                    val resultExpr = stmt.resultExpr?.let { unwrapDeep(it) }
                    net.sergeych.lyng.ReturnStatement(stmt.label, resultExpr, stmt.pos)
                }
                is net.sergeych.lyng.ThrowStatement ->
                    net.sergeych.lyng.ThrowStatement(unwrapDeep(stmt.throwExpr), stmt.pos)
                else -> stmt
            }
        }
    }
}
