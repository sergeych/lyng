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
import net.sergeych.lyng.obj.ObjClass
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

    init {
        // Preserve the lexical class context of the closure by default. This ensures that lambdas
        // created inside a class method keep access to that class's private/protected members even
        // when executed from within another object's method (e.g., Mutex.withLock), which may set
        // its own currentClassCtx temporarily. If the closure has no class context, inherit caller's.
        this.currentClassCtx = closureScope.currentClassCtx ?: callScope.currentClassCtx
    }

    override fun get(name: String): ObjRecord? {
        if (name == "this") return thisObj.asReadonly

        // 1. Current frame locals (parameters, local variables)
        tryGetLocalRecord(this, name, currentClassCtx)?.let { return it }

        // 2. Lexical environment (captured locals from entire ancestry)
        closureScope.chainLookupIgnoreClosure(name, followClosure = true, caller = currentClassCtx)?.let { return it }

        // 3. Lexical this members (captured receiver)
        val receiver = thisObj
        val effectiveClass = receiver as? ObjClass ?: receiver.objClass
        for (cls in effectiveClass.mro) {
            val rec = cls.members[name] ?: cls.classScope?.objects?.get(name)
            if (rec != null && !rec.isAbstract) {
                if (canAccessMember(rec.visibility, rec.declaringClass ?: cls, currentClassCtx)) {
                    return rec.copy(receiver = receiver)
                }
            }
        }
        // Finally, root object fallback
        Obj.rootObjectType.members[name]?.let { rec ->
            if (canAccessMember(rec.visibility, rec.declaringClass, currentClassCtx)) {
                return rec.copy(receiver = receiver)
            }
        }

        // 4. Call environment (caller locals, caller this, and global fallback)
        return callScope.get(name)
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