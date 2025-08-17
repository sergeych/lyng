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

package net.sergeych.lyng

import net.sergeych.lyng.obj.ObjRecord

/**
 * Scope that adds a "closure" to caller; most often it is used to apply class instance to caller scope.
 * Inherits [Scope.args] and [Scope.thisObj] from [callScope] and adds lookup for symbols
 * from [closureScope] with proper precedence
 */
class ClosureScope(val callScope: Scope, val closureScope: Scope) :
    Scope(callScope, callScope.args, thisObj = callScope.thisObj) {

    override fun get(name: String): ObjRecord? {
        // we take arguments from the callerScope, the rest
        // from the closure.

        // note using super, not callScope, as arguments are assigned by the constructor
        // and are not assigned yet to vars in callScope self:
        super.objects[name]?.let {
//            if( name == "predicate" ) {
//                println("predicate: ${it.type.isArgument}: ${it.value}")
//            }
            if( it.type.isArgument ) return it
        }
        return closureScope.get(name)
    }
}

class ApplyScope(_parent: Scope,val applied: Scope) : Scope(_parent, thisObj = applied.thisObj) {

    override fun get(name: String): ObjRecord? {
        return applied.get(name) ?: super.get(name)
    }

    override fun applyClosure(closure: Scope): Scope {
        return this
    }

}