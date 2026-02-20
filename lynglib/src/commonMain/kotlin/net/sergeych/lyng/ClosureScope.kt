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
 * Unlike legacy closure scopes, it does not override name lookup.
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
        val primaryThis = when {
            callScope is ApplyScope -> callScope.thisObj
            desired != null -> desired
            else -> closureScope.thisObj
        }
        val merged = ArrayList<Obj>(callScope.thisVariants.size + closureScope.thisVariants.size + 3)
        desired?.let { merged.add(it) }
        merged.add(callScope.thisObj)
        merged.addAll(callScope.thisVariants)
        if (callScope is ApplyScope) {
            merged.add(callScope.applied.thisObj)
            merged.addAll(callScope.applied.thisVariants)
        }
        merged.addAll(closureScope.thisVariants)
        setThisVariants(primaryThis, merged)
        this.currentClassCtx = closureScope.currentClassCtx ?: callScope.currentClassCtx
    }
}

class ApplyScope(val callScope: Scope, val applied: Scope) :
    Scope(applied, callScope.args, callScope.pos, callScope.thisObj) {

    init {
        // Merge applied receiver variants with the caller variants so qualified this@Type
        // can see both the applied receiver and outer receivers.
        val merged = ArrayList<Obj>(applied.thisVariants.size + callScope.thisVariants.size + 1)
        merged.addAll(applied.thisVariants)
        merged.addAll(callScope.thisVariants)
        setThisVariants(callScope.thisObj, merged)
        this.currentClassCtx = applied.currentClassCtx ?: callScope.currentClassCtx
    }

    override fun get(name: String): ObjRecord? {
        return applied.get(name) ?: callScope.get(name)
    }

    override fun applyClosure(closure: Scope, preferredThisType: String?): Scope {
        return BytecodeClosureScope(this, closure, preferredThisType)
    }

}
