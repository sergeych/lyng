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
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjProperty
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjUnset
import net.sergeych.lyng.obj.ObjVoid

class InstanceFieldInitStatement(
    val storageName: String,
    val isMutable: Boolean,
    val visibility: Visibility,
    val writeVisibility: Visibility?,
    val isAbstract: Boolean,
    val isClosed: Boolean,
    val isOverride: Boolean,
    val isTransient: Boolean,
    val isLateInitVal: Boolean,
    val initializer: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        val initValue = initializer?.let { execBytecodeOnly(scope, it, "instance field init") }?.byValueCopy()
            ?: if (isLateInitVal) ObjUnset else ObjNull
        scope.addItem(
            storageName,
            isMutable,
            initValue,
            visibility,
            writeVisibility,
            recordType = ObjRecord.Type.Field,
            isAbstract = isAbstract,
            isClosed = isClosed,
            isOverride = isOverride,
            isTransient = isTransient
        )
        return ObjVoid
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

class InstancePropertyInitStatement(
    val storageName: String,
    val isMutable: Boolean,
    val visibility: Visibility,
    val writeVisibility: Visibility?,
    val isAbstract: Boolean,
    val isClosed: Boolean,
    val isOverride: Boolean,
    val isTransient: Boolean,
    val prop: ObjProperty,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        scope.addItem(
            storageName,
            isMutable,
            prop,
            visibility,
            writeVisibility,
            recordType = ObjRecord.Type.Property,
            isAbstract = isAbstract,
            isClosed = isClosed,
            isOverride = isOverride,
            isTransient = isTransient
        )
        return ObjVoid
    }
}

class InstanceDelegatedInitStatement(
    val storageName: String,
    val memberName: String,
    val isMutable: Boolean,
    val visibility: Visibility,
    val writeVisibility: Visibility?,
    val isAbstract: Boolean,
    val isClosed: Boolean,
    val isOverride: Boolean,
    val isTransient: Boolean,
    val accessTypeLabel: String,
    val initializer: Statement,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        val initValue = execBytecodeOnly(scope, initializer, "instance delegated init")
        val accessType = ObjString(accessTypeLabel)
        val finalDelegate = try {
            initValue.invokeInstanceMethod(
                scope,
                "bind",
                Arguments(ObjString(memberName), accessType, scope.thisObj)
            )
        } catch (_: Exception) {
            initValue
        }
        scope.addItem(
            storageName,
            isMutable,
            ObjUnset,
            visibility,
            writeVisibility,
            recordType = ObjRecord.Type.Delegated,
            isAbstract = isAbstract,
            isClosed = isClosed,
            isOverride = isOverride,
            isTransient = isTransient
        ).apply {
            delegate = finalDelegate
        }
        return ObjVoid
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
