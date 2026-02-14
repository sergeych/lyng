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
 *
 */

package net.sergeych.lyng.bytecode

import net.sergeych.lyng.*
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ValueFnRef

class BytecodeStatement private constructor(
    val original: Statement,
    private val function: CmdFunction,
) : Statement(original.isStaticConst, original.isConst, original.returnType) {
    override val pos: Pos = original.pos

    override suspend fun execute(scope: Scope): Obj {
        scope.pos = pos
        return CmdVm().execute(function, scope, scope.args)
    }

    internal fun bytecodeFunction(): CmdFunction = function

    companion object {
        fun wrap(
            statement: Statement,
            nameHint: String,
            allowLocalSlots: Boolean,
            returnLabels: Set<String> = emptySet(),
            rangeLocalNames: Set<String> = emptySet(),
            allowedScopeNames: Set<String>? = null,
            scopeSlotNameSet: Set<String>? = null,
            moduleScopeId: Int? = null,
            forcedLocalSlots: Map<String, Int> = emptyMap(),
            forcedLocalScopeId: Int? = null,
            forcedLocalSlotInfo: Map<String, ForcedLocalSlotInfo> = emptyMap(),
            globalSlotInfo: Map<String, ForcedLocalSlotInfo> = emptyMap(),
            globalSlotScopeId: Int? = null,
            slotTypeByScopeId: Map<Int, Map<Int, ObjClass>> = emptyMap(),
            knownNameObjClass: Map<String, ObjClass> = emptyMap(),
            knownObjectNames: Set<String> = emptySet(),
            classFieldTypesByName: Map<String, Map<String, ObjClass>> = emptyMap(),
            enumEntriesByName: Map<String, List<String>> = emptyMap(),
            callableReturnTypeByScopeId: Map<Int, Map<Int, ObjClass>> = emptyMap(),
            callableReturnTypeByName: Map<String, ObjClass> = emptyMap(),
            externCallableNames: Set<String> = emptySet(),
            lambdaCaptureEntriesByRef: Map<ValueFnRef, List<LambdaCaptureEntry>> = emptyMap(),
        ): Statement {
            if (statement is BytecodeStatement) return statement
            val hasUnsupported = containsUnsupportedStatement(statement)
            if (hasUnsupported) {
                val statementName = statement.toString()
                throw BytecodeCompileException(
                    "Bytecode compile error: unsupported statement $statementName in '$nameHint'",
                    statement.pos
                )
            }
            val safeLocals = allowLocalSlots
            val compiler = BytecodeCompiler(
                allowLocalSlots = safeLocals,
                returnLabels = returnLabels,
                rangeLocalNames = rangeLocalNames,
                allowedScopeNames = allowedScopeNames,
                scopeSlotNameSet = scopeSlotNameSet,
                moduleScopeId = moduleScopeId,
                forcedLocalSlots = forcedLocalSlots,
                forcedLocalScopeId = forcedLocalScopeId,
                forcedLocalSlotInfo = forcedLocalSlotInfo,
                globalSlotInfo = globalSlotInfo,
                globalSlotScopeId = globalSlotScopeId,
                slotTypeByScopeId = slotTypeByScopeId,
                knownNameObjClass = knownNameObjClass,
                knownObjectNames = knownObjectNames,
                classFieldTypesByName = classFieldTypesByName,
                enumEntriesByName = enumEntriesByName,
                callableReturnTypeByScopeId = callableReturnTypeByScopeId,
                callableReturnTypeByName = callableReturnTypeByName,
                externCallableNames = externCallableNames,
                lambdaCaptureEntriesByRef = lambdaCaptureEntriesByRef
            )
            val compiled = compiler.compileStatement(nameHint, statement)
            val fn = compiled ?: throw BytecodeCompileException(
                "Bytecode compile error: failed to compile '$nameHint'",
                statement.pos
            )
            return BytecodeStatement(statement, fn)
        }

        private fun containsUnsupportedStatement(stmt: Statement): Boolean {
            val target = if (stmt is BytecodeStatement) stmt.original else stmt
            return when (target) {
                is net.sergeych.lyng.ExpressionStatement -> {
                    val ref = target.ref
                    if (ref is net.sergeych.lyng.obj.StatementRef) {
                        containsUnsupportedStatement(ref.statement)
                    } else {
                        false
                    }
                }
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
                is net.sergeych.lyng.InlineBlockStatement ->
                    target.statements().any { containsUnsupportedStatement(it) }
                is net.sergeych.lyng.VarDeclStatement ->
                    target.initializer?.let { containsUnsupportedStatement(it) } ?: false
                is net.sergeych.lyng.DelegatedVarDeclStatement ->
                    containsUnsupportedStatement(target.initializer)
                is net.sergeych.lyng.DestructuringVarDeclStatement ->
                    containsUnsupportedStatement(target.initializer)
                is net.sergeych.lyng.BreakStatement ->
                    target.resultExpr?.let { containsUnsupportedStatement(it) } ?: false
                is net.sergeych.lyng.ContinueStatement -> false
                is net.sergeych.lyng.ReturnStatement ->
                    target.resultExpr?.let { containsUnsupportedStatement(it) } ?: false
                is net.sergeych.lyng.ThrowStatement ->
                    containsUnsupportedStatement(target.throwExpr)
                is net.sergeych.lyng.NopStatement -> false
                is net.sergeych.lyng.ExtensionPropertyDeclStatement -> false
                is net.sergeych.lyng.ClassDeclStatement -> false
                is net.sergeych.lyng.FunctionDeclStatement -> false
                is net.sergeych.lyng.EnumDeclStatement -> false
                is net.sergeych.lyng.ClassStaticFieldInitStatement ->
                    target.initializer?.let { containsUnsupportedStatement(it) } ?: false
                is net.sergeych.lyng.ClassInstanceInitDeclStatement ->
                    containsUnsupportedStatement(target.initStatement)
                is net.sergeych.lyng.ClassInstanceFieldDeclStatement ->
                    target.initStatement?.let { containsUnsupportedStatement(it) } ?: false
                is net.sergeych.lyng.ClassInstancePropertyDeclStatement ->
                    target.initStatement?.let { containsUnsupportedStatement(it) } ?: false
                is net.sergeych.lyng.ClassInstanceDelegatedDeclStatement ->
                    target.initStatement?.let { containsUnsupportedStatement(it) } ?: false
                is net.sergeych.lyng.InstanceFieldInitStatement ->
                    target.initializer?.let { containsUnsupportedStatement(it) } ?: false
                is net.sergeych.lyng.InstancePropertyInitStatement -> false
                is net.sergeych.lyng.InstanceDelegatedInitStatement ->
                    containsUnsupportedStatement(target.initializer)
                is net.sergeych.lyng.TryStatement -> {
                    containsUnsupportedStatement(target.body) ||
                        target.catches.any { containsUnsupportedStatement(it.block) } ||
                        (target.finallyClause?.let { containsUnsupportedStatement(it) } ?: false)
                }
                is net.sergeych.lyng.WhenStatement -> {
                    containsUnsupportedStatement(target.value) ||
                        target.cases.any { case ->
                            case.conditions.any { cond -> containsUnsupportedStatement(cond.expr) } ||
                                containsUnsupportedStatement(case.block)
                        } ||
                        (target.elseCase?.let { containsUnsupportedStatement(it) } ?: false)
                }
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
                        stmt.scopeId,
                        stmt.captureSlots,
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
                        stmt.scopeId,
                        stmt.pos,
                        stmt.initializerObjClass
                    )
                }
                is net.sergeych.lyng.DestructuringVarDeclStatement -> {
                    net.sergeych.lyng.DestructuringVarDeclStatement(
                        stmt.pattern,
                        stmt.names,
                        unwrapDeep(stmt.initializer),
                        stmt.isMutable,
                        stmt.visibility,
                        stmt.isTransient,
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
                        stmt.loopScopeId,
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
                is net.sergeych.lyng.WhenStatement -> {
                    net.sergeych.lyng.WhenStatement(
                        unwrapDeep(stmt.value),
                        stmt.cases.map { case ->
                            net.sergeych.lyng.WhenCase(
                                case.conditions.map { unwrapWhenCondition(it) },
                                unwrapDeep(case.block)
                            )
                        },
                        stmt.elseCase?.let { unwrapDeep(it) },
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.ClassStaticFieldInitStatement -> {
                    net.sergeych.lyng.ClassStaticFieldInitStatement(
                        stmt.name,
                        stmt.isMutable,
                        stmt.visibility,
                        stmt.writeVisibility,
                        stmt.initializer?.let { unwrapDeep(it) },
                        stmt.isDelegated,
                        stmt.isTransient,
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.ClassInstanceInitDeclStatement -> {
                    net.sergeych.lyng.ClassInstanceInitDeclStatement(
                        unwrapDeep(stmt.initStatement),
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.ClassInstanceFieldDeclStatement -> {
                    net.sergeych.lyng.ClassInstanceFieldDeclStatement(
                        stmt.name,
                        stmt.isMutable,
                        stmt.visibility,
                        stmt.writeVisibility,
                        stmt.isAbstract,
                        stmt.isClosed,
                        stmt.isOverride,
                        stmt.isTransient,
                        stmt.fieldId,
                        stmt.initStatement?.let { unwrapDeep(it) },
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.ClassInstancePropertyDeclStatement -> {
                    net.sergeych.lyng.ClassInstancePropertyDeclStatement(
                        stmt.name,
                        stmt.isMutable,
                        stmt.visibility,
                        stmt.writeVisibility,
                        stmt.isAbstract,
                        stmt.isClosed,
                        stmt.isOverride,
                        stmt.isTransient,
                        stmt.prop,
                        stmt.methodId,
                        stmt.initStatement?.let { unwrapDeep(it) },
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.ClassInstanceDelegatedDeclStatement -> {
                    net.sergeych.lyng.ClassInstanceDelegatedDeclStatement(
                        stmt.name,
                        stmt.isMutable,
                        stmt.visibility,
                        stmt.writeVisibility,
                        stmt.isAbstract,
                        stmt.isClosed,
                        stmt.isOverride,
                        stmt.isTransient,
                        stmt.methodId,
                        stmt.initStatement?.let { unwrapDeep(it) },
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.InstanceFieldInitStatement -> {
                    net.sergeych.lyng.InstanceFieldInitStatement(
                        stmt.storageName,
                        stmt.isMutable,
                        stmt.visibility,
                        stmt.writeVisibility,
                        stmt.isAbstract,
                        stmt.isClosed,
                        stmt.isOverride,
                        stmt.isTransient,
                        stmt.isLateInitVal,
                        stmt.initializer?.let { unwrapDeep(it) },
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.InstancePropertyInitStatement -> {
                    net.sergeych.lyng.InstancePropertyInitStatement(
                        stmt.storageName,
                        stmt.isMutable,
                        stmt.visibility,
                        stmt.writeVisibility,
                        stmt.isAbstract,
                        stmt.isClosed,
                        stmt.isOverride,
                        stmt.isTransient,
                        stmt.prop,
                        stmt.pos
                    )
                }
                is net.sergeych.lyng.InstanceDelegatedInitStatement -> {
                    net.sergeych.lyng.InstanceDelegatedInitStatement(
                        stmt.storageName,
                        stmt.memberName,
                        stmt.isMutable,
                        stmt.visibility,
                        stmt.writeVisibility,
                        stmt.isAbstract,
                        stmt.isClosed,
                        stmt.isOverride,
                        stmt.isTransient,
                        stmt.accessTypeLabel,
                        unwrapDeep(stmt.initializer),
                        stmt.pos
                    )
                }
                else -> stmt
            }
        }

        private fun unwrapWhenCondition(cond: WhenCondition): WhenCondition {
            return when (cond) {
                is WhenEqualsCondition -> WhenEqualsCondition(unwrapDeep(cond.expr), cond.pos)
                is WhenInCondition -> WhenInCondition(unwrapDeep(cond.expr), cond.negated, cond.pos)
                is WhenIsCondition -> WhenIsCondition(unwrapDeep(cond.expr), cond.negated, cond.pos)
            }
        }
    }
}
