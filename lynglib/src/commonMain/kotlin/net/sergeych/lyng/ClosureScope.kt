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

package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjRecord

/**
 * Bytecode-oriented closure scope that keeps the call scope parent chain for stack traces
 * while carrying the lexical closure for `this` variants and module resolution.
 * Unlike interpreter closure scopes, it does not override name lookup.
 */
class BytecodeClosureScope(
    val callScope: Scope,
    val closureScope: Scope,
    private val preferredThisType: String? = null
) :
    Scope(callScope, callScope.args, thisObj = closureScope.thisObj) {

    init {
        val desired = preferredThisType?.let { typeName ->
            callScope.thisVariants.firstOrNull { it.objClass.className == typeName }
        }
        val primaryThis = closureScope.thisObj
        val merged = ArrayList<Obj>(callScope.thisVariants.size + closureScope.thisVariants.size + 1)
        desired?.let { merged.add(it) }
        merged.addAll(callScope.thisVariants)
        merged.addAll(closureScope.thisVariants)
        setThisVariants(primaryThis, merged)
        this.currentClassCtx = closureScope.currentClassCtx ?: callScope.currentClassCtx
    }
}

class ApplyScope(val callScope: Scope, val applied: Scope) :
    Scope(callScope, thisObj = applied.thisObj) {

    override fun get(name: String): ObjRecord? {
        return applied.get(name) ?: super.get(name)
    }

    override fun applyClosure(closure: Scope, preferredThisType: String?): Scope {
        return BytecodeClosureScope(this, closure, preferredThisType)
    }

}
