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
import net.sergeych.lyng.obj.ObjProperty
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.obj.ObjVoid

class ClassInstanceInitDeclStatement(
    val initStatement: Statement,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        val cls = scope.thisObj as? ObjClass
            ?: scope.raiseIllegalState("instance init declaration requires class scope")
        cls.instanceInitializers += initStatement
        return ObjVoid
    }
}

class ClassInstanceFieldDeclStatement(
    val name: String,
    val isMutable: Boolean,
    val visibility: Visibility,
    val writeVisibility: Visibility?,
    val isAbstract: Boolean,
    val isClosed: Boolean,
    val isOverride: Boolean,
    val isTransient: Boolean,
    val fieldId: Int?,
    val initStatement: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        val cls = scope.thisObj as? ObjClass
            ?: scope.raiseIllegalState("instance field declaration requires class scope")
        cls.createField(
            name,
            net.sergeych.lyng.obj.ObjNull,
            isMutable = isMutable,
            visibility = visibility,
            writeVisibility = writeVisibility,
            isAbstract = isAbstract,
            isClosed = isClosed,
            isOverride = isOverride,
            isTransient = isTransient,
            declaringClass = cls,
            type = ObjRecord.Type.Field,
            fieldId = fieldId
        )
        if (!isAbstract) initStatement?.let { cls.instanceInitializers += it }
        return ObjVoid
    }
}

class ClassInstancePropertyDeclStatement(
    val name: String,
    val isMutable: Boolean,
    val visibility: Visibility,
    val writeVisibility: Visibility?,
    val isAbstract: Boolean,
    val isClosed: Boolean,
    val isOverride: Boolean,
    val isTransient: Boolean,
    val prop: ObjProperty,
    val methodId: Int?,
    val initStatement: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        val cls = scope.thisObj as? ObjClass
            ?: scope.raiseIllegalState("instance property declaration requires class scope")
        cls.addProperty(
            name = name,
            visibility = visibility,
            writeVisibility = writeVisibility,
            declaringClass = cls,
            isAbstract = isAbstract,
            isClosed = isClosed,
            isOverride = isOverride,
            pos = pos,
            prop = prop,
            methodId = methodId
        )
        if (!isAbstract) initStatement?.let { cls.instanceInitializers += it }
        return ObjVoid
    }
}

class ClassInstanceDelegatedDeclStatement(
    val name: String,
    val isMutable: Boolean,
    val visibility: Visibility,
    val writeVisibility: Visibility?,
    val isAbstract: Boolean,
    val isClosed: Boolean,
    val isOverride: Boolean,
    val isTransient: Boolean,
    val methodId: Int?,
    val initStatement: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        val cls = scope.thisObj as? ObjClass
            ?: scope.raiseIllegalState("instance delegated declaration requires class scope")
        cls.createField(
            name,
            net.sergeych.lyng.obj.ObjUnset,
            isMutable = isMutable,
            visibility = visibility,
            writeVisibility = writeVisibility,
            isAbstract = isAbstract,
            isClosed = isClosed,
            isOverride = isOverride,
            isTransient = isTransient,
            declaringClass = cls,
            type = ObjRecord.Type.Delegated,
            methodId = methodId
        )
        if (!isAbstract) initStatement?.let { cls.instanceInitializers += it }
        return ObjVoid
    }
}
