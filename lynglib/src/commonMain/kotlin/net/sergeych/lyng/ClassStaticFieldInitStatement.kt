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
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjUnset
import net.sergeych.lyng.obj.ObjVoid

class ClassStaticFieldInitStatement(
    val name: String,
    val isMutable: Boolean,
    val visibility: Visibility,
    val writeVisibility: Visibility?,
    val initializer: Statement?,
    val isDelegated: Boolean,
    val isTransient: Boolean,
    val annotationSpecs: List<ParsedDeclAnnotation> = emptyList(),
    private val startPos: Pos,
) : Statement() {
    override val pos: Pos = startPos

    override suspend fun execute(scope: Scope): Obj {
        val initValue = initializer?.let { execBytecodeOnly(scope, it, "class static field init") }?.byValueCopy()
            ?: ObjNull
        val annotations = annotationSpecs.evaluateDeclAnnotations(scope)
        val cls = scope.thisObj as? ObjClass
            ?: scope.raiseIllegalState("static field init requires class scope")
        return if (isDelegated) {
            val accessTypeStr = if (isMutable) "Var" else "Val"
            val accessType = ObjString(accessTypeStr)
            val finalDelegate = try {
                initValue.invokeInstanceMethod(
                    scope,
                    "bind",
                    Arguments(ObjString(name), accessType, scope.thisObj)
                )
            } catch (_: Exception) {
                initValue
            }
            cls.createClassField(
                name,
                ObjUnset,
                isMutable,
                visibility,
                writeVisibility,
                startPos,
                isTransient = isTransient,
                type = ObjRecord.Type.Delegated,
                annotations = annotations
            ).apply {
                delegate = finalDelegate
            }
            scope.addItem(
                name,
                isMutable,
                ObjUnset,
                visibility,
                writeVisibility,
                recordType = ObjRecord.Type.Delegated,
                isTransient = isTransient,
                annotations = annotations
            ).apply {
                delegate = finalDelegate
            }
            finalDelegate
        } else {
            cls.createClassField(
                name,
                initValue,
                isMutable,
                visibility,
                writeVisibility,
                startPos,
                isTransient = isTransient,
                annotations = annotations
            )
            scope.addItem(
                name,
                isMutable,
                initValue,
                visibility,
                writeVisibility,
                recordType = ObjRecord.Type.Field,
                isTransient = isTransient,
                annotations = annotations
            )
            initValue
        }
    }

    private suspend fun execBytecodeOnly(scope: Scope, stmt: Statement, label: String): Obj {
        val bytecode = when (stmt) {
            is net.sergeych.lyng.bytecode.BytecodeStatement -> stmt
            is BytecodeBodyProvider -> stmt.bytecodeBody()
            else -> null
        } ?: scope.raiseIllegalState("$label requires bytecode statement")
        return bytecode.execute(scope)
    }
}
