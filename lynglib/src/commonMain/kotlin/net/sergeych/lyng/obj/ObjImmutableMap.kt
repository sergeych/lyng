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
import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjImmutableMap(entries: Map<Obj, Obj> = emptyMap()) : Obj() {
    val map: Map<Obj, Obj> = LinkedHashMap(entries)

    override val objClass get() = type

    override suspend fun equals(scope: Scope, other: Obj): Boolean {
        if (this === other) return true
        val otherMap = when (other) {
            is ObjImmutableMap -> other.map
            is ObjMap -> other.map
            else -> return false
        }
        if (map.size != otherMap.size) return false
        for ((k, v) in map) {
            val ov = other.getAt(scope, k)
            if (ov === ObjNull && !other.contains(scope, k)) return false
            if (!v.equals(scope, ov)) return false
        }
        return true
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        val otherMap = when (other) {
            is ObjImmutableMap -> other.map
            is ObjMap -> other.map
            else -> return -1
        }
        if (map == otherMap) return 0
        if (map.size != otherMap.size) return map.size.compareTo(otherMap.size)
        return map.toString().compareTo(otherMap.toString())
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj = map[index] ?: ObjNull

    override suspend fun contains(scope: Scope, other: Obj): Boolean = other in map

    override suspend fun defaultToString(scope: Scope): ObjString {
        val rendered = buildString {
            append("ImmutableMap(")
            var first = true
            for ((k, v) in map) {
                if (!first) append(",")
                append(k.inspect(scope))
                append(" => ")
                append(v.toString(scope).value)
                first = false
            }
            append(")")
        }
        return ObjString(rendered)
    }

    override suspend fun lynonType(): LynonType = LynonType.Map

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        val keys = map.keys.map { it.toObj() }
        val values = map.values.map { it.toObj() }
        encoder.encodeAnyList(scope, keys)
        encoder.encodeAnyList(scope, values, fixedSize = true)
    }

    override suspend fun toJson(scope: Scope): JsonElement {
        return JsonObject(map.map { it.key.toString(scope).value to it.value.toJson(scope) }.toMap())
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        val out = LinkedHashMap(map)
        mergeIn(scope, out, other)
        return ObjImmutableMap(out)
    }

    private suspend fun mergeIn(scope: Scope, out: MutableMap<Obj, Obj>, other: Obj) {
        when (other) {
            is ObjImmutableMap -> out.putAll(other.map)
            is ObjMap -> out.putAll(other.map)
            is ObjMapEntry -> out[other.key] = other.value
            is ObjList -> {
                for (e in other.list) {
                    when (e) {
                        is ObjMapEntry -> out[e.key] = e.value
                        else -> {
                            if (e.isInstanceOf(ObjArray)) {
                                if (e.invokeInstanceMethod(scope, "size").toInt() != 2)
                                    scope.raiseIllegalArgument("Array element to merge into map must have 2 elements, got $e")
                                out[e.getAt(scope, 0)] = e.getAt(scope, 1)
                            } else {
                                scope.raiseIllegalArgument("map can only be merged with MapEntry elements; got $e")
                            }
                        }
                    }
                }
            }
            else -> scope.raiseIllegalArgument("map can only be merged with Map, ImmutableMap, MapEntry, or List<MapEntry>")
        }
    }

    fun toMutableMapCopy(): MutableMap<Obj, Obj> = LinkedHashMap(map)

    companion object {
        val type = object : ObjClass("ImmutableMap", ObjCollection) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjImmutableMap(ObjMap.listToMap(scope, scope.args.list))
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                val keys = decoder.decodeAnyList(scope)
                val values = decoder.decodeAnyList(scope, fixedSize = keys.size)
                if (keys.size != values.size) scope.raiseIllegalArgument("map keys and values should be same size")
                return ObjImmutableMap(keys.zip(values).toMap())
            }
        }.apply {
            addFnDoc(
                name = "getOrNull",
                doc = "Get value by key or return null if the key is absent.",
                params = listOf(ParamDoc("key")),
                returns = type("lyng.Any", nullable = true),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjImmutableMap>().map[args.firstAndOnly(pos)] ?: ObjNull
            }
            addPropertyDoc(
                name = "size",
                doc = "Number of entries in the immutable map.",
                type = type("lyng.Int"),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjImmutableMap>().map.size.toObj() }
            )
            addPropertyDoc(
                name = "keys",
                doc = "List of keys in this immutable map.",
                type = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.Any"))),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjImmutableMap>().map.keys.toObj() }
            )
            addPropertyDoc(
                name = "values",
                doc = "List of values in this immutable map.",
                type = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.Any"))),
                moduleName = "lyng.stdlib",
                getter = { ObjList(thisAs<ObjImmutableMap>().map.values.toMutableList()) }
            )
            addFnDoc(
                name = "iterator",
                doc = "Iterator over map entries as MapEntry objects.",
                moduleName = "lyng.stdlib"
            ) {
                ObjKotlinIterator(thisAs<ObjImmutableMap>().map.entries.iterator())
            }
            addFnDoc(
                name = "toMutable",
                doc = "Create a mutable copy of this immutable map.",
                returns = type("lyng.Map"),
                moduleName = "lyng.stdlib"
            ) {
                ObjMap(thisAs<ObjImmutableMap>().toMutableMapCopy())
            }
            addFnDoc(
                name = "toImmutable",
                doc = "Return this immutable map.",
                returns = type("lyng.ImmutableMap"),
                moduleName = "lyng.stdlib"
            ) { thisObj }
        }
    }
}
