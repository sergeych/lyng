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

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.ClosureScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Statement

class ObjDynamicContext(val delegate: ObjDynamic) : Obj() {
    override val objClass: ObjClass = type

    companion object {
        val type = ObjClass("DelegateContext").apply {
            addFn("get") {
                val d = thisAs<ObjDynamicContext>().delegate
                if (d.readCallback != null)
                    raiseIllegalState("get already defined")
                d.readCallback = requireOnlyArg()
                ObjVoid
            }

            addFn("set") {
                val d = thisAs<ObjDynamicContext>().delegate
                if (d.writeCallback != null)
                    raiseIllegalState("set already defined")
                d.writeCallback = requireOnlyArg()
                ObjVoid
            }

        }

    }
}

/**
 * Object that delegates all its field access/invocation operations to a callback. It is used to implement dynamic
 * objects using "dynamic" keyword.
 */
open class ObjDynamic(var readCallback: Statement? = null, var writeCallback: Statement? = null) : Obj() {

    override val objClass: ObjClass = type
    // Capture the lexical scope used to build this dynamic so callbacks can see outer locals
    internal var builderScope: Scope? = null

    /**
     * Use read callback to dynamically resolve the field name. Note that it does not work
     * with method invocation which is implemented separately in [invokeInstanceMethod] below.
     */
    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        val execBase = builderScope?.let { ClosureScope(scope, it) } ?: scope
        return readCallback?.execute(execBase.createChildScope(Arguments(ObjString(name))))?.let {
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
        val execBase = builderScope?.let { ClosureScope(scope, it) } ?: scope
        val over = readCallback?.execute(execBase.createChildScope(Arguments(ObjString(name))))
        return over?.invoke(scope, scope.thisObj, args)
            ?: super.invokeInstanceMethod(scope, name, args, onNotFoundResult)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        val execBase = builderScope?.let { ClosureScope(scope, it) } ?: scope
        writeCallback?.execute(execBase.createChildScope(Arguments(ObjString(name), newValue)))
            ?: super.writeField(scope, name, newValue)
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        val execBase = builderScope?.let { ClosureScope(scope, it) } ?: scope
        return readCallback?.execute(execBase.createChildScope(Arguments(index)))
            ?: super.getAt(scope, index)
    }

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        val execBase = builderScope?.let { ClosureScope(scope, it) } ?: scope
        writeCallback?.execute(execBase.createChildScope(Arguments(index, newValue)))
            ?: super.putAt(scope, index, newValue)
    }

    companion object {

        suspend fun create(scope: Scope, builder: Statement): ObjDynamic {
            val delegate = ObjDynamic()
            val context = ObjDynamicContext(delegate)
            // Capture the function's lexical scope (scope) so callbacks can see outer locals like parameters.
            // Build the dynamic in a child scope purely to set `this` to context, but keep captured closure at parent.
            val buildScope = scope.createChildScope(newThisObj = context)
            // Snapshot the caller scope to capture locals/args even if the runtime pools/reuses frames
            delegate.builderScope = scope.snapshotForClosure()
            builder.execute(buildScope)
            return delegate
        }

        val type = object : ObjClass("Delegate") {}.apply {
//            addClassConst("IndexGetName", operatorGetName)
        }
    }

}