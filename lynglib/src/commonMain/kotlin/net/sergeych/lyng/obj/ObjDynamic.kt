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

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Scope
import net.sergeych.lyng.bytecode.BytecodeLambdaCallable

class ObjDynamicContext(val delegate: ObjDynamic) : Obj() {
    override val objClass: ObjClass get() = type

    companion object {
        val type = ObjClass("DelegateContext").apply {
            addFn("get") {
                val d = thisAs<ObjDynamicContext>().delegate
                if (d.readCallback != null)
                    raiseIllegalState("get already defined")
                val callback = requireOnlyArg<Obj>()
                d.readCallback = d.rebindCallback(requireScope(), callback)
                ObjVoid
            }

            addFn("set") {
                val d = thisAs<ObjDynamicContext>().delegate
                if (d.writeCallback != null)
                    raiseIllegalState("set already defined")
                val callback = requireOnlyArg<Obj>()
                d.writeCallback = d.rebindCallback(requireScope(), callback)
                ObjVoid
            }

        }

    }
}

/**
 * Object that delegates all its field access/invocation operations to a callback. It is used to implement dynamic
 * objects using "dynamic" keyword.
 */
open class ObjDynamic(var readCallback: Obj? = null, var writeCallback: Obj? = null) : Obj() {

    override val objClass: ObjClass get() = type
    // Capture the lexical scope used to build this dynamic so callbacks can see outer locals
    internal var builderScope: Scope? = null
    internal fun rebindCallback(contextScope: Scope, callback: Obj): Obj {
        val snapshot = builderScope ?: return callback
        val context = Scope(snapshot, contextScope.args, contextScope.pos, contextScope.thisObj)
        return (callback as? BytecodeLambdaCallable)?.rebindClosure(context) ?: callback
    }

    private suspend fun callCallback(callback: Obj, child: Scope): Obj {
        return (callback as? net.sergeych.lyng.BytecodeCallable)?.callOnFast(child) ?: callback.callOn(child)
    }

    /**
     * Use read callback to dynamically resolve the field name. Note that it does not work
     * with method invocation which is implemented separately in [invokeInstanceMethod] below.
     */
    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        val execBase = builderScope?.let { scope.applyClosure(it) } ?: scope
        return readCallback?.let { callback ->
            callCallback(callback, execBase.createChildScope(Arguments(ObjString(name))))
        }?.let {
            if (writeCallback != null)
                it.asMutable
            else
                it.asReadonly
        }
            ?: super.readField(scope, name)
    }

    /**
     * Notice that invocation currently does not rely on [readField], which is a buffy moment to be reconsidered
     * in the future, so we implement it separately:
     */
    override suspend fun invokeInstanceMethod(
        scope: Scope,
        name: String,
        args: Arguments,
        onNotFoundResult: (suspend () -> Obj?)?
    ): Obj {
        val execBase = builderScope?.let { scope.applyClosure(it) } ?: scope
        val over = readCallback?.let { callback ->
            callCallback(callback, execBase.createChildScope(Arguments(ObjString(name))))
        }
        return over?.invoke(scope, scope.thisObj, args)
            ?: super.invokeInstanceMethod(scope, name, args, onNotFoundResult)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        val execBase = builderScope?.let { scope.applyClosure(it) } ?: scope
        writeCallback?.let { callback ->
            callCallback(callback, execBase.createChildScope(Arguments(ObjString(name), newValue)))
        }
            ?: super.writeField(scope, name, newValue)
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        val execBase = builderScope?.let { scope.applyClosure(it) } ?: scope
        return readCallback?.let { callback ->
            callCallback(callback, execBase.createChildScope(Arguments(index)))
        }
            ?: super.getAt(scope, index)
    }

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        val execBase = builderScope?.let { scope.applyClosure(it) } ?: scope
        writeCallback?.let { callback ->
            callCallback(callback, execBase.createChildScope(Arguments(index, newValue)))
        }
            ?: super.putAt(scope, index, newValue)
    }

    companion object {

        suspend fun create(scope: Scope, builder: Obj): ObjDynamic {
            val delegate = ObjDynamic()
            val context = ObjDynamicContext(delegate)
            // Capture the function's lexical scope (scope) so callbacks can see outer locals like parameters.
            // Build the dynamic in a child scope purely to set `this` to context, but keep captured closure at parent.
            val buildScope = scope.createChildScope(newThisObj = context)
            // Snapshot the caller scope to capture locals/args even if the runtime pools/reuses frames.
            // Module scope should stay late-bound to allow extern class rebinding and similar updates.
            delegate.builderScope = if (scope is net.sergeych.lyng.ModuleScope) null else scope.snapshotForClosure()
            (builder as? net.sergeych.lyng.BytecodeCallable)?.callOnFast(buildScope) ?: builder.callOn(buildScope)
            return delegate
        }

        val type = object : ObjClass("Delegate") {}.apply {
            logicalPackageNameOverride = "lyng.stdlib"
            addFn("getValue") { raiseError("Delegate.getValue is not implemented") }
            addFn("setValue") { raiseError("Delegate.setValue is not implemented") }
            addFn("invoke") { raiseError("Delegate.invoke is not implemented") }
            addFn("bind") { raiseError("Delegate.bind is not implemented") }
//            addClassConst("IndexGetName", operatorGetName)
        }
    }

}
