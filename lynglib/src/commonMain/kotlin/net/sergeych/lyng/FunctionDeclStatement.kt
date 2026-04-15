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

package net.sergeych.lyng

import net.sergeych.lyng.obj.*

class FunctionClosureBox(
    var closure: Scope? = null,
    var captureContext: Scope? = null,
    var captureRecords: List<ObjRecord>? = null,
)

data class FunctionDeclSpec(
    val name: String,
    val visibility: Visibility,
    val isAbstract: Boolean,
    val isClosed: Boolean,
    val isOverride: Boolean,
    val isStatic: Boolean,
    val isTransient: Boolean,
    val isDelegated: Boolean,
    val delegateExpression: Statement?,
    val delegateInitStatement: Statement?,
    val extTypeName: String?,
    val extensionWrapperName: String?,
    val memberMethodId: Int?,
    val actualExtern: Boolean,
    val parentIsClassBody: Boolean,
    val externCallSignature: CallSignature?,
    val annotation: (suspend (Scope, ObjString, Statement) -> Statement)?,
    val typeDecl: TypeDecl?,
    val fnBody: Statement,
    val closureBox: FunctionClosureBox,
    val captureSlots: List<CaptureSlot>,
    val slotIndex: Int?,
    val scopeId: Int?,
    val startPos: Pos,
)

internal suspend fun executeFunctionDecl(
    scope: Scope,
    spec: FunctionDeclSpec,
    captureRecords: List<ObjRecord>? = null
): Obj {
    spec.closureBox.captureRecords = captureRecords
    if (spec.actualExtern && spec.extTypeName == null && !spec.parentIsClassBody) {
        val existing = scope.get(spec.name)
        if (existing != null) {
            val value = (existing.value as? ObjExternCallable) ?: ObjExternCallable.wrap(existing.value)
            scope.addItem(
                spec.name,
                false,
                value,
                spec.visibility,
                callSignature = existing.callSignature,
                typeDecl = spec.typeDecl
            )
            return value
        }
    }
    if (spec.actualExtern && spec.extTypeName == null && spec.parentIsClassBody) {
        val cls = scope.thisObj as? ObjClass
        if (cls != null) {
            val existing = cls.members[spec.name]
            if (existing != null) {
                cls.members[spec.name] = existing.copy(
                    typeDecl = existing.typeDecl ?: spec.typeDecl
                )
                val memberValue = cls.members[spec.name]?.value ?: existing.value
                val local = scope.getLocalRecordDirect(spec.name)
                if (local != null) {
                    scope.objects[spec.name] = local.copy(
                        value = memberValue,
                        typeDecl = local.typeDecl ?: spec.typeDecl
                    )
                } else {
                    scope.addItem(
                        spec.name,
                        false,
                        memberValue,
                        spec.visibility,
                        callSignature = spec.externCallSignature,
                        typeDecl = spec.typeDecl
                    )
                }
                return memberValue
            }
        }
    }

    if (spec.isDelegated) {
        val delegateExpr = spec.delegateExpression ?: scope.raiseError("delegated function missing delegate")
        val accessType = ObjString("Callable")
        val initValue = executeBytecodeWithSeed(scope, delegateExpr, "delegated function")
        val finalDelegate = try {
            initValue.invokeInstanceMethod(scope, "bind", Arguments(ObjString(spec.name), accessType, scope.thisObj))
        } catch (e: Exception) {
            initValue
        }

        if (spec.extTypeName != null) {
            val type = scope.resolveExtensionReceiverClass(spec.extTypeName)
            scope.addExtension(
                type,
                spec.name,
                ObjRecord(ObjUnset, isMutable = false, visibility = spec.visibility, declaringClass = null, type = ObjRecord.Type.Delegated).apply {
                    delegate = finalDelegate
                }
            )
            return finalDelegate
        }

        val th = scope.thisObj
        if (spec.isStatic) {
            (th as ObjClass).createClassField(
                spec.name,
                ObjUnset,
                false,
                spec.visibility,
                null,
                spec.startPos,
                isTransient = spec.isTransient,
                type = ObjRecord.Type.Delegated
            ).apply {
                delegate = finalDelegate
            }
            scope.addItem(
                spec.name,
                false,
                ObjUnset,
                spec.visibility,
                recordType = ObjRecord.Type.Delegated,
                isTransient = spec.isTransient
            ).apply {
                delegate = finalDelegate
            }
        } else if (th is ObjClass) {
            val cls: ObjClass = th
            cls.createField(
                spec.name,
                ObjUnset,
                false,
                spec.visibility,
                null,
                spec.startPos,
                declaringClass = cls,
                isAbstract = spec.isAbstract,
                isClosed = spec.isClosed,
                isOverride = spec.isOverride,
                isTransient = spec.isTransient,
                type = ObjRecord.Type.Delegated,
                methodId = spec.memberMethodId
            )
            val initStmt = spec.delegateInitStatement
                ?: scope.raiseIllegalState("missing delegated init statement for ${spec.name}")
            cls.instanceInitializers += requireBytecodeBody(scope, initStmt, "delegated function init")
        } else {
            scope.addItem(
                spec.name,
                false,
                ObjUnset,
                spec.visibility,
                recordType = ObjRecord.Type.Delegated,
                isTransient = spec.isTransient
            ).apply {
                delegate = finalDelegate
            }
        }
        return finalDelegate
    }

    if (spec.isStatic || !spec.parentIsClassBody) {
        spec.closureBox.closure = scope
    }
    if (spec.parentIsClassBody) {
        spec.closureBox.captureContext = scope
    }

    val annotatedFnBody = spec.annotation?.invoke(scope, ObjString(spec.name), spec.fnBody) ?: spec.fnBody
    val compiledFnBody = annotatedFnBody

    spec.extTypeName?.let { typeName ->
        val type = scope.resolveExtensionReceiverClass(typeName)
        if (spec.isStatic) {
            type.createClassField(
                spec.name,
                compiledFnBody,
                isMutable = false,
                visibility = spec.visibility,
                pos = spec.startPos,
                type = ObjRecord.Type.Fun,
            )
        } else {
            scope.addExtension(
                type,
                spec.name,
                ObjRecord(
                    compiledFnBody,
                    isMutable = false,
                    visibility = spec.visibility,
                    declaringClass = null,
                    type = ObjRecord.Type.Fun,
                    typeDecl = spec.typeDecl
                )
            )
        }
        val wrapperName = spec.extensionWrapperName ?: extensionCallableName(typeName, spec.name)
        val wrapper = ObjExtensionMethodCallable(spec.name, compiledFnBody)
        scope.addItem(
            wrapperName,
            false,
            wrapper,
            spec.visibility,
            recordType = ObjRecord.Type.Fun,
            typeDecl = spec.typeDecl
        )
    } ?: run {
        val th = scope.thisObj
        if (!spec.isStatic && th is ObjClass) {
            val cls: ObjClass = th
            cls.createField(
                spec.name,
                compiledFnBody,
                isMutable = true,
                visibility = spec.visibility,
                pos = spec.startPos,
                declaringClass = cls,
                isAbstract = spec.isAbstract,
                isClosed = spec.isClosed,
                isOverride = spec.isOverride,
                type = ObjRecord.Type.Fun,
                methodId = spec.memberMethodId,
                typeDecl = spec.typeDecl
            )
            val memberValue = cls.members[spec.name]?.value ?: compiledFnBody
            scope.addItem(
                spec.name,
                false,
                memberValue,
                spec.visibility,
                callSignature = spec.externCallSignature,
                typeDecl = spec.typeDecl
            )
            compiledFnBody
        } else {
            scope.addItem(
                spec.name,
                false,
                compiledFnBody,
                spec.visibility,
                recordType = ObjRecord.Type.Fun,
                callSignature = spec.externCallSignature,
                typeDecl = spec.typeDecl
            )
        }
    }
    return annotatedFnBody
}

private suspend fun requireBytecodeBody(
    scope: Scope,
    stmt: Statement,
    label: String
): net.sergeych.lyng.bytecode.BytecodeStatement {
    val bytecode = when (stmt) {
        is net.sergeych.lyng.bytecode.BytecodeStatement -> stmt
        is BytecodeBodyProvider -> stmt.bytecodeBody()
        else -> null
    }
    return bytecode ?: scope.raiseIllegalState("$label requires bytecode statement")
}

class FunctionDeclStatement(
    val spec: FunctionDeclSpec,
) : Statement() {
    override val pos: Pos = spec.startPos

    override suspend fun execute(scope: Scope): Obj {
        return executeFunctionDecl(scope, spec)
    }

    override suspend fun callOn(scope: Scope): Obj {
        val target = scope.parent ?: scope
        return executeFunctionDecl(target, spec)
    }
}
