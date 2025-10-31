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

class ObjDynamic : Obj() {

    internal var readCallback: Statement? = null
    internal var writeCallback: Statement? = null

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        return readCallback?.execute(scope.createChildScope(Arguments(ObjString(name))))?.let {
            if (writeCallback != null)
                it.asMutable
            else
                it.asReadonly
        }
            ?: super.readField(scope, name)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        writeCallback?.execute(scope.createChildScope(Arguments(ObjString(name), newValue)))
            ?: super.writeField(scope, name, newValue)
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        return readCallback?.execute( scope.createChildScope(Arguments(index)))
            ?: super.getAt(scope, index)
    }

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        writeCallback?.execute(scope.createChildScope(Arguments(index, newValue)))
            ?: super.putAt(scope, index, newValue)
    }

    companion object {

        suspend fun create(scope: Scope, builder: Statement): ObjDynamic {
            val delegate = ObjDynamic()
            val context = ObjDynamicContext(delegate)
            builder.execute(scope.createChildScope(newThisObj = context))
            return delegate
        }

        val type = object : ObjClass("Delegate") {}.apply {
//            addClassConst("IndexGetName", operatorGetName)
        }
    }

}