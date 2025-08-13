/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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

package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope
import net.sergeych.lyng.Visibility

/**
 * Record to store object with access rules, e.g. [isMutable] and access level [visibility].
 */
data class ObjRecord(
    var value: Obj,
    val isMutable: Boolean,
    val visibility: Visibility = Visibility.Public,
    var importedFrom: Scope? = null,
    val isTransient: Boolean = false,
    val type: Type = Type.Other
) {
    enum class Type(val comparable: Boolean = false,val serializable: Boolean = false) {
        Field(true, true),
        @Suppress("unused")
        Fun,
        ConstructorField(true, true),
        @Suppress("unused")
        Class,
        Enum,
        Other
    }
    @Suppress("unused")
    fun qualifiedName(name: String): String =
        "${importedFrom?.packageName ?: "anonymous"}.$name"
}