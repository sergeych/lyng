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

package net.sergeych.lyng.serialization

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.sergeych.lyng.Arguments
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBitBuffer
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjDate
import net.sergeych.lyng.obj.ObjDateTime
import net.sergeych.lyng.obj.ObjEnumClass
import net.sergeych.lyng.obj.ObjEnumEntry
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjImmutableList
import net.sergeych.lyng.obj.ObjImmutableMap
import net.sergeych.lyng.obj.ObjImmutableSet
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjInstant
import net.sergeych.lyng.obj.ObjList
import net.sergeych.lyng.obj.ObjMap
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjReal
import net.sergeych.lyng.obj.ObjSet
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lynon.BitArray
import net.sergeych.mp_tools.decodeBase64Url
import net.sergeych.mp_tools.encodeToBase64Url
import kotlin.time.Instant

private const val TYPE_KEY = "@lyng"
private const val VALUE_KEY = "value"
private const val ITEMS_KEY = "items"
private const val ENTRIES_KEY = "entries"
private const val CLASS_KEY = "class"
private const val NAME_KEY = "name"
private const val ARGS_KEY = "args"
private const val VARS_KEY = "vars"
private const val BASE64_KEY = "base64"
private const val LAST_BYTE_BITS_KEY = "lastByteBits"
private const val MESSAGE_KEY = "message"
private const val EXTRA_DATA_KEY = "extraData"
private const val STACK_TRACE_KEY = "stackTrace"

object ObjJsonClass : ObjSerializationFormatClass("Json") {

    override suspend fun encodeValue(scope: Scope, value: Obj): Obj =
        ObjString(encodeToJsonElement(scope, value).toString())

    override suspend fun decodeValue(scope: Scope, encoded: Obj): Obj {
        val text = when (encoded) {
            is ObjString -> encoded.value
            else -> encoded.toString(scope).value
        }
        return decodeFromJsonElement(scope, Json.parseToJsonElement(text))
    }

    suspend fun encodeToJsonElement(scope: Scope, value: Obj): JsonElement =
        UniversalJsonCodec.encode(scope, value)

    suspend fun decodeFromJsonElement(scope: Scope, element: JsonElement): Obj =
        UniversalJsonCodec.decode(scope, element)
}

suspend fun Obj.toUniversalJsonElement(scope: Scope = Scope()): JsonElement =
    ObjJsonClass.encodeToJsonElement(scope, this)

suspend fun decodeUniversalJsonElement(element: JsonElement, scope: Scope = Scope()): Obj =
    ObjJsonClass.decodeFromJsonElement(scope, element)

private object UniversalJsonCodec {
    suspend fun encode(scope: Scope, value: Obj): JsonElement = when (value) {
        ObjVoid -> tagged("void")
        ObjNull -> JsonNull
        is ObjBool -> JsonPrimitive(value.value)
        is ObjInt -> JsonPrimitive(value.value)
        is ObjReal -> if (value.value.isFinite()) {
            JsonPrimitive(value.value)
        } else {
            tagged("real", VALUE_KEY to JsonPrimitive(value.value.toString()))
        }
        is ObjString -> JsonPrimitive(value.value)
        is ObjDate -> tagged("date", VALUE_KEY to JsonPrimitive(value.date.toString()))
        is ObjInstant -> tagged("instant", VALUE_KEY to JsonPrimitive(value.instant.toString()))
        is ObjDateTime -> tagged("dateTime", VALUE_KEY to JsonPrimitive(value.toRFC3339()))
        is ObjBuffer -> tagged("buffer", BASE64_KEY to JsonPrimitive(value.base64))
        is ObjBitBuffer -> tagged(
            "bitBuffer",
            BASE64_KEY to JsonPrimitive(value.bitArray.asUByteArray().asByteArray().encodeToBase64Url()),
            LAST_BYTE_BITS_KEY to JsonPrimitive(value.bitArray.lastByteBits)
        )
        is ObjImmutableList -> tagged("immutableList", ITEMS_KEY to JsonArray(value.toMutableList().map { encode(scope, it) }))
        is ObjList -> JsonArray(value.list.map { encode(scope, it) })
        is ObjImmutableSet -> tagged("immutableSet", ITEMS_KEY to JsonArray(value.toMutableSet().map { encode(scope, it) }))
        is ObjSet -> tagged("set", ITEMS_KEY to JsonArray(value.set.map { encode(scope, it) }))
        is ObjImmutableMap -> tagged("immutableMap", ENTRIES_KEY to encodeEntries(scope, value.map.entries.map { it.toPair() }))
        is ObjMap -> encodeMap(scope, value)
        is ObjEnumEntry -> tagged(
            "enum",
            CLASS_KEY to JsonPrimitive(value.objClass.className),
            NAME_KEY to JsonPrimitive(value.name.value)
        )
        is ObjException -> tagged(
            "exception",
            CLASS_KEY to JsonPrimitive(value.exceptionClass.className),
            MESSAGE_KEY to encode(scope, value.message),
            EXTRA_DATA_KEY to encode(scope, value.extraData),
            STACK_TRACE_KEY to encode(scope, value.getStackTrace())
        )
        is ObjClass -> tagged("class", NAME_KEY to JsonPrimitive(value.className))
        is ObjInstance -> if (value.objClass.isSingletonObject) {
            encodeSingletonObject(scope, value)
        } else {
            encodeInstance(scope, value)
        }
        else -> scope.raiseNotImplemented("Json.encode can't serialize ${value.objClass.className}")
    }

    suspend fun decode(scope: Scope, element: JsonElement): Obj = when (element) {
        JsonNull -> ObjNull
        is JsonPrimitive -> decodePrimitive(element)
        is JsonArray -> ObjList(element.map { decode(scope, it) }.toMutableList())
        is JsonObject -> decodeObject(scope, element)
    }

    private suspend fun encodeMap(scope: Scope, value: ObjMap): JsonElement {
        if (value.map.keys.all { it is ObjString } && TYPE_KEY !in value.map.keys.map { (it as ObjString).value }) {
            return JsonObject(
                value.map.entries.associate { (k, v) ->
                    (k as ObjString).value to encode(scope, v)
                }
            )
        }
        return tagged("map", ENTRIES_KEY to encodeEntries(scope, value.map.entries.map { it.toPair() }))
    }

    private suspend fun encodeEntries(scope: Scope, entries: List<Pair<Obj, Obj>>): JsonArray =
        JsonArray(entries.map { (k, v) -> JsonArray(listOf(encode(scope, k), encode(scope, v))) })

    private suspend fun encodeInstance(scope: Scope, value: ObjInstance): JsonElement {
        val meta = value.objClass.constructorMeta
            ?: scope.raiseError("can't serialize non-serializable object (no constructor meta)")
        val args = linkedMapOf<String, JsonElement>()
        for (param in meta.params) {
            if (!param.isTransient) {
                args[param.name] = encode(scope, value.readField(scope, param.name).value)
            }
        }
        val vars = linkedMapOf<String, JsonElement>()
        for ((key, record) in value.serializingVars) {
            vars[key.substringAfterLast("::")] = encode(scope, record.value)
        }
        return tagged(
            "instance",
            CLASS_KEY to JsonPrimitive(value.objClass.className),
            ARGS_KEY to JsonObject(args),
            VARS_KEY to JsonObject(vars)
        )
    }

    private suspend fun encodeSingletonObject(scope: Scope, value: ObjInstance): JsonElement {
        val vars = linkedMapOf<String, JsonElement>()
        for ((key, record) in value.serializingVars) {
            vars[key.substringAfterLast("::")] = encode(scope, record.value)
        }
        return tagged(
            "object",
            NAME_KEY to JsonPrimitive(value.objClass.className),
            VARS_KEY to JsonObject(vars)
        )
    }

    private suspend fun decodeObject(scope: Scope, element: JsonObject): Obj {
        val tag = element[TYPE_KEY]?.jsonPrimitive?.content
        if (tag == null) {
            val map = linkedMapOf<Obj, Obj>()
            for ((k, v) in element) {
                map[ObjString(k)] = decode(scope, v)
            }
            return ObjMap(map.toMutableMap())
        }
        return when (tag) {
            "void" -> ObjVoid
            "real" -> decodeTaggedReal(element)
            "date" -> ObjDate(LocalDate.parse(requiredString(element, VALUE_KEY)))
            "instant" -> ObjInstant(Instant.parse(requiredString(element, VALUE_KEY)))
            "dateTime" -> ObjDateTime.type.invokeInstanceMethod(scope, "parseRFC3339", ObjString(requiredString(element, VALUE_KEY)))
            "buffer" -> ObjBuffer(requiredString(element, BASE64_KEY).decodeBase64Url().asUByteArray())
            "bitBuffer" -> ObjBitBuffer(
                BitArray(
                    requiredString(element, BASE64_KEY).decodeBase64Url().asUByteArray(),
                    requiredInt(element, LAST_BYTE_BITS_KEY)
                )
            )
            "immutableList" -> ObjImmutableList(requiredArray(element, ITEMS_KEY).map { decode(scope, it) })
            "set" -> ObjSet(requiredArray(element, ITEMS_KEY).map { decode(scope, it) }.toMutableSet())
            "immutableSet" -> ObjImmutableSet(requiredArray(element, ITEMS_KEY).map { decode(scope, it) })
            "map" -> decodeMap(scope, requiredArray(element, ENTRIES_KEY), mutable = true)
            "immutableMap" -> decodeMap(scope, requiredArray(element, ENTRIES_KEY), mutable = false)
            "class" -> resolveClass(scope, requiredString(element, NAME_KEY))
            "enum" -> decodeEnum(scope, element)
            "instance" -> decodeInstance(scope, element)
            "object" -> decodeSingletonObject(scope, element)
            "exception" -> decodeException(scope, element)
            else -> scope.raiseIllegalArgument("unknown Json type tag '$tag'")
        }
    }

    private fun decodePrimitive(element: JsonPrimitive): Obj {
        element.booleanOrNull?.let { return ObjBool(it) }
        if (element.isString) return ObjString(element.content)
        val raw = element.content
        return if (!raw.contains('.') && !raw.contains('e', ignoreCase = true)) {
            element.longOrNull?.let { ObjInt.of(it) } ?: ObjReal(raw.toDouble())
        } else {
            ObjReal(element.doubleOrNull ?: raw.toDouble())
        }
    }

    private fun decodeTaggedReal(element: JsonObject): ObjReal {
        val raw = requiredString(element, VALUE_KEY)
        val value = when (raw) {
            "NaN" -> Double.NaN
            "Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> raw.toDouble()
        }
        return ObjReal(value)
    }

    private suspend fun decodeMap(scope: Scope, entries: JsonArray, mutable: Boolean): Obj {
        val pairs = entries.map { item ->
            val pair = item as? JsonArray ?: scope.raiseIllegalArgument("map entry must be a JSON array")
            if (pair.size != 2) scope.raiseIllegalArgument("map entry must contain exactly 2 items")
            decode(scope, pair[0]) to decode(scope, pair[1])
        }
        return if (mutable) ObjMap(pairs.toMap().toMutableMap()) else ObjImmutableMap(pairs.toMap())
    }

    private suspend fun decodeEnum(scope: Scope, element: JsonObject): Obj {
        val klass = resolveClass(scope, requiredString(element, CLASS_KEY))
        if (klass !is ObjEnumClass) scope.raiseClassCastError("${klass.className} is not an enum")
        return klass.invokeInstanceMethod(scope, "valueOf", ObjString(requiredString(element, NAME_KEY)))
    }

    private suspend fun decodeInstance(scope: Scope, element: JsonObject): Obj {
        val klass = resolveClass(scope, requiredString(element, CLASS_KEY))
        val meta = klass.constructorMeta
            ?: scope.raiseError("can't deserialize ${klass.className} from Json: no constructor meta")
        val argsObject = requiredObject(element, ARGS_KEY)
        val namedArgs = linkedMapOf<String, Obj>()
        for (param in meta.params) {
            if (param.isTransient) continue
            val encoded = argsObject[param.name]
            if (encoded == null) {
                if (param.defaultValue == null && !param.type.isNullable) {
                    scope.raiseIllegalArgument("missing constructor field '${param.name}' for ${klass.className}")
                }
            } else {
                namedArgs[param.name] = decode(scope, encoded)
            }
        }
        val callScope = scope.createChildScope(args = Arguments(list = emptyList(), named = namedArgs))
        val instance = klass.callOn(callScope)
        if (instance is ObjInstance) {
            val varsObject = requiredObject(element, VARS_KEY)
            for ((name, encoded) in varsObject) {
                val target = resolveSerializableVar(instance, name)
                    ?: scope.raiseIllegalArgument("unknown serializable field '${klass.className}.$name'")
                target.value = decode(scope, encoded)
            }
        }
        return instance
    }

    private suspend fun decodeSingletonObject(scope: Scope, element: JsonObject): Obj {
        val instance = resolveObject(scope, requiredString(element, NAME_KEY))
        val varsObject = requiredObject(element, VARS_KEY)
        for ((name, encoded) in varsObject) {
            val target = resolveSerializableVar(instance, name)
                ?: scope.raiseIllegalArgument("unknown serializable field '${instance.objClass.className}.$name'")
            target.value = decode(scope, encoded)
        }
        return instance
    }

    private suspend fun decodeException(scope: Scope, element: JsonObject): Obj {
        val klass = resolveClass(scope, requiredString(element, CLASS_KEY))
        if (klass !is ObjException.Companion.ExceptionClass) {
            scope.raiseClassCastError("${klass.className} is not an exception class")
        }
        val message = decode(scope, requireElement(element, MESSAGE_KEY)) as? ObjString
            ?: scope.raiseClassCastError("exception message must be a string")
        val extraData = decode(scope, requireElement(element, EXTRA_DATA_KEY))
        val stackTrace = decode(scope, requireElement(element, STACK_TRACE_KEY)) as? ObjList
            ?: scope.raiseClassCastError("exception stackTrace must be a list")
        return ObjException(klass, scope, message, extraData, stackTrace)
    }

    private fun resolveSerializableVar(instance: ObjInstance, name: String) =
        instance.serializingVars[name]
            ?: instance.serializingVars.entries.singleOrNull { it.key.substringAfterLast("::") == name }?.value

    private suspend fun resolveClass(scope: Scope, className: String): ObjClass {
        scope.get(className)?.value?.let {
            if (it is ObjClass) return it
            if (it is ObjInstance && it.objClass.className == className) return it.objClass
            scope.raiseClassCastError("Expected class $className, got ${it.objClass.className}")
        }
        val resolved = scope.resolveQualifiedIdentifier(className)
        if (resolved is ObjClass) return resolved
        if (resolved is ObjInstance && resolved.objClass.className == className) return resolved.objClass
        scope.raiseClassCastError("Expected class $className, got ${resolved.objClass.className}")
        return resolved as ObjClass
    }

    private suspend fun resolveObject(scope: Scope, objectName: String): ObjInstance {
        scope.get(objectName)?.value?.let {
            if (it is ObjInstance) return it
            scope.raiseClassCastError("Expected object $objectName, got ${it.objClass.className}")
        }
        if (objectName.contains('.')) {
            val resolved = scope.resolveQualifiedIdentifier(objectName)
            val inst = resolved as? ObjInstance
            if (inst != null) return inst
            scope.raiseClassCastError("Expected object $objectName, got ${resolved.objClass.className}")
        }
        scope.raiseSymbolNotFound(objectName)
    }

    private fun tagged(type: String, vararg fields: Pair<String, JsonElement>): JsonObject =
        JsonObject(linkedMapOf(TYPE_KEY to JsonPrimitive(type), *fields))

    private fun requiredString(element: JsonObject, key: String): String =
        requireElement(element, key).jsonPrimitive.content

    private fun requiredInt(element: JsonObject, key: String): Int =
        requireElement(element, key).jsonPrimitive.content.toInt()

    private fun requiredArray(element: JsonObject, key: String): JsonArray =
        requireElement(element, key) as? JsonArray
            ?: throw IllegalArgumentException("field '$key' must be a JSON array")

    private fun requiredObject(element: JsonObject, key: String): JsonObject =
        requireElement(element, key) as? JsonObject
            ?: throw IllegalArgumentException("field '$key' must be a JSON object")

    private fun requireElement(element: JsonObject, key: String): JsonElement =
        element[key] ?: throw IllegalArgumentException("missing field '$key'")
}
