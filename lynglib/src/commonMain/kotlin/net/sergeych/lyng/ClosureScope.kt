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
    // Important: use closureScope.thisObj so unqualified members (e.g., fields) resolve to the instance
    // we captured, not to the caller's `this` (e.g., FlowBuilder).
    Scope(callScope, callScope.args, thisObj = closureScope.thisObj) {

    override fun get(name: String): ObjRecord? {
        // Priority:
        // 1) Arguments from the caller scope (if present in this frame)
        // 2) Instance/class members of the captured receiver (`closureScope.thisObj`), e.g., fields like `coll`, `factor`
        // 3) Symbols from the captured closure scope (its locals and parents)
        // 4) Instance members of the caller's `this` (e.g., FlowBuilder.emit)
        // 5) Fallback to the standard chain (this frame -> parent (callScope) -> class members)

        // note using super, not callScope, as arguments are assigned by the constructor
        // and are not yet exposed via callScope.get at this point:
        super.objects[name]?.let { if (it.type.isArgument) return it }

        // Prefer instance fields/methods declared on the captured receiver:
        // First, resolve real instance fields stored in the instance scope (constructor vars like `coll`, `factor`)
        (closureScope.thisObj as? net.sergeych.lyng.obj.ObjInstance)
            ?.instanceScope
            ?.objects
            ?.get(name)
            ?.let { return it }

        // Then, try class-declared members (methods/properties declared in the class body)
        closureScope.thisObj.objClass.getInstanceMemberOrNull(name)?.let { return it }

        // Then delegate to the full closure scope chain (locals, parents, etc.)
        closureScope.get(name)?.let { return it }

        // Allow resolving instance members of the caller's `this` (e.g., FlowBuilder.emit)
        callScope.thisObj.objClass.getInstanceMemberOrNull(name)?.let { return it }

        // Fallback to the standard lookup chain: this frame -> parent (callScope) -> class members
        return super.get(name)
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