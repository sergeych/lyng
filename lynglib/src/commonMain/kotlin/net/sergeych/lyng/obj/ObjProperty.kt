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

/**
 * Property accessor storage. Per instructions, properties do NOT have
 * automatic backing fields. They are pure accessors.
 */
class ObjProperty(
    val name: String,
    val getter: Statement?,
    val setter: Statement?
) : Obj() {

    suspend fun callGetter(scope: Scope, instance: Obj, declaringClass: ObjClass? = null): Obj {
        val g = getter ?: scope.raiseError("property $name has no getter")
        // Execute getter in a child scope of the instance with 'this' properly set
        // Use ClosureScope to match extension function behavior (access to instance scope + call scope)
        val instanceScope = (instance as? ObjInstance)?.instanceScope ?: instance.autoInstanceScope(scope)
        val execScope = ClosureScope(scope, instanceScope).createChildScope(newThisObj = instance)
        execScope.currentClassCtx = declaringClass
        return g.execute(execScope)
    }

    suspend fun callSetter(scope: Scope, instance: Obj, value: Obj, declaringClass: ObjClass? = null) {
        val s = setter ?: scope.raiseError("property $name has no setter")
        // Execute setter in a child scope of the instance with 'this' properly set and the value as an argument
        // Use ClosureScope to match extension function behavior
        val instanceScope = (instance as? ObjInstance)?.instanceScope ?: instance.autoInstanceScope(scope)
        val execScope = ClosureScope(scope, instanceScope).createChildScope(args = Arguments(value), newThisObj = instance)
        execScope.currentClassCtx = declaringClass
        s.execute(execScope)
    }

    override fun toString(): String = "Property($name)"
}
