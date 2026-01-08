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
        // Fast-path built-ins
        if (name == "this") return thisObj.asReadonly

        // Priority:
        // 1) Locals and arguments declared in this lambda frame (including values defined before suspension)
        // 2) Instance/class members of the captured receiver (`closureScope.thisObj`)
        // 3) Symbols from the captured closure scope chain (locals/parents), ignoring nested ClosureScope overrides
        // 4) Instance members of the caller's `this` (e.g., FlowBuilder.emit)
        // 5) Symbols from the caller chain (locals/parents), ignoring nested ClosureScope overrides
        // 6) Special fallback for module pseudo-symbols (e.g., __PACKAGE__)

        // 1) Locals/arguments in this closure frame
        super.objects[name]?.let { return it }
        super.localBindings[name]?.let { return it }

        // 1a) Priority: if we are in a class context, prefer our own private members to support 
        // non-virtual private dispatch. This prevents a subclass from accidentally 
        // capturing a private member call from a base class.
        // We only return non-field/non-property members (methods) here; fields must
        // be resolved via instance storage in priority 2.
        currentClassCtx?.let { ctx ->
            ctx.members[name]?.let { rec ->
                if (rec.visibility == Visibility.Private && 
                    rec.type != net.sergeych.lyng.obj.ObjRecord.Type.Field && 
                    rec.type != net.sergeych.lyng.obj.ObjRecord.Type.Property) return rec
            }
        }

        // 1b) Captured locals from the entire closure ancestry. This ensures that parameters
        // and local variables shadow members of captured receivers, matching standard
        // lexical scoping rules.
        closureScope.chainLookupIgnoreClosure(name, followClosure = true)?.let { return it }

        // 2) Members on the captured receiver instance
        (closureScope.thisObj as? net.sergeych.lyng.obj.ObjInstance)?.let { inst ->
            // Check direct locals in instance scope (unmangled)
            inst.instanceScope.objects[name]?.let { rec ->
                if (rec.type != net.sergeych.lyng.obj.ObjRecord.Type.Property && 
                    canAccessMember(rec.visibility, rec.declaringClass, currentClassCtx)) return rec
            }
            // Check mangled names for fields along MRO
            for (cls in inst.objClass.mro) {
                inst.instanceScope.objects["${cls.className}::$name"]?.let { rec ->
                    if (rec.type != net.sergeych.lyng.obj.ObjRecord.Type.Property && 
                        canAccessMember(rec.visibility, rec.declaringClass ?: cls, currentClassCtx)) return rec
                }
            }
        }

        findExtension(closureScope.thisObj.objClass, name)?.let { return it }
        closureScope.thisObj.objClass.getInstanceMemberOrNull(name)?.let { rec ->
            if (canAccessMember(rec.visibility, rec.declaringClass, currentClassCtx)) {
                // Return only non-field/non-property members (methods) from class-level records.
                // Fields and properties must be resolved via instance storage (mangled) or readField.
                if (rec.type != net.sergeych.lyng.obj.ObjRecord.Type.Field && 
                    rec.type != net.sergeych.lyng.obj.ObjRecord.Type.Property && 
                    !rec.isAbstract) return rec
            }
        }

        // 3) Closure scope chain (locals/parents + members), ignore ClosureScope overrides to prevent recursion
        closureScope.chainLookupWithMembers(name, currentClassCtx, followClosure = true)?.let { return it }

        // 4) Caller `this` members
        (callScope.thisObj as? net.sergeych.lyng.obj.ObjInstance)?.let { inst ->
            // Check direct locals in instance scope (unmangled)
            inst.instanceScope.objects[name]?.let { rec ->
                if (rec.type != net.sergeych.lyng.obj.ObjRecord.Type.Property && 
                    canAccessMember(rec.visibility, rec.declaringClass, currentClassCtx)) return rec
            }
            // Check mangled names for fields along MRO
            for (cls in inst.objClass.mro) {
                inst.instanceScope.objects["${cls.className}::$name"]?.let { rec ->
                    if (rec.type != net.sergeych.lyng.obj.ObjRecord.Type.Property && 
                        canAccessMember(rec.visibility, rec.declaringClass ?: cls, currentClassCtx)) return rec
                }
            }
        }
        findExtension(callScope.thisObj.objClass, name)?.let { return it }
        callScope.thisObj.objClass.getInstanceMemberOrNull(name)?.let { rec ->
            if (canAccessMember(rec.visibility, rec.declaringClass, currentClassCtx)) {
                if (rec.type != net.sergeych.lyng.obj.ObjRecord.Type.Field && 
                    rec.type != net.sergeych.lyng.obj.ObjRecord.Type.Property && 
                    !rec.isAbstract) return rec
            }
        }

        // 5) Caller chain (locals/parents + members)
        callScope.chainLookupWithMembers(name, currentClassCtx)?.let { return it }

        // 6) Module pseudo-symbols (e.g., __PACKAGE__) — walk caller ancestry and query ModuleScope directly
        if (name.startsWith("__")) {
            var s: Scope? = callScope
            val visited = HashSet<Long>(4)
            while (s != null) {
                if (!visited.add(s.frameId)) break
                if (s is ModuleScope) return s.get(name)
                s = s.parent
            }
        }

        // 7) Direct module/global fallback: try to locate nearest ModuleScope and check its own locals
        fun lookupInModuleAncestry(from: Scope): ObjRecord? {
            var s: Scope? = from
            val visited = HashSet<Long>(4)
            while (s != null) {
                if (!visited.add(s.frameId)) break
                if (s is ModuleScope) {
                    s.objects[name]?.let { return it }
                    s.localBindings[name]?.let { return it }
                    // check immediate parent (root scope) locals/constants for globals like `delay`
                    val p = s.parent
                    if (p != null) {
                        p.objects[name]?.let { return it }
                        p.localBindings[name]?.let { return it }
                        p.thisObj.objClass.getInstanceMemberOrNull(name)?.let { return it }
                    }
                    return null
                }
                s = s.parent
            }
            return null
        }

        lookupInModuleAncestry(closureScope)?.let { return it }
        lookupInModuleAncestry(callScope)?.let { return it }

        // 8) Global root scope constants/functions (e.g., delay, yield) via current import provider
        runCatching { this.currentImportProvider.rootScope.objects[name] }.getOrNull()?.let { return it }

        // Final safe fallback: base scope lookup from this frame walking raw parents
        return baseGetIgnoreClosure(name)
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