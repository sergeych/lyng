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

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Visibility
import net.sergeych.lyng.canAccessMember
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjInstance(override val objClass: ObjClass) : Obj() {

    internal lateinit var instanceScope: Scope

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        val caller = scope.currentClassCtx
        
        // Fast path for public members when outside any class context
        if (caller == null) {
            objClass.publicMemberResolution[name]?.let { key ->
                instanceScope.objects[key]?.let { rec ->
                    // Directly return fields to bypass resolveRecord overhead
                    if ((rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField) && !rec.isAbstract) 
                        return rec
                    return resolveRecord(scope, rec, name, rec.declaringClass)
                }
            }
        }

        // 0. Private mangled of current class context
        caller?.let { c ->
            // Check for private methods/properties
            c.members[name]?.let { rec ->
                if (rec.visibility == Visibility.Private) {
                    return resolveRecord(scope, rec, name, c)
                }
            }
            // Check for private fields (stored in instanceScope)
            val mangled = c.mangledName(name)
            instanceScope.objects[mangled]?.let { rec ->
                if (rec.visibility == Visibility.Private) {
                    return resolveRecord(scope, rec, name, c)
                }
            }
        }

        // 1. MRO mangled storage
        for (cls in objClass.mro) {
            if (cls.className == "Obj") break
            val mangled = cls.mangledName(name)
            instanceScope.objects[mangled]?.let { rec ->
                if (canAccessMember(rec.visibility, cls, caller, name)) {
                    return resolveRecord(scope, rec, name, cls)
                }
            }
        }

        // 2. Unmangled storage
        instanceScope.objects[name]?.let { rec ->
            val decl = rec.declaringClass
            if (canAccessMember(rec.visibility, decl, caller, name)) {
                return resolveRecord(scope, rec, name, decl)
            }
        }

        // 3. Fall back to super (handles class members and extensions)
        return super.readField(scope, name)
    }

    override suspend fun resolveRecord(scope: Scope, obj: ObjRecord, name: String, decl: ObjClass?): ObjRecord {
        if (obj.type.isArgument) return super.resolveRecord(scope, obj, name, decl)
        if (obj.type == ObjRecord.Type.Delegated) {
            val d = decl ?: obj.declaringClass
            val storageName = d?.mangledName(name) ?: name
            var del = instanceScope[storageName]?.delegate ?: obj.delegate
            if (del == null) {
                for (c in objClass.mro) {
                    del = instanceScope[c.mangledName(name)]?.delegate
                    if (del != null) break
                }
            }
            del = del ?: scope.raiseError("Internal error: delegated property $name has no delegate")
            val res = del.invokeInstanceMethod(scope, "getValue", Arguments(this, ObjString(name)))
            obj.value = res
            return obj
        }

        // Map member template to instance storage if applicable
        var targetRec = obj
        val d = decl ?: obj.declaringClass
        if (d != null) {
            val mangled = d.mangledName(name)
            instanceScope.objects[mangled]?.let { 
                targetRec = it 
            }
        }
        if (targetRec === obj) {
            instanceScope.objects[name]?.let { rec ->
                // Check if this record in instanceScope is the one we want.
                // For members, it must match the declaring class.
                // Arguments are also preferred.
                if (rec.type == ObjRecord.Type.Argument || rec.declaringClass == d || rec.declaringClass == null) {
                    targetRec = rec
                }
            }
        }

        return super.resolveRecord(scope, targetRec, name, d)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        willMutate(scope)
        val caller = scope.currentClassCtx

        // Fast path for public members when outside any class context
        if (caller == null) {
            objClass.publicMemberResolution[name]?.let { key ->
                instanceScope.objects[key]?.let { rec ->
                    if (rec.effectiveWriteVisibility == Visibility.Public) {
                        // Skip property/delegated overhead if it's a plain mutable field
                        if (rec.type == ObjRecord.Type.Field && rec.isMutable && !rec.isAbstract) {
                            if (rec.value.assign(scope, newValue) == null)
                                rec.value = newValue
                            return
                        }
                        updateRecord(scope, rec, name, newValue, rec.declaringClass)
                        return
                    }
                }
            }
        }

        // 0. Private mangled of current class context
        caller?.let { c ->
            // Check for private methods/properties
            c.members[name]?.let { rec ->
                if (rec.visibility == Visibility.Private) {
                    updateRecord(scope, resolveRecord(scope, rec, name, c), name, newValue, c)
                    return
                }
            }
            // Check for private fields (stored in instanceScope)
            val mangled = c.mangledName(name)
            instanceScope.objects[mangled]?.let { rec ->
                if (rec.visibility == Visibility.Private) {
                    updateRecord(scope, rec, name, newValue, c)
                    return
                }
            }
        }

        // 1. MRO mangled storage
        for (cls in objClass.mro) {
            if (cls.className == "Obj") break
            val mangled = cls.mangledName(name)
            instanceScope.objects[mangled]?.let { rec ->
                if (canAccessMember(rec.effectiveWriteVisibility, cls, caller, name)) {
                    updateRecord(scope, rec, name, newValue, cls)
                    return
                }
            }
        }

        // 2. Unmangled storage
        instanceScope.objects[name]?.let { rec ->
            val decl = rec.declaringClass
            if (canAccessMember(rec.effectiveWriteVisibility, decl, caller, name)) {
                updateRecord(scope, rec, name, newValue, decl)
                return
            }
        }

        super.writeField(scope, name, newValue)
    }

    private suspend fun updateRecord(scope: Scope, rec: ObjRecord, name: String, newValue: Obj, decl: ObjClass?) {
        if (rec.type == ObjRecord.Type.Property) {
            val prop = rec.value as ObjProperty
            prop.callSetter(scope, this, newValue, decl)
            return
        }
        if (rec.type == ObjRecord.Type.Delegated) {
            val storageName = "${decl?.className}::$name"
            var del = instanceScope[storageName]?.delegate
            if (del == null) {
                for (c in objClass.mro) {
                    del = instanceScope["${c.className}::$name"]?.delegate
                    if (del != null) break
                }
            }
            del = del ?: rec.delegate ?: scope.raiseError("Internal error: delegated property $name has no delegate (tried $storageName)")
            del.invokeInstanceMethod(scope, "setValue", Arguments(this, ObjString(name), newValue))
            return
        }
        if (!rec.isMutable && rec.value !== ObjUnset) ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
        if (rec.value.assign(scope, newValue) == null)
            rec.value = newValue
    }

    override suspend fun invokeInstanceMethod(
        scope: Scope, name: String, args: Arguments,
        onNotFoundResult: (suspend () -> Obj?)?
    ): Obj {
        val caller = scope.currentClassCtx
        
        // Fast path for public members when outside any class context
        if (caller == null) {
            objClass.publicMemberResolution[name]?.let { key ->
                instanceScope.objects[key]?.let { rec ->
                    if (rec.visibility == Visibility.Public && !rec.isAbstract) {
                        val decl = rec.declaringClass
                        if (rec.type == ObjRecord.Type.Property) {
                            if (args.isEmpty()) return (rec.value as ObjProperty).callGetter(scope, this, decl)
                        } else if (rec.type == ObjRecord.Type.Fun) {
                            return rec.value.invoke(instanceScope, this, args, decl)
                        }
                    }
                }
            }
        }

        // 0. Prefer private member of current class context
        caller?.let { c ->
            val mangled = c.mangledName(name)
            instanceScope.objects[mangled]?.let { rec ->
                if (rec.visibility == Visibility.Private && !rec.isAbstract) {
                    if (rec.type == ObjRecord.Type.Property) {
                        if (args.isEmpty()) return (rec.value as ObjProperty).callGetter(scope, this, c)
                    } else if (rec.type == ObjRecord.Type.Fun) {
                        return rec.value.invoke(instanceScope, this, args, c)
                    }
                }
            }
            c.members[name]?.let { rec ->
                if (rec.visibility == Visibility.Private && !rec.isAbstract) {
                    if (rec.type == ObjRecord.Type.Property) {
                        if (args.isEmpty()) return (rec.value as ObjProperty).callGetter(scope, this, c)
                    } else if (rec.type == ObjRecord.Type.Fun) {
                        return rec.value.invoke(instanceScope, this, args, c)
                    }
                }
            }
        }

        // 1. Walk MRO to find member, handling delegation
        for (cls in objClass.mro) {
            if (cls.className == "Obj") break
            val rec = cls.members[name] ?: cls.classScope?.objects?.get(name)
            if (rec != null && !rec.isAbstract) {
                if (rec.type == ObjRecord.Type.Delegated) {
                    val storageName = cls.mangledName(name)
                    val del = instanceScope[storageName]?.delegate ?: rec.delegate
                    ?: scope.raiseError("Internal error: delegated member $name has no delegate (tried $storageName)")
                    
                    // For delegated member, try 'invoke' first if it's a function-like call
                    val allArgs = (listOf(this, ObjString(name)) + args.list).toTypedArray()
                    return del.invokeInstanceMethod(scope, "invoke", Arguments(*allArgs), onNotFoundResult = {
                        // Fallback: property delegation (getValue then call result)
                        val propVal = del.invokeInstanceMethod(scope, "getValue", Arguments(this, ObjString(name)))
                        propVal.invoke(scope, this, args, rec.declaringClass ?: cls)
                    })
                }
                val decl = rec.declaringClass ?: cls
                val effectiveCaller = caller ?: if (scope.thisObj === this) objClass else null
                if (!canAccessMember(rec.visibility, decl, effectiveCaller, name))
                    scope.raiseError(
                        ObjIllegalAccessException(
                            scope,
                            "can't invoke method $name (declared in ${decl.className})"
                        )
                    )
                
                if (rec.type == ObjRecord.Type.Property) {
                    if (args.isEmpty()) return (rec.value as ObjProperty).callGetter(scope, this, decl)
                } else if (rec.type == ObjRecord.Type.Fun) {
                    return rec.value.invoke(
                        instanceScope,
                        this,
                        args,
                        decl
                    )
                } else if (rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.ConstructorField || rec.type == ObjRecord.Type.Argument) {
                    val resolved = readField(scope, name)
                    return resolved.value.invoke(scope, this, args, resolved.declaringClass)
                }
            }
        }

        // 2. Fall back to super (handles extensions and root fallback)
        return super.invokeInstanceMethod(scope, name, args, onNotFoundResult)
    }

    private val publicFields: Map<String, ObjRecord>
        get() = instanceScope.objects.filter {
            // Expose only human-facing fields: skip MI-mangled storage entries like "Class::name"
            !it.key.contains("::") && it.value.visibility.isPublic && it.value.type.serializable
        }

    override suspend fun defaultToString(scope: Scope): ObjString {
        return ObjString(buildString {
            append("${objClass.className}(")
            var first = true
            for ((name, value) in publicFields) {
                if (first) first = false else append(",")
                append("$name=${value.value.toString(scope)}")
            }
            append(")")
        })
    }

    override suspend fun inspect(scope: Scope): String {
        return toString(scope).value
    }

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        val meta = objClass.constructorMeta
            ?: scope.raiseError("can't serialize non-serializable object (no constructor meta)")
        // actual constructor can vary, for example, adding new fields with default
        // values, so we save size of the construction:

        // using objlist allow for some optimizations:
        val params = meta.params.map { readField(scope, it.name).value }
        encoder.encodeAnyList(scope, params)
        val vars = serializingVars.values.map { it.value }
        if (vars.isNotEmpty()) {
            encoder.encodeAnyList(scope, vars)
        }
    }

    override suspend fun toJson(scope: Scope): JsonElement {
        // Call the class-provided map serializer:
        val custom = invokeInstanceMethod(scope, "toJsonObject", Arguments.EMPTY, { ObjVoid })
        if (custom != ObjVoid) {
            // class json serializer returned something, so use it:
            return custom.toJson(scope)
        }
        // no class serializer, serialize from constructor
        val result = mutableMapOf<String, JsonElement>()
        val meta = objClass.constructorMeta
            ?: scope.raiseError("can't serialize non-serializable object (no constructor meta)")
        for (entry in meta.params)
            result[entry.name] = readField(scope, entry.name).value.toJson(scope)
        for (i in serializingVars) {
            // remove T:: prefix from the field name for JSON
            val parts = i.key.split("::")
            result[if (parts.size == 1) parts[0] else parts.last()] = i.value.value.toJson(scope)
        }
        return JsonObject(result)
    }

//    val instanceVars: Map<String, ObjRecord> by lazy {
//        instanceScope.objects.filter { it.value.type.serializable }
//    }

    val serializingVars: Map<String, ObjRecord> by lazy {
        val metaParams = objClass.constructorMeta?.params?.map { it.name }?.toSet() ?: emptySet()
        instanceScope.objects.filter {
            it.value.type.serializable &&
                    it.value.type == ObjRecord.Type.Field &&
                    it.value.isMutable &&
                    !metaParams.contains(it.key)
        }
    }

    internal suspend fun deserializeStateVars(scope: Scope, decoder: LynonDecoder) {
        val localVars = serializingVars.values.toList()
        if (localVars.isNotEmpty()) {
            val vars = decoder.decodeAnyList(scope)
            if (vars.size > serializingVars.size)
                scope.raiseIllegalArgument(
                    "serialized vars has bigger size ${vars.size} than instance vars (${serializingVars.size}): "+
                    vars.joinToString(",")
                )
            for ((i, v) in vars.withIndex()) {
                localVars[i].value = v
            }
        }
    }

    protected val comparableVars: Map<String, ObjRecord> by lazy {
        instanceScope.objects.filter {
            it.value.type.comparable && (it.value.type != ObjRecord.Type.Field || it.value.isMutable)
        }
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is ObjInstance) return -1
        if (other.objClass != objClass) return -1
        for (f in comparableVars) {
            val a = f.value.value
            val b = other.instanceScope.objects[f.key]?.value ?: scope.raiseError("Internal error: field ${f.key} not found in other instance")
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
            if (!canAccessMember(rec.visibility, decl, caller, name))
                scope.raiseError(ObjIllegalAccessException(scope, "can't access field $name (declared in ${decl.className})"))
            return instance.resolveRecord(scope, rec, name, decl)
        }
        // Then try instance locals (unmangled) only if startClass is the dynamic class itself
        if (startClass === instance.objClass) {
            instance.instanceScope[name]?.let { rec ->
                val decl = rec.declaringClass ?: instance.objClass.findDeclaringClassOf(name)
                val caller = scope.currentClassCtx
                if (!canAccessMember(rec.visibility, decl, caller, name))
                    scope.raiseError(
                        ObjIllegalAccessException(
                            scope,
                            "can't access field $name (declared in ${decl?.className ?: "?"})"
                        )
                    )
                return instance.resolveRecord(scope, rec, name, decl)
            }
        }
        // Finally try methods/properties starting from ancestor
        val r = memberFromAncestor(name) ?: scope.raiseError("no such field: $name")
        val decl = r.declaringClass ?: startClass
        val caller = scope.currentClassCtx
        if (!canAccessMember(r.visibility, decl, caller, name))
            scope.raiseError(ObjIllegalAccessException(scope, "can't access field $name (declared in ${decl.className})"))
        
        return instance.resolveRecord(scope, r, name, decl)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        // Qualified write: target mangled storage for the ancestor
        val mangled = "${startClass.className}::$name"
        instance.instanceScope.objects[mangled]?.let { f ->
            val decl = f.declaringClass ?: startClass
            val caller = scope.currentClassCtx
            if (!canAccessMember(f.effectiveWriteVisibility, decl, caller, name))
                ObjIllegalAccessException(
                    scope,
                    "can't assign to field $name (declared in ${decl.className})"
                ).raise()
            if (!f.isMutable && f.value !== ObjUnset) ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
            if (f.value.assign(scope, newValue) == null) f.value = newValue
            return
        }
        // If start is dynamic class, allow unmangled
        if (startClass === instance.objClass) {
            instance.instanceScope[name]?.let { f ->
                val decl = f.declaringClass ?: instance.objClass.findDeclaringClassOf(name)
                val caller = scope.currentClassCtx
                if (!canAccessMember(f.effectiveWriteVisibility, decl, caller, name))
                    ObjIllegalAccessException(
                        scope,
                        "can't assign to field $name (declared in ${decl?.className ?: "?"})"
                    ).raise()
                if (!f.isMutable && f.value !== ObjUnset) ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
                if (f.value.assign(scope, newValue) == null) f.value = newValue
                return
            }
        }
        val r = memberFromAncestor(name) ?: scope.raiseError("no such field: $name")
        val decl = r.declaringClass ?: startClass
        val caller = scope.currentClassCtx
        if (!canAccessMember(r.effectiveWriteVisibility, decl, caller, name))
            ObjIllegalAccessException(scope, "can't assign to field $name (declared in ${decl.className})").raise()
        if (!r.isMutable) scope.raiseError("can't assign to read-only field: $name")
        if (r.value.assign(scope, newValue) == null) r.value = newValue
    }

    override suspend fun invokeInstanceMethod(
        scope: Scope,
        name: String,
        args: Arguments,
        onNotFoundResult: (suspend () -> Obj?)?
    ): Obj {
        // Qualified method dispatch must start from the specified ancestor, not from the instance scope.
        memberFromAncestor(name)?.let { rec ->
            val decl = rec.declaringClass ?: startClass
            val caller = scope.currentClassCtx
            if (!canAccessMember(rec.visibility, decl, caller, name))
                scope.raiseError(ObjIllegalAccessException(scope, "can't invoke method $name (declared in ${decl.className})"))
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
                if (!canAccessMember(rec.visibility, decl, caller, name))
                    scope.raiseError(
                        ObjIllegalAccessException(
                            scope,
                            "can't invoke method $name (declared in ${decl?.className ?: "?"})"
                        )
                    )
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