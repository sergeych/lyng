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

import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScriptError

// avoid KDOC bug: keep it
@Suppress("unused")
typealias DocCompiler = Compiler
/**
 * When we need read-write access to an object in some abstract storage, we need Accessor,
 * as in-site assigning is not always sufficient, in general case we need to replace the object
 * in the storage.
 *
 * Note that assigning new value is more complex than just replacing the object, see how assignment
 * operator is implemented in [Compiler.allOps].
 */
data class Accessor(
    val getter: suspend (Scope) -> ObjRecord,
    val setterOrNull: (suspend (Scope, Obj) -> Unit)?
) {
    /**
     * Simplified constructor for immutable stores.
     */
    constructor(getter: suspend (Scope) -> ObjRecord) : this(getter, null)

    /**
     * Get the setter or throw.
     */
    fun setter(pos: Pos) = setterOrNull ?: throw ScriptError(pos, "can't assign value")
}