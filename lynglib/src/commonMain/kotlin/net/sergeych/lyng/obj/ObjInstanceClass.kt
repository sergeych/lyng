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
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonType

/**
 * Special variant of [ObjClass] to be used in [ObjInstance], e.g. for Lyng compiled classes
 */
class ObjInstanceClass(val name: String, vararg parents: ObjClass) : ObjClass(name, *parents) {

    override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
        val args = decoder.decodeAnyList(scope)
        val actualSize = constructorMeta?.params?.size ?: 0
        if (args.size > actualSize)
            scope.raiseIllegalArgument("constructor $name has only $actualSize but serialized version has ${args.size}")
        val newScope = scope.createChildScope(args = Arguments(args))
        val instance = createInstance(newScope)
        initializeInstance(instance, newScope.args, runConstructors = false)
        return instance.apply {
            deserializeStateVars(scope, decoder)
            invokeInstanceMethod(scope, "onDeserialized") { ObjVoid }
        }
    }

    init {
        addFn("toString", true) {
            thisObj.toString(this, true)
        }
    }

}