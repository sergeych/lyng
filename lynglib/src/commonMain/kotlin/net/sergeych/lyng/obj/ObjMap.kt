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

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Statement
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjMapEntry(val key: Obj, val value: Obj) : Obj() {

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is ObjMapEntry) return -1
        val c = key.compareTo(scope, other.key)
        if (c != 0) return c
        return value.compareTo(scope, other.value)
    }

    override fun hashCode(): Int {
        return key.hashCode() + value.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other is ObjMapEntry && key == other.key && value == other.value
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj = when (index.toInt()) {
        0 -> key
        1 -> value
        else -> scope.raiseIndexOutOfBounds()
    }

    override fun toString(): String {
        return "$key=>$value"
    }

    override val objClass = type

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeAny(scope,key)
        encoder.encodeAny(scope,value)
    }

    companion object {
        val type = object : ObjClass("MapEntry", ObjArray) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjMapEntry(scope.requiredArg<Obj>(0), scope.requiredArg<Obj>(1))
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                return ObjMapEntry(
                    decoder.decodeAny(scope),
                    decoder.decodeAny(scope)
                )
            }
        }.apply {
            addFn("key") { thisAs<ObjMapEntry>().key }
            addFn("value") { thisAs<ObjMapEntry>().value }
            addFn("size") { 2.toObj() }
        }
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        // Build a new map starting from this entry, then merge `other`.
        val result = ObjMap(mutableMapOf(key to value))
        return result.plus(scope, other)
    }
}

class ObjMap(val map: MutableMap<Obj, Obj> = mutableMapOf()) : Obj() {

    override val objClass = type

    override suspend fun getAt(scope: Scope, index: Obj): Obj =
        map.get(index) ?: ObjNull

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        map[index] = newValue
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {
        return other in map
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if( other is ObjMap && other.map == map) return 0
        return -1
    }

    override suspend fun toString(scope: Scope, calledFromLyng: Boolean): ObjString {
        val reusult = buildString {
            append("Map(")
            var first = true
            for( (k,v) in map) {
                if( !first ) append(",")
                append(k.inspect(scope))
                append(" => ")
                append(v.toString(scope).value)
                first = false
            }
            append(")")
        }
        return ObjString(reusult)
    }

    override fun hashCode(): Int {
        return map.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjMap

        return map == other.map
    }

    override suspend fun lynonType(): LynonType = LynonType.Map

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        val keys = map.keys.map { it.toObj() }
        val values = map.values.map { it.toObj() }
        encoder.encodeAnyList(scope, keys)
        encoder.encodeAnyList(scope, values, fixedSize = true)
    }

    override suspend fun toJson(scope: Scope): JsonElement {
        return JsonObject(
            map.map { it.key.toString(scope).value to it.value.toJson(scope) }.toMap()
        )
    }

    companion object {

        suspend fun listToMap(scope: Scope, list: List<Obj>): MutableMap<Obj, Obj> {
            val map = mutableMapOf<Obj, Obj>()
            if (list.isEmpty()) return map

            val first = list.first()
            if (first.isInstanceOf(ObjArray)) {
                if (first.invokeInstanceMethod(scope, "size").toInt() != 2)
                    scope.raiseIllegalArgument(
                        "list to construct map entry should exactly be 2 element Array like [key,value], got $list"
                    )
            } else scope.raiseIllegalArgument("first element of map list be a Collection of 2 elements; got $first")



            list.forEach {
                map[it.getAt(scope, ObjInt.Zero)] = it.getAt(scope, ObjInt.One)
            }
            return map
        }


        val type = object : ObjClass("Map", ObjCollection) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjMap(listToMap(scope, scope.args.list))
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                val keys = decoder.decodeAnyList(scope)
                val values = decoder.decodeAnyList(scope,fixedSize = keys.size)
                if( keys.size != values.size) scope.raiseIllegalArgument("map keys and values should be same size")
                return ObjMap(keys.zip(values).toMap().toMutableMap())
            }
        }.apply {
            addFn("getOrNull") {
                val key = args.firstAndOnly(pos)
                thisAs<ObjMap>().map.getOrElse(key) { ObjNull }
            }
            addFn("getOrPut") {
                val key = requiredArg<Obj>(0)
                thisAs<ObjMap>().map.getOrPut(key) {
                    val lambda = requiredArg<Statement>(1)
                    lambda.execute(this)
                }
            }
            addFn("size") {
                thisAs<ObjMap>().map.size.toObj()
            }
            addFn("remove") {
                thisAs<ObjMap>().map.remove(requiredArg<Obj>(0))?.toObj() ?: ObjNull
            }
            addFn("clear") {
                thisAs<ObjMap>().map.clear()
                thisObj
            }
            addFn("keys") {
                thisAs<ObjMap>().map.keys.toObj()
            }
            addFn("values") {
                ObjList(thisAs<ObjMap>().map.values.toMutableList())
            }
            addFn("iterator") {
                ObjKotlinIterator(thisAs<ObjMap>().map.entries.iterator())
            }
        }
    }

    // Merge operations
    override suspend fun plus(scope: Scope, other: Obj): Obj {
        val result = ObjMap(map.toMutableMap())
        result.mergeIn(scope, other)
        return result
    }

    override suspend fun plusAssign(scope: Scope, other: Obj): Obj {
        mergeIn(scope, other)
        return this
    }

    private suspend fun mergeIn(scope: Scope, other: Obj) {
        when (other) {
            is ObjMap -> {
                // Rightmost wins: copy all entries from `other` over existing ones
                for ((k, v) in other.map) {
                    val key = k as? ObjString ?: scope.raiseIllegalArgument("map merge expects string keys; got $k")
                    map[key] = v
                }
            }
            is ObjMapEntry -> {
                val key = other.key as? ObjString ?: scope.raiseIllegalArgument("map merge expects string keys; got ${other.key}")
                map[key] = other.value
            }
            is ObjList -> {
                // Treat as list of map entries
                for (e in other.list) {
                    val entry = when (e) {
                        is ObjMapEntry -> e
                        else -> scope.raiseIllegalArgument("map can only be merged with MapEntry elements; got $e")
                    }
                    val key = entry.key as? ObjString ?: scope.raiseIllegalArgument("map merge expects string keys; got ${entry.key}")
                    map[key] = entry.value
                }
            }
            else -> scope.raiseIllegalArgument("map can only be merged with Map, MapEntry, or List<MapEntry>")
        }
    }
}