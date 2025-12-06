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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.RegexCache
import net.sergeych.lyng.Scope
import net.sergeych.lyng.statement
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType
import net.sergeych.sprintf.sprintf

@Serializable
@SerialName("string")
data class ObjString(val value: String) : Obj() {

//    fun normalize(context: Context, index: Int, allowsEndInclusive: Boolean = false): Int {
//        val i = if (index < 0) value.length + index else index
//        if (allowsEndInclusive && i == value.length) return i
//        if (i !in value.indices) context.raiseError("index $index out of bounds for length ${value.length} of \"$value\"")
//        return i
//    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is ObjString) return -2
        return this.value.compareTo(other.value)
    }

    override fun toString(): String = value

    override suspend fun inspect(scope: Scope): String {
        return "\"$value\""
    }

    override val objClass: ObjClass
        get() = type

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        return ObjString(value + other.toString(scope).value)
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        when (index) {
            is ObjInt -> return ObjChar(value[index.toInt()])
            is ObjRange -> {
                val start = if (index.start == null || index.start.isNull) 0 else index.start.toInt()
                val end = if (index.end == null || index.end.isNull) value.length else {
                    val e = index.end.toInt()
                    if (index.isEndInclusive) e + 1 else e
                }
                return ObjString(value.substring(start, end))
            }

            is ObjRegex -> {
                return index.find(this)
            }

            else -> scope.raiseIllegalArgument("String index must be Int, Regex or Range")
        }
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override suspend fun callOn(scope: Scope): Obj {
        return ObjString(this.value.sprintf(*scope.args
            .toKotlinList(scope)
            .map { if (it == null) "null" else it }
            .toTypedArray()))
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {
        return if (other is ObjString)
            value.contains(other.value)
        else if (other is ObjChar)
            value.contains(other.value)
        else scope.raiseIllegalArgument("String.contains can't take $other")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjString

        return value == other.value
    }

    override suspend fun operatorMatch(scope: Scope, other: Obj): Obj {
        val re = other.cast<ObjRegex>(scope)
        return re.operatorMatch(scope, this)
    }

    override suspend fun lynonType(): LynonType = LynonType.String

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeBinaryData(value.encodeToByteArray())
    }

    override suspend fun toJson(scope: Scope): JsonElement {
        return JsonPrimitive(value)
    }

    companion object {
        val type = object : ObjClass("String") {
            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
                ObjString(decoder.unpackBinaryData().decodeToString())
        }.apply {
            addFn("toInt") {
                ObjInt(
                    thisAs<ObjString>().value.toLongOrNull()
                        ?: raiseIllegalArgument("can't convert to int: $thisObj")
                )
            }
            addFn("startsWith") {
                ObjBool(thisAs<ObjString>().value.startsWith(requiredArg<ObjString>(0).value))
            }
            addFn("endsWith") {
                ObjBool(thisAs<ObjString>().value.endsWith(requiredArg<ObjString>(0).value))
            }
            addConst("length",
                statement { ObjInt(thisAs<ObjString>().value.length.toLong()) }
            )
            addFn("takeLast") {
                thisAs<ObjString>().value.takeLast(
                    requiredArg<ObjInt>(0).toInt()
                ).let(::ObjString)
            }
            addFn("take") {
                thisAs<ObjString>().value.take(
                    requiredArg<ObjInt>(0).toInt()
                ).let(::ObjString)
            }
            addFn("drop") {
                thisAs<ObjString>().value.drop(
                    requiredArg<ObjInt>(0).toInt()
                ).let(::ObjString)
            }
            addFn("dropLast") {
                thisAs<ObjString>().value.dropLast(
                    requiredArg<ObjInt>(0).toInt()
                ).let(::ObjString)
            }
            addFn("lower") {
                thisAs<ObjString>().value.lowercase().let(::ObjString)
            }
            addFn("upper") {
                thisAs<ObjString>().value.uppercase().let(::ObjString)
            }
            addFn("characters") {
                ObjList(
                    thisAs<ObjString>().value.map { ObjChar(it) }.toMutableList()
                )
            }
            addFn("last") {
                ObjChar(thisAs<ObjString>().value.lastOrNull() ?: raiseNoSuchElement("empty string"))
            }
            addFn("encodeUtf8") { ObjBuffer(thisAs<ObjString>().value.encodeToByteArray().asUByteArray()) }
            addFn("size") { ObjInt(thisAs<ObjString>().value.length.toLong()) }
            addFn("toReal") {
                ObjReal(thisAs<ObjString>().value.toDouble())
            }
            addFn("trim") {
                thisAs<ObjString>().value.trim().let(::ObjString)
            }
            addFn("matches") {
                val s = requireOnlyArg<Obj>()
                val self = thisAs<ObjString>().value
                ObjBool(
                    when (s) {
                        is ObjRegex -> self.matches(s.regex)
                        is ObjString -> {
                            if (s.value == ".*") true
                            else {
                                val re = if (PerfFlags.REGEX_CACHE) RegexCache.get(s.value) else s.value.toRegex()
                                self.matches(re)
                            }
                        }

                        else ->
                            raiseIllegalArgument("can't match ${s.objClass.className}: required Regex or String")
                    }
                )
            }
        }
    }
}