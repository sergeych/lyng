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
import net.sergeych.lyng.obj.ObjString

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
    fun raiseError(obj: net.sergeych.lyng.obj.ObjException): Nothing
    fun raiseClassCastError(message: String): Nothing
    fun raiseIllegalArgument(message: String): Nothing
    fun raiseNoSuchElement(message: String = "No such element"): Nothing
    fun raiseSymbolNotFound(name: String): Nothing
    fun raiseIllegalState(message: String = "Illegal argument error"): Nothing
    fun raiseNotImplemented(what: String = "operation"): Nothing
    suspend fun call(callee: Obj, args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null): Obj
    suspend fun toStringOf(obj: Obj, forInspect: Boolean = false): ObjString
    suspend fun inspect(obj: Obj): String
    fun trace(text: String = "")
}

internal class ScopeBridge(internal val scope: Scope) : ScopeFacade {
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
    override fun raiseError(obj: net.sergeych.lyng.obj.ObjException): Nothing = scope.raiseError(obj)
    override fun raiseClassCastError(message: String): Nothing = scope.raiseClassCastError(message)
    override fun raiseIllegalArgument(message: String): Nothing = scope.raiseIllegalArgument(message)
    override fun raiseNoSuchElement(message: String): Nothing = scope.raiseNoSuchElement(message)
    override fun raiseSymbolNotFound(name: String): Nothing = scope.raiseSymbolNotFound(name)
    override fun raiseIllegalState(message: String): Nothing = scope.raiseIllegalState(message)
    override fun raiseNotImplemented(what: String): Nothing = scope.raiseNotImplemented(what)
    override suspend fun call(callee: Obj, args: Arguments, newThisObj: Obj?): Obj {
        return callee.callOn(scope.createChildScope(scope.pos, args = args, newThisObj = newThisObj))
    }
    override suspend fun toStringOf(obj: Obj, forInspect: Boolean): ObjString = obj.toString(scope, forInspect)
    override suspend fun inspect(obj: Obj): String = obj.inspect(scope)
    override fun trace(text: String) = scope.trace(text)
}

/** Public factory for bridge facades. */
fun Scope.asFacade(): ScopeFacade = ScopeBridge(this)

inline fun <reified T : Obj> ScopeFacade.requiredArg(index: Int): T {
    if (args.list.size <= index) raiseError("Expected at least ${index + 1} argument, got ${args.list.size}")
    return (args.list[index].byValueCopy() as? T)
        ?: raiseClassCastError("Expected type ${T::class.simpleName}, got ${args.list[index]::class.simpleName}")
}

inline fun <reified T : Obj> ScopeFacade.requireOnlyArg(): T {
    if (args.list.size != 1) raiseError("Expected exactly 1 argument, got ${args.list.size}")
    return requiredArg(0)
}

fun ScopeFacade.requireExactCount(count: Int) {
    if (args.list.size != count) {
        raiseError("Expected exactly $count arguments, got ${args.list.size}")
    }
}

fun ScopeFacade.requireNoArgs() {
    if (args.list.isNotEmpty()) {
        raiseError("This function does not accept any arguments")
    }
}

inline fun <reified T : Obj> ScopeFacade.thisAs(): T {
    val obj = thisObj
    return (obj as? T) ?: raiseClassCastError(
        "Cannot cast ${obj.objClass.className} to ${T::class.simpleName}"
    )
}

fun ScopeFacade.requireScope(): Scope =
    (this as? ScopeBridge)?.scope ?: raiseIllegalState("ScopeFacade requires ScopeBridge")
