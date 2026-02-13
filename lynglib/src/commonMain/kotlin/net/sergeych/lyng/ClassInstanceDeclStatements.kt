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
import net.sergeych.lyng.obj.ObjProperty

class ClassInstanceInitDeclStatement(
    val initStatement: Statement,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return bytecodeOnly(scope, "class instance init declaration")
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
        return bytecodeOnly(scope, "class instance field declaration")
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
        return bytecodeOnly(scope, "class instance property declaration")
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
        return bytecodeOnly(scope, "class instance delegated declaration")
    }
}
