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
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjInstance(override val objClass: ObjClass) : Obj() {

    internal lateinit var instanceScope: Scope

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        return instanceScope[name]?.let {
            if (it.visibility.isPublic)
                it
            else
                scope.raiseError(ObjAccessException(scope, "can't access non-public field $name"))
        }
            ?: super.readField(scope, name)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        instanceScope[name]?.let { f ->
            if (!f.visibility.isPublic)
                ObjIllegalAssignmentException(scope, "can't assign to non-public field $name")
            if (!f.isMutable) ObjIllegalAssignmentException(scope, "can't reassign val $name").raise()
            if (f.value.assign(scope, newValue) == null)
                f.value = newValue
        } ?: super.writeField(scope, name, newValue)
    }

    override suspend fun invokeInstanceMethod(scope: Scope, name: String, args: Arguments,
                                              onNotFoundResult: Obj?): Obj =
        instanceScope[name]?.let {
            if (it.visibility.isPublic)
                it.value.invoke(
                    instanceScope,
                    this,
                    args)
            else
                scope.raiseError(ObjAccessException(scope, "can't invoke non-public method $name"))
        }
            ?: super.invokeInstanceMethod(scope, name, args, onNotFoundResult)

    private val publicFields: Map<String, ObjRecord>
        get() = instanceScope.objects.filter { it.value.visibility.isPublic }

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
            println("serialized state vars $vars")
        }
    }

    internal suspend fun deserializeStateVars(scope: Scope, decoder: LynonDecoder) {
        val localVars = instanceVars.values.toList()
        if( localVars.isNotEmpty() ) {
            println("gonna read vars")
            val vars = decoder.decodeAnyList(scope)
            if (vars.size > instanceVars.size)
                scope.raiseIllegalArgument("serialized vars has bigger size than instance vars")
            println("deser state vars $vars")
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