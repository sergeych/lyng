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
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonType

/**
 * Special variant of [ObjClass] to be used in [ObjInstance], e.g. for Lyng compiled classes
 */
class ObjInstanceClass(val name: String, vararg parents: ObjClass) : ObjClass(name, *parents) {

    override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
        val serializedArgs = decoder.decodeAnyList(scope)
        val meta = constructorMeta ?: scope.raiseError("no constructor meta for $name")
        val nonTransientCount = meta.params.count { !it.isTransient }
        if (serializedArgs.size != nonTransientCount)
            scope.raiseIllegalArgument("constructor $name expects $nonTransientCount non-transient arguments, but serialized version has ${serializedArgs.size}")
        
        var sIdx = 0
        val namedArgs = mutableMapOf<String, Obj>()
        for (p in meta.params) {
            if (!p.isTransient) {
                namedArgs[p.name] = serializedArgs[sIdx++]
            } else if (p.defaultValue == null) {
                // If transient parameter has no default value, we use ObjNull to avoid "too few arguments" error
                namedArgs[p.name] = ObjNull
            }
        }
        // Using named arguments allows the constructor to apply default values for transient parameters
        val newArgs = Arguments(list = emptyList<Obj>(), named = namedArgs)
        val instance = createInstance(scope.createChildScope(args = newArgs))
        initializeInstance(instance, newArgs, runConstructors = false)
        return instance.apply {
            deserializeStateVars(scope, decoder)
            invokeInstanceMethod(scope, "onDeserialized") { ObjVoid }
        }
    }

    init {
    }

}