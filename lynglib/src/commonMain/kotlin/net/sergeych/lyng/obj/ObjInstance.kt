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
import net.sergeych.lyng.canAccessMember
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjInstance(override val objClass: ObjClass) : Obj() {

    internal lateinit var instanceScope: Scope

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        // 1. Direct (unmangled) lookup first
        instanceScope[name]?.let { rec ->
            val decl = rec.declaringClass
            // Allow unconditional access when accessing through `this` of the same instance
            // BUT only if we are in the class context (not extension)
            if (scope.thisObj !== this || scope.currentClassCtx == null) {
                val caller = scope.currentClassCtx
                if (!canAccessMember(rec.visibility, decl, caller))
                    scope.raiseError(
                        ObjIllegalAccessException(
                            scope,
                            "can't access field $name (declared in ${decl?.className ?: "?"})"
                        )
                    )
            }
            return resolveRecord(scope, rec, name, decl)
        }

        // 2. MI-mangled instance scope lookup
        val cls = objClass
        fun findMangledInRead(): ObjRecord? {
            instanceScope.objects["${cls.className}::$name"]?.let { return it }
            for (p in cls.mroParents) {
                instanceScope.objects["${p.className}::$name"]?.let { return it }
            }
            return null
        }

        findMangledInRead()?.let { rec ->
            val declaring = when {
                instanceScope.objects.containsKey("${cls.className}::$name") -> cls
                else -> cls.mroParents.firstOrNull { instanceScope.objects.containsKey("${it.className}::$name") }
            }
            if (scope.thisObj !== this || scope.currentClassCtx == null) {
                val caller = scope.currentClassCtx
                if (!canAccessMember(rec.visibility, declaring, caller))
                    scope.raiseError(
                        ObjIllegalAccessException(
                            scope,
                            "can't access field $name (declared in ${declaring?.className ?: "?"})"
                        )
                    )
            }
            return resolveRecord(scope, rec, name, declaring)
        }

        // 3. Fall back to super (handles class members and extensions)
        return super.readField(scope, name)
    }

    override suspend fun resolveRecord(scope: Scope, obj: ObjRecord, name: String, decl: ObjClass?): ObjRecord {
        if (obj.type == ObjRecord.Type.Delegated) {
            val storageName = "${decl?.className}::$name"
            var del = instanceScope[storageName]?.delegate
            if (del == null) {
                for (c in objClass.mro) {
                    del = instanceScope["${c.className}::$name"]?.delegate
                    if (del != null) break
                }
            }
            del = del ?: obj.delegate ?: scope.raiseError("Internal error: delegated property $name has no delegate (tried $storageName)")
            val res = del.invokeInstanceMethod(scope, "getValue", Arguments(this, ObjString(name)))
            obj.value = res
            return obj
        }
        return super.resolveRecord(scope, obj, name, decl)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        // Direct (unmangled) first
        instanceScope[name]?.let { f ->
            val decl = f.declaringClass
            if (scope.thisObj !== this || scope.currentClassCtx == null) {
                val caller = scope.currentClassCtx
                if (!canAccessMember(f.effectiveWriteVisibility, decl, caller))
                    ObjIllegalAccessException(
                        scope,
                        "can't assign to field $name (declared in ${decl?.className ?: "?"})"
                    ).raise()
            }
            if (f.type == ObjRecord.Type.Property) {
                val prop = f.value as ObjProperty
                prop.callSetter(scope, this, newValue, decl)
                return
            }
            if (f.type == ObjRecord.Type.Delegated) {
                val storageName = "${decl?.className}::$name"
                var del = instanceScope[storageName]?.delegate
                if (del == null) {
                    for (c in objClass.mro) {
                        del = instanceScope["${c.className}::$name"]?.delegate
                        if (del != null) break
                    }
                }
                del = del ?: f.delegate ?: scope.raiseError("Internal error: delegated property $name has no delegate (tried $storageName)")
                del.invokeInstanceMethod(scope, "setValue", Arguments(this, ObjString(name), newValue))
                return
            }
            if (!f.isMutable && f.value !== ObjUnset) ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
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
            if (scope.thisObj !== this || scope.currentClassCtx == null) {
                val caller = scope.currentClassCtx
                if (!canAccessMember(rec.effectiveWriteVisibility, declaring, caller))
                    ObjIllegalAccessException(
                        scope,
                        "can't assign to field $name (declared in ${declaring?.className ?: "?"})"
                    ).raise()
            }
            if (rec.type == ObjRecord.Type.Property) {
                val prop = rec.value as ObjProperty
                prop.callSetter(scope, this, newValue, declaring)
                return
            }
            if (rec.type == ObjRecord.Type.Delegated) {
                val storageName = "${declaring?.className}::$name"
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
            return
        }
        super.writeField(scope, name, newValue)
    }

    override suspend fun invokeInstanceMethod(
        scope: Scope, name: String, args: Arguments,
        onNotFoundResult: (suspend () -> Obj?)?
    ): Obj {
        // 1. Walk MRO to find member, handling delegation
        for (cls in objClass.mro) {
            if (cls.className == "Obj") break
            val rec = cls.members[name] ?: cls.classScope?.objects?.get(name)
            if (rec != null) {
                if (rec.type == ObjRecord.Type.Delegated) {
                    val storageName = "${cls.className}::$name"
                    val del = instanceScope[storageName]?.delegate ?: rec.delegate
                    ?: scope.raiseError("Internal error: delegated member $name has no delegate (tried $storageName)")
                    val allArgs = (listOf(this, ObjString(name)) + args.list).toTypedArray()
                    return del.invokeInstanceMethod(scope, "invoke", Arguments(*allArgs), onNotFoundResult = {
                        // Fallback: property delegation
                        val propVal = del.invokeInstanceMethod(scope, "getValue", Arguments(this, ObjString(name)))
                        propVal.invoke(scope, this, args, rec.declaringClass ?: cls)
                    })
                }
                if (rec.type == ObjRecord.Type.Fun && !rec.isAbstract) {
                    val decl = rec.declaringClass ?: cls
                    val caller = scope.currentClassCtx ?: if (scope.thisObj === this) objClass else null
                    if (!canAccessMember(rec.visibility, decl, caller))
                        scope.raiseError(
                            ObjIllegalAccessException(
                                scope,
                                "can't invoke method $name (declared in ${decl.className})"
                            )
                        )
                    return rec.value.invoke(
                        instanceScope,
                        this,
                        args,
                        decl
                    )
                } else if ((rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.Property) && !rec.isAbstract) {
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
                scope.raiseIllegalArgument("serialized vars has bigger size than instance vars")
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
            if (!canAccessMember(rec.visibility, decl, caller))
                scope.raiseError(ObjIllegalAccessException(scope, "can't access field $name (declared in ${decl.className})"))
            return resolveRecord(scope, rec, name, decl)
        }
        // Then try instance locals (unmangled) only if startClass is the dynamic class itself
        if (startClass === instance.objClass) {
            instance.instanceScope[name]?.let { rec ->
                val decl = rec.declaringClass ?: instance.objClass.findDeclaringClassOf(name)
                val caller = scope.currentClassCtx
                if (!canAccessMember(rec.visibility, decl, caller))
                    scope.raiseError(
                        ObjIllegalAccessException(
                            scope,
                            "can't access field $name (declared in ${decl?.className ?: "?"})"
                        )
                    )
                return resolveRecord(scope, rec, name, decl)
            }
        }
        // Finally try methods/properties starting from ancestor
        val r = memberFromAncestor(name) ?: scope.raiseError("no such field: $name")
        val decl = r.declaringClass ?: startClass
        val caller = scope.currentClassCtx
        if (!canAccessMember(r.visibility, decl, caller))
            scope.raiseError(ObjIllegalAccessException(scope, "can't access field $name (declared in ${decl.className})"))
        
        return resolveRecord(scope, r, name, decl)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        // Qualified write: target mangled storage for the ancestor
        val mangled = "${startClass.className}::$name"
        instance.instanceScope.objects[mangled]?.let { f ->
            val decl = f.declaringClass ?: startClass
            val caller = scope.currentClassCtx
            if (!canAccessMember(f.effectiveWriteVisibility, decl, caller))
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
                if (!canAccessMember(f.effectiveWriteVisibility, decl, caller))
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
        if (!canAccessMember(r.effectiveWriteVisibility, decl, caller))
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
            if (!canAccessMember(rec.visibility, decl, caller))
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
                if (!canAccessMember(rec.visibility, decl, caller))
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