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
import net.sergeych.lyng.canAccessMember
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjInstance(override val objClass: ObjClass) : Obj() {

    internal lateinit var instanceScope: Scope

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        // Direct (unmangled) lookup first
        instanceScope[name]?.let {
            val decl = it.declaringClass ?: objClass.findDeclaringClassOf(name)
            val caller = scope.currentClassCtx ?: instanceScope.currentClassCtx
            val allowed = if (it.visibility == net.sergeych.lyng.Visibility.Private) (decl === objClass) else canAccessMember(it.visibility, decl, caller)
            if (!allowed)
                scope.raiseError(ObjAccessException(scope, "can't access field $name (declared in ${decl?.className ?: "?"})"))
            return it
        }
        // Try MI-mangled lookup along linearization (C3 MRO): ClassName::name
        val cls = objClass
        // self first, then parents
        fun findMangled(): ObjRecord? {
            // self
            instanceScope.objects["${cls.className}::$name"]?.let { return it }
            // ancestors in deterministic C3 order
            for (p in cls.mroParents) {
                instanceScope.objects["${p.className}::$name"]?.let { return it }
            }
            return null
        }
        findMangled()?.let { rec ->
            val declName = rec.importedFrom?.packageName // unused; use mangled key instead
            // derive declaring class by mangled prefix: try self then parents
            val declaring = when {
                instanceScope.objects.containsKey("${cls.className}::$name") -> cls
                else -> cls.mroParents.firstOrNull { instanceScope.objects.containsKey("${it.className}::$name") }
            }
            val caller = scope.currentClassCtx ?: instanceScope.currentClassCtx
            val allowed = if (rec.visibility == net.sergeych.lyng.Visibility.Private) (declaring === objClass) else canAccessMember(rec.visibility, declaring, caller)
            if (!allowed)
                scope.raiseError(ObjAccessException(scope, "can't access field $name (declared in ${declaring?.className ?: "?"})"))
            return rec
        }
        // Fall back to methods/properties on class
        return super.readField(scope, name)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        // Direct (unmangled) first
        instanceScope[name]?.let { f ->
            val decl = f.declaringClass ?: objClass.findDeclaringClassOf(name)
            val caller = scope.currentClassCtx ?: instanceScope.currentClassCtx
            val allowed = if (f.visibility == net.sergeych.lyng.Visibility.Private) (decl === objClass) else canAccessMember(f.visibility, decl, caller)
            if (!allowed)
                ObjIllegalAssignmentException(scope, "can't assign to field $name (declared in ${decl?.className ?: "?"})").raise()
            if (!f.isMutable) ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
            if (f.value.assign(scope, newValue) == null)
                f.value = newValue
            return
        }
        // Try MI-mangled resolution along linearization (C3 MRO)
        val cls = objClass
        fun findMangled(): ObjRecord? {
            instanceScope.objects["${cls.className}::$name"]?.let { return it }
            for (p in cls.mroParents) {
                instanceScope.objects["${p.className}::$name"]?.let { return it }
            }
            return null
        }
        val rec = findMangled()
        if (rec != null) {
            val declaring = when {
                instanceScope.objects.containsKey("${cls.className}::$name") -> cls
                else -> cls.mroParents.firstOrNull { instanceScope.objects.containsKey("${it.className}::$name") }
            }
            val caller = scope.currentClassCtx ?: instanceScope.currentClassCtx
            val allowed = if (rec.visibility == net.sergeych.lyng.Visibility.Private) (declaring === objClass) else canAccessMember(rec.visibility, declaring, caller)
            if (!allowed)
                ObjIllegalAssignmentException(scope, "can't assign to field $name (declared in ${declaring?.className ?: "?"})").raise()
            if (!rec.isMutable) ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
            if (rec.value.assign(scope, newValue) == null)
                rec.value = newValue
            return
        }
        super.writeField(scope, name, newValue)
    }

    override suspend fun invokeInstanceMethod(scope: Scope, name: String, args: Arguments,
                                              onNotFoundResult: (()->Obj?)?): Obj =
        instanceScope[name]?.let { rec ->
            val decl = rec.declaringClass ?: objClass.findDeclaringClassOf(name)
            val caller = scope.currentClassCtx ?: instanceScope.currentClassCtx
            val allowed = if (rec.visibility == net.sergeych.lyng.Visibility.Private) (decl === objClass) else canAccessMember(rec.visibility, decl, caller)
            if (!allowed)
                scope.raiseError(ObjAccessException(scope, "can't invoke method $name (declared in ${decl?.className ?: "?"})"))
            // execute with lexical class context propagated to declaring class
            val saved = instanceScope.currentClassCtx
            instanceScope.currentClassCtx = decl
            try {
                rec.value.invoke(
                    instanceScope,
                    this,
                    args)
            } finally {
                instanceScope.currentClassCtx = saved
            }
        }
            ?: run {
                // fallback: class-scope function (registered during class body execution)
                objClass.classScope?.objects?.get(name)?.let { rec ->
                    val decl = rec.declaringClass ?: objClass.findDeclaringClassOf(name)
                    val caller = scope.currentClassCtx
                    if (!canAccessMember(rec.visibility, decl, caller))
                        scope.raiseError(ObjAccessException(scope, "can't invoke method $name (declared in ${decl?.className ?: "?"})"))
                    val saved = instanceScope.currentClassCtx
                    instanceScope.currentClassCtx = decl
                    try {
                        rec.value.invoke(instanceScope, this, args)
                    } finally {
                        instanceScope.currentClassCtx = saved
                    }
                }
            }
            ?: super.invokeInstanceMethod(scope, name, args, onNotFoundResult)

    private val publicFields: Map<String, ObjRecord>
        get() = instanceScope.objects.filter { it.value.visibility.isPublic && it.value.type.serializable }

    override fun toString(): String {
        val fields = publicFields.map { "${it.key}=${it.value.value}" }.joinToString(",")
        return "${objClass.className}($fields)"
    }

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        val meta = objClass.constructorMeta
            ?: scope.raiseError("can't serialize non-serializable object (no constructor meta)")
        // actual constructor can vary, for example, adding new fields with default
        // values, so we save size of the construction:

        // using objlist allow for some optimizations:
        val params = meta.params.map { readField(scope, it.name).value }
        encoder.encodeAnyList(scope, params)
        serializeStateVars(scope, encoder)
    }

    protected val instanceVars: Map<String, ObjRecord> by lazy {
        instanceScope.objects.filter { it.value.type.serializable }
    }

    protected suspend fun serializeStateVars(scope: Scope,encoder: LynonEncoder) {
        val vars = instanceVars.values.map { it.value }
        if( vars.isNotEmpty()) {
            encoder.encodeAnyList(scope, vars)
        }
    }

    internal suspend fun deserializeStateVars(scope: Scope, decoder: LynonDecoder) {
        val localVars = instanceVars.values.toList()
        if( localVars.isNotEmpty() ) {
            val vars = decoder.decodeAnyList(scope)
            if (vars.size > instanceVars.size)
                scope.raiseIllegalArgument("serialized vars has bigger size than instance vars")
            for ((i, v) in vars.withIndex()) {
                localVars[i].value = v
            }
        }
    }

    protected val comparableVars: Map<String, ObjRecord> by lazy {
        instanceScope.objects.filter {
            it.value.type.comparable
        }
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is ObjInstance) return -1
        if (other.objClass != objClass) return -1
        for (f in comparableVars) {
            val a = f.value.value
            val b = other.instanceScope[f.key]!!.value
            val d = a.compareTo(scope, b)
            if (d != 0) return d
        }
        return 0
    }
}

/**
 * A qualified view over an [ObjInstance] that resolves members starting from a specific ancestor class.
 * It does not change identity; it only affects lookup precedence for fields and methods.
 */
class ObjQualifiedView(val instance: ObjInstance, private val startClass: ObjClass) : Obj() {
    override val objClass: ObjClass get() = instance.objClass

    private fun memberFromAncestor(name: String): ObjRecord? =
        instance.objClass.getInstanceMemberFromAncestor(startClass, name)

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        // Qualified field access: prefer mangled storage for the qualified ancestor
        val mangled = "${startClass.className}::$name"
        instance.instanceScope.objects[mangled]?.let { rec ->
            // Visibility: declaring class is the qualified ancestor for mangled storage
            val decl = rec.declaringClass ?: startClass
            val caller = scope.currentClassCtx
            if (!net.sergeych.lyng.canAccessMember(rec.visibility, decl, caller))
                scope.raiseError(ObjAccessException(scope, "can't access field $name (declared in ${decl.className})"))
            return rec
        }
        // Then try instance locals (unmangled) only if startClass is the dynamic class itself
        if (startClass === instance.objClass) {
            instance.instanceScope[name]?.let { rec ->
                val decl = rec.declaringClass ?: instance.objClass.findDeclaringClassOf(name)
                val caller = scope.currentClassCtx
                if (!net.sergeych.lyng.canAccessMember(rec.visibility, decl, caller))
                    scope.raiseError(ObjAccessException(scope, "can't access field $name (declared in ${decl?.className ?: "?"})"))
                return rec
            }
        }
        // Finally try methods/properties starting from ancestor
        val r = memberFromAncestor(name) ?: scope.raiseError("no such field: $name")
        val decl = r.declaringClass ?: startClass
        val caller = scope.currentClassCtx
        if (!net.sergeych.lyng.canAccessMember(r.visibility, decl, caller))
            scope.raiseError(ObjAccessException(scope, "can't access field $name (declared in ${decl.className})"))
        return when (val value = r.value) {
            is net.sergeych.lyng.Statement -> ObjRecord(value.execute(instance.instanceScope.createChildScope(scope.pos, newThisObj = instance)), r.isMutable)
            else -> r
        }
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        // Qualified write: target mangled storage for the ancestor
        val mangled = "${startClass.className}::$name"
        instance.instanceScope.objects[mangled]?.let { f ->
            val decl = f.declaringClass ?: startClass
            val caller = scope.currentClassCtx
            if (!net.sergeych.lyng.canAccessMember(f.visibility, decl, caller))
                ObjIllegalAssignmentException(scope, "can't assign to field $name (declared in ${decl.className})").raise()
            if (!f.isMutable) ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
            if (f.value.assign(scope, newValue) == null) f.value = newValue
            return
        }
        // If start is dynamic class, allow unmangled
        if (startClass === instance.objClass) {
            instance.instanceScope[name]?.let { f ->
                val decl = f.declaringClass ?: instance.objClass.findDeclaringClassOf(name)
                val caller = scope.currentClassCtx
                if (!net.sergeych.lyng.canAccessMember(f.visibility, decl, caller))
                    ObjIllegalAssignmentException(scope, "can't assign to field $name (declared in ${decl?.className ?: "?"})").raise()
                if (!f.isMutable) ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
                if (f.value.assign(scope, newValue) == null) f.value = newValue
                return
            }
        }
        val r = memberFromAncestor(name) ?: scope.raiseError("no such field: $name")
        val decl = r.declaringClass ?: startClass
        val caller = scope.currentClassCtx
        if (!net.sergeych.lyng.canAccessMember(r.visibility, decl, caller))
            ObjIllegalAssignmentException(scope, "can't assign to field $name (declared in ${decl.className})").raise()
        if (!r.isMutable) scope.raiseError("can't assign to read-only field: $name")
        if (r.value.assign(scope, newValue) == null) r.value = newValue
    }

    override suspend fun invokeInstanceMethod(scope: Scope, name: String, args: Arguments, onNotFoundResult: (() -> Obj?)?): Obj {
        // Qualified method dispatch must start from the specified ancestor, not from the instance scope.
        memberFromAncestor(name)?.let { rec ->
            val decl = rec.declaringClass ?: startClass
            val caller = scope.currentClassCtx
            if (!net.sergeych.lyng.canAccessMember(rec.visibility, decl, caller))
                scope.raiseError(ObjAccessException(scope, "can't invoke method $name (declared in ${decl.className})"))
            val saved = instance.instanceScope.currentClassCtx
            instance.instanceScope.currentClassCtx = decl
            try {
                return rec.value.invoke(instance.instanceScope, instance, args)
            } finally {
                instance.instanceScope.currentClassCtx = saved
            }
        }
        // If the qualifier is the dynamic class itself, allow instance-scope methods as a fallback
        if (startClass === instance.objClass) {
            instance.instanceScope[name]?.let { rec ->
                val decl = rec.declaringClass ?: instance.objClass.findDeclaringClassOf(name)
                val caller = scope.currentClassCtx
                if (!net.sergeych.lyng.canAccessMember(rec.visibility, decl, caller))
                    scope.raiseError(ObjAccessException(scope, "can't invoke method $name (declared in ${decl?.className ?: "?"})"))
                val saved = instance.instanceScope.currentClassCtx
                instance.instanceScope.currentClassCtx = decl
                try {
                    return rec.value.invoke(instance.instanceScope, instance, args)
                } finally {
                    instance.instanceScope.currentClassCtx = saved
                }
            }
        }
        return onNotFoundResult?.invoke() ?: scope.raiseSymbolNotFound(name)
    }

    override fun toString(): String = instance.toString()
}