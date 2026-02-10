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
import net.sergeych.lyng.obj.ObjEnumClass
import net.sergeych.lyng.obj.ObjRecord

class EnumDeclStatement(
    val declaredName: String,
    val qualifiedName: String,
    val entries: List<String>,
    val lifted: Boolean,
    private val startPos: Pos,
) : Statement() {
    override val pos: Pos = startPos

    override suspend fun execute(scope: Scope): Obj {
        val enumClass = ObjEnumClass.createSimpleEnum(qualifiedName, entries)
        scope.addItem(declaredName, false, enumClass, recordType = ObjRecord.Type.Enum)
        if (lifted) {
            for (entry in entries) {
                val rec = enumClass.getInstanceMemberOrNull(entry, includeAbstract = false, includeStatic = true)
                if (rec != null) {
                    scope.addItem(entry, false, rec.value)
                }
            }
        }
        return enumClass
    }

    override suspend fun callOn(scope: Scope): Obj {
        val target = scope.parent ?: scope
        return execute(target)
    }
}
