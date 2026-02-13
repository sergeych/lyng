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
import net.sergeych.lyng.obj.ObjRecord

/**
 * Limited facade for Kotlin bridge callables.
 * Exposes only the minimal API needed to read/write vars and invoke methods.
 */
interface ScopeFacade {
    val args: Arguments
    var pos: Pos
    var thisObj: Obj
    operator fun get(name: String): ObjRecord?
    suspend fun resolve(rec: ObjRecord, name: String): Obj
    suspend fun assign(rec: ObjRecord, name: String, newValue: Obj)
    fun raiseError(message: String): Nothing
    fun raiseSymbolNotFound(name: String): Nothing
    fun raiseIllegalState(message: String = "Illegal argument error"): Nothing
}

internal class ScopeBridge(private val scope: Scope) : ScopeFacade {
    override val args: Arguments
        get() = scope.args
    override var pos: Pos
        get() = scope.pos
        set(value) { scope.pos = value }
    override var thisObj: Obj
        get() = scope.thisObj
        set(value) { scope.thisObj = value }
    override fun get(name: String): ObjRecord? = scope[name]
    override suspend fun resolve(rec: ObjRecord, name: String): Obj = scope.resolve(rec, name)
    override suspend fun assign(rec: ObjRecord, name: String, newValue: Obj) = scope.assign(rec, name, newValue)
    override fun raiseError(message: String): Nothing = scope.raiseError(message)
    override fun raiseSymbolNotFound(name: String): Nothing = scope.raiseSymbolNotFound(name)
    override fun raiseIllegalState(message: String): Nothing = scope.raiseIllegalState(message)
}
