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
 *
 */

package net.sergeych.lyng.obj

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Scope

class ObjExtensionMethodCallable(
    private val name: String,
    private val target: Obj,
    private val declaringClass: ObjClass? = null
) : Obj() {
    override suspend fun callOn(scope: Scope): Obj {
        val args = scope.args
        if (args.isEmpty()) scope.raiseError("extension call $name requires receiver")
        val receiver = args.first()
        val rest = if (args.size <= 1) {
            Arguments.EMPTY
        } else {
            Arguments(args.list.subList(1, args.size), args.tailBlockMode, args.named)
        }
        return target.invoke(scope, receiver, rest, declaringClass)
    }
}

class ObjExtensionPropertyGetterCallable(
    private val name: String,
    private val property: ObjProperty,
    private val declaringClass: ObjClass? = null
) : Obj() {
    override suspend fun callOn(scope: Scope): Obj {
        val args = scope.args
        if (args.isEmpty()) scope.raiseError("extension property $name requires receiver")
        val receiver = args.first()
        if (args.size > 1) scope.raiseError("extension property $name getter takes no arguments")
        return property.callGetter(scope, receiver, declaringClass)
    }
}

class ObjExtensionPropertySetterCallable(
    private val name: String,
    private val property: ObjProperty,
    private val declaringClass: ObjClass? = null
) : Obj() {
    override suspend fun callOn(scope: Scope): Obj {
        val args = scope.args
        if (args.size < 2) scope.raiseError("extension property $name setter requires value")
        val receiver = args[0]
        val value = args[1]
        property.callSetter(scope, receiver, value, declaringClass)
        return ObjVoid
    }
}
