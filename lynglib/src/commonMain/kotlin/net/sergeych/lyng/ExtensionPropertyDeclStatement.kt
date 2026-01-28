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

class ExtensionPropertyDeclStatement(
    val extTypeName: String,
    val property: ObjProperty,
    val visibility: Visibility,
    val setterVisibility: Visibility?,
    private val startPos: Pos,
) : Statement() {
    override val pos: Pos = startPos

    override suspend fun execute(context: Scope): Obj {
        val type = context[extTypeName]?.value ?: context.raiseSymbolNotFound("class $extTypeName not found")
        if (type !is ObjClass) context.raiseClassCastError("$extTypeName is not the class instance")
        context.addExtension(
            type,
            property.name,
            ObjRecord(
                property,
                isMutable = false,
                visibility = visibility,
                writeVisibility = setterVisibility,
                declaringClass = null,
                type = ObjRecord.Type.Property
            )
        )
        return property
    }
}
