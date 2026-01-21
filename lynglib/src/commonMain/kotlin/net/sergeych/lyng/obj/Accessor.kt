/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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

interface AccessorGetter {
    suspend fun call(scope: Scope): ObjRecord
}

interface AccessorSetter {
    suspend fun call(scope: Scope, value: Obj)
}

/** Lambda-based reference for edge cases that still construct access via lambdas. */
private class LambdaRef(
    private val getterFn: AccessorGetter,
    private val setterFn: AccessorSetter? = null
) : ObjRef {
    override suspend fun get(scope: Scope): ObjRecord = getterFn.call(scope)
    override suspend fun setAt(pos: Pos, scope: Scope, newValue: Obj) {
        val s = setterFn ?: throw ScriptError(pos, "can't assign value")
        s.call(scope, newValue)
    }
}

// Factory functions to preserve current call sites like `Accessor { ... }`
fun Accessor(getter: AccessorGetter): Accessor = LambdaRef(getter)
fun Accessor(
    getter: AccessorGetter,
    setter: AccessorSetter
): Accessor = LambdaRef(getter, setter)

// Compatibility shims used throughout Compiler: `.getter(...)` and `.setter(pos)`
val Accessor.getter: AccessorGetter
    get() = object : AccessorGetter {
        override suspend fun call(scope: Scope): ObjRecord = this@getter.get(scope)
    }

fun Accessor.setter(pos: Pos): AccessorSetter = object : AccessorSetter {
    override suspend fun call(scope: Scope, value: Obj) {
        this@setter.setAt(pos, scope, value)
    }
}