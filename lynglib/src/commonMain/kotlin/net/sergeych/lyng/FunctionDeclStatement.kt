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
import net.sergeych.lyng.obj.ObjExtensionMethodCallable
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjUnset
import net.sergeych.lyng.obj.ObjVoid

class FunctionClosureBox(
    var closure: Scope? = null,
    var captureContext: Scope? = null,
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
    val extTypeName: String?,
    val extensionWrapperName: String?,
    val memberMethodId: Int?,
    val actualExtern: Boolean,
    val parentIsClassBody: Boolean,
    val externCallSignature: CallSignature?,
    val annotation: (suspend (Scope, ObjString, Statement) -> Statement)?,
    val fnBody: Statement,
    val closureBox: FunctionClosureBox,
    val captureSlots: List<CaptureSlot>,
    val startPos: Pos,
)

internal suspend fun executeFunctionDecl(scope: Scope, spec: FunctionDeclSpec): Obj {
    if (spec.actualExtern && spec.extTypeName == null && !spec.parentIsClassBody) {
        val existing = scope.get(spec.name)
        if (existing != null) {
            scope.addItem(
                spec.name,
                false,
                existing.value,
                spec.visibility,
                callSignature = existing.callSignature
            )
            return existing.value
        }
    }

    if (spec.isDelegated) {
        val delegateExpr = spec.delegateExpression ?: scope.raiseError("delegated function missing delegate")
        val accessType = ObjString("Callable")
        val initValue = delegateExpr.execute(scope)
        val finalDelegate = try {
            initValue.invokeInstanceMethod(scope, "bind", Arguments(ObjString(spec.name), accessType, scope.thisObj))
        } catch (e: Exception) {
            initValue
        }

        if (spec.extTypeName != null) {
            val type = scope[spec.extTypeName]?.value ?: scope.raiseSymbolNotFound("class ${spec.extTypeName} not found")
            if (type !is ObjClass) scope.raiseClassCastError("${spec.extTypeName} is not the class instance")
            scope.addExtension(
                type,
                spec.name,
                ObjRecord(ObjUnset, isMutable = false, visibility = spec.visibility, declaringClass = null, type = ObjRecord.Type.Delegated).apply {
                    delegate = finalDelegate
                }
            )
            return ObjVoid
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
            val storageName = "${cls.className}::${spec.name}"
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
            cls.instanceInitializers += object : Statement() {
                override val pos: Pos = spec.startPos
                override suspend fun execute(scp: Scope): Obj {
                    val accessType2 = ObjString("Callable")
                    val initValue2 = delegateExpr.execute(scp)
                    val finalDelegate2 = try {
                        initValue2.invokeInstanceMethod(scp, "bind", Arguments(ObjString(spec.name), accessType2, scp.thisObj))
                    } catch (e: Exception) {
                        initValue2
                    }
                    scp.addItem(
                        storageName,
                        false,
                        ObjUnset,
                        spec.visibility,
                        null,
                        recordType = ObjRecord.Type.Delegated,
                        isAbstract = spec.isAbstract,
                        isClosed = spec.isClosed,
                        isOverride = spec.isOverride,
                        isTransient = spec.isTransient
                    ).apply {
                        delegate = finalDelegate2
                    }
                    return ObjVoid
                }
            }
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
        return ObjVoid
    }

    if (spec.isStatic || !spec.parentIsClassBody) {
        spec.closureBox.closure = scope
    }
    if (spec.parentIsClassBody && spec.captureSlots.isNotEmpty()) {
        spec.closureBox.captureContext = scope
    }

    val annotatedFnBody = spec.annotation?.invoke(scope, ObjString(spec.name), spec.fnBody) ?: spec.fnBody
    val compiledFnBody = annotatedFnBody

    spec.extTypeName?.let { typeName ->
        val type = scope[typeName]?.value ?: scope.raiseSymbolNotFound("class $typeName not found")
        if (type !is ObjClass) scope.raiseClassCastError("$typeName is not the class instance")
        val stmt = object : Statement() {
            override val pos: Pos = spec.startPos
            override suspend fun execute(scope: Scope): Obj {
                val result = (scope.thisObj as? ObjInstance)?.let { i ->
                    compiledFnBody.execute(ClosureScope(scope, i.instanceScope))
                } ?: compiledFnBody.execute(scope.thisObj.autoInstanceScope(scope))
                return result
            }
        }
        scope.addExtension(type, spec.name, ObjRecord(stmt, isMutable = false, visibility = spec.visibility, declaringClass = null))
        val wrapperName = spec.extensionWrapperName ?: extensionCallableName(typeName, spec.name)
        val wrapper = ObjExtensionMethodCallable(spec.name, stmt)
        scope.addItem(wrapperName, false, wrapper, spec.visibility, recordType = ObjRecord.Type.Fun)
    } ?: run {
        val th = scope.thisObj
        if (!spec.isStatic && th is ObjClass) {
            val cls: ObjClass = th
            cls.addFn(
                spec.name,
                isMutable = true,
                visibility = spec.visibility,
                isAbstract = spec.isAbstract,
                isClosed = spec.isClosed,
                isOverride = spec.isOverride,
                pos = spec.startPos,
                methodId = spec.memberMethodId
            ) {
                val savedCtx = this.currentClassCtx
                this.currentClassCtx = cls
                try {
                    (thisObj as? ObjInstance)?.let { i ->
                        val execScope = i.instanceScope.createChildScope(
                            pos = this.pos,
                            args = this.args,
                            newThisObj = i
                        )
                        execScope.currentClassCtx = cls
                        compiledFnBody.execute(execScope)
                    } ?: compiledFnBody.execute(thisObj.autoInstanceScope(this))
                } finally {
                    this.currentClassCtx = savedCtx
                }
            }
            scope.addItem(spec.name, false, compiledFnBody, spec.visibility, callSignature = spec.externCallSignature)
            compiledFnBody
        } else {
            scope.addItem(spec.name, false, compiledFnBody, spec.visibility, callSignature = spec.externCallSignature)
        }
    }
    return annotatedFnBody
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
