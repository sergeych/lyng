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
 * Final migration shim: make `Accessor` an alias to `ObjRef`.
 * This preserves source compatibility while removing lambda-based indirection.
 */
typealias Accessor = ObjRef

/** Lambda-based reference for edge cases that still construct access via lambdas. */
private class LambdaRef(
    private val getterFn: suspend (Scope) -> ObjRecord,
    private val setterFn: (suspend (Pos, Scope, Obj) -> Unit)? = null
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = getterFn(scope)
    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        val s = setterFn ?: throw ScriptError(pos, "can't assign value")
        s(pos, scope, newValue)
    }
}

// Factory functions to preserve current call sites like `Accessor { ... }`
fun Accessor(getter: suspend (Scope) -> ObjRecord): Accessor = LambdaRef(getter)
fun Accessor(
    getter: suspend (Scope) -> ObjRecord,
    setter: suspend (Scope, Obj) -> Unit
): Accessor = LambdaRef(getter) { _, scope, value -> setter(scope, value) }

// Compatibility shims used throughout Compiler: `.getter(...)` and `.setter(pos)`
val Accessor.getter: suspend (Scope) -> ObjRecord
    get() = { scope -> this.get(scope) }

fun Accessor.setter(pos: Pos): suspend (Scope, Obj) -> Unit = { scope, newValue ->
    this.setAt(pos, scope, newValue)
}