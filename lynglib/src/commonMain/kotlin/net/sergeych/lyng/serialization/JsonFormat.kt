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
import net.sergeych.lyng.TypeDecl
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
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.obj.ObjSet
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjTypeExpr
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.matchesTypeDecl
import net.sergeych.lyng.requireExactCount
import net.sergeych.lyng.requireScope
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

    init {
        addClassFn("encodeAs") {
            requireExactCount(2)
            val targetType = typeDeclFromJsonTarget(requireScope(), args[0])
            ObjString(encodeToJsonElement(requireScope(), args[1], targetType).toString())
        }
        addClassFn("decodeAs") {
            requireExactCount(2)
            val scope = requireScope()
            val targetType = typeDeclFromJsonTarget(scope, args[0])
            val text = when (val encoded = args[1]) {
                is ObjString -> encoded.value
                else -> encoded.toString(scope).value
            }
            decodeFromJsonElement(scope, Json.parseToJsonElement(text), targetType)
        }
    }

    override suspend fun encodeValue(scope: Scope, value: Obj): Obj =
        ObjString(encodeToJsonElement(scope, value).toString())

    override suspend fun decodeValue(scope: Scope, encoded: Obj): Obj {
        val text = when (encoded) {
            is ObjString -> encoded.value
            else -> encoded.toString(scope).value
        }
        return decodeFromJsonElement(scope, Json.parseToJsonElement(text))
    }

    suspend fun encodeToJsonElement(scope: Scope, value: Obj, expectedType: TypeDecl? = null): JsonElement =
        UniversalJsonCodec.encode(scope, value, expectedType)

    suspend fun decodeFromJsonElement(scope: Scope, element: JsonElement, expectedType: TypeDecl? = null): Obj =
        UniversalJsonCodec.decode(scope, element, expectedType)
}

suspend fun Obj.toUniversalJsonElement(scope: Scope = Scope()): JsonElement =
    ObjJsonClass.encodeToJsonElement(scope, this)

suspend fun decodeUniversalJsonElement(element: JsonElement, scope: Scope = Scope()): Obj =
    ObjJsonClass.decodeFromJsonElement(scope, element)

private fun typeDeclFromJsonTarget(scope: Scope, target: Obj): TypeDecl = when (target) {
    is ObjTypeExpr -> target.typeDecl
    is ObjClass -> TypeDecl.Simple(target.className, false)
    is ObjInstance -> TypeDecl.Simple(target.objClass.className, false)
    is ObjString -> TypeDecl.Simple(target.value, false)
    else -> scope.raiseClassCastError("Json.encodeAs/decodeAs expects a class or type expression")
}

private object UniversalJsonCodec {
    suspend fun encode(scope: Scope, value: Obj, expectedType: TypeDecl? = null): JsonElement {
        if (expectedType != null) {
            encodeWithExpectedType(scope, value, expectedType)?.let { return it }
        }
        return encodeCanonical(scope, value)
    }

    suspend fun decode(scope: Scope, element: JsonElement, expectedType: TypeDecl? = null): Obj {
        if (expectedType != null) {
            if (element is JsonObject && TYPE_KEY in element) {
                return ensureMatchesExpectedType(scope, decodeCanonical(scope, element), expectedType)
            }
            decodeWithExpectedType(scope, element, expectedType)?.let {
                return ensureMatchesExpectedType(scope, it, expectedType)
            }
        }
        return decodeCanonical(scope, element)
    }

    private suspend fun encodeCanonical(scope: Scope, value: Obj): JsonElement = when (value) {
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
        is ObjImmutableList -> tagged("immutableList", ITEMS_KEY to JsonArray(value.toMutableList().map { encodeCanonical(scope, it) }))
        is ObjList -> JsonArray(value.list.map { encodeCanonical(scope, it) })
        is ObjImmutableSet -> tagged("immutableSet", ITEMS_KEY to JsonArray(value.toMutableSet().map { encodeCanonical(scope, it) }))
        is ObjSet -> tagged("set", ITEMS_KEY to JsonArray(value.set.map { encodeCanonical(scope, it) }))
        is ObjImmutableMap -> tagged("immutableMap", ENTRIES_KEY to encodeEntries(scope, value.map.entries.map { it.toPair() }))
        is ObjMap -> encodeCanonicalMap(scope, value)
        is ObjEnumEntry -> tagged(
            "enum",
            CLASS_KEY to JsonPrimitive(value.objClass.className),
            NAME_KEY to JsonPrimitive(value.name.value)
        )
        is ObjException -> tagged(
            "exception",
            CLASS_KEY to JsonPrimitive(value.exceptionClass.className),
            MESSAGE_KEY to encodeCanonical(scope, value.message),
            EXTRA_DATA_KEY to encodeCanonical(scope, value.extraData),
            STACK_TRACE_KEY to encodeCanonical(scope, value.getStackTrace())
        )
        is ObjClass -> tagged("class", NAME_KEY to JsonPrimitive(value.className))
        is ObjInstance -> if (value.objClass.isSingletonObject) {
            encodeCanonicalSingletonObject(scope, value)
        } else {
            encodeCanonicalInstance(scope, value)
        }
        else -> scope.raiseNotImplemented("Json.encode can't serialize ${value.objClass.className}")
    }

    private suspend fun decodeCanonical(scope: Scope, element: JsonElement): Obj = when (element) {
        JsonNull -> ObjNull
        is JsonPrimitive -> decodePrimitive(element)
        is JsonArray -> ObjList(element.map { decodeCanonical(scope, it) }.toMutableList())
        is JsonObject -> decodeCanonicalObject(scope, element)
    }

    private suspend fun encodeWithExpectedType(scope: Scope, value: Obj, expectedType: TypeDecl): JsonElement? {
        if (value === ObjNull) return JsonNull

        when (value) {
            is ObjBool -> return JsonPrimitive(value.value)
            is ObjInt -> return JsonPrimitive(value.value)
            is ObjReal -> if (value.value.isFinite()) return JsonPrimitive(value.value)
            is ObjString -> return JsonPrimitive(value.value)
            is ObjDate -> if (isExpectedExactClass(scope, expectedType, value.objClass)) return JsonPrimitive(value.date.toString())
            is ObjInstant -> if (isExpectedExactClass(scope, expectedType, value.objClass)) return JsonPrimitive(value.instant.toString())
            is ObjDateTime -> if (isExpectedExactClass(scope, expectedType, value.objClass)) return JsonPrimitive(value.toRFC3339())
            is ObjBuffer -> if (isExpectedExactClass(scope, expectedType, value.objClass)) return JsonPrimitive(value.base64)
            is ObjBitBuffer -> if (isExpectedExactClass(scope, expectedType, value.objClass)) {
                return JsonObject(
                    linkedMapOf(
                        BASE64_KEY to JsonPrimitive(value.bitArray.asUByteArray().asByteArray().encodeToBase64Url()),
                        LAST_BYTE_BITS_KEY to JsonPrimitive(value.bitArray.lastByteBits)
                    )
                )
            }
            is ObjEnumEntry -> if (isExpectedExactClass(scope, expectedType, value.objClass)) {
                return JsonPrimitive(value.name.value)
            }
            is ObjList -> if (expectedBaseName(expectedType) == "List") {
                return JsonArray(value.list.map { encode(scope, it, expectedElementType(expectedType)) })
            }
            is ObjImmutableList -> if (expectedBaseName(expectedType) == "ImmutableList") {
                return JsonArray(value.toMutableList().map { encode(scope, it, expectedElementType(expectedType)) })
            }
            is ObjSet -> if (expectedBaseName(expectedType) == "Set") {
                return JsonArray(value.set.map { encode(scope, it, expectedElementType(expectedType)) })
            }
            is ObjImmutableSet -> if (expectedBaseName(expectedType) == "ImmutableSet") {
                return JsonArray(value.toMutableSet().map { encode(scope, it, expectedElementType(expectedType)) })
            }
            is ObjMap -> if (expectedBaseName(expectedType) == "Map") {
                return encodeTypedMap(scope, value.map, expectedKeyType(expectedType), expectedValueType(expectedType))
            }
            is ObjImmutableMap -> if (expectedBaseName(expectedType) == "ImmutableMap") {
                return encodeTypedMap(scope, value.map, expectedKeyType(expectedType), expectedValueType(expectedType))
            }
            is ObjInstance -> if (isExpectedExactClass(scope, expectedType, value.objClass)) {
                return if (value.objClass.isSingletonObject) encodeTypedSingletonObject(scope, value) else encodeTypedInstance(scope, value)
            }
            else -> Unit
        }

        return null
    }

    private suspend fun decodeWithExpectedType(scope: Scope, element: JsonElement, expectedType: TypeDecl): Obj? = when (element) {
        JsonNull -> ObjNull
        is JsonPrimitive -> decodePrimitiveWithExpectedType(scope, element, expectedType)
        is JsonArray -> decodeArrayWithExpectedType(scope, element, expectedType)
        is JsonObject -> decodeObjectWithExpectedType(scope, element, expectedType)
    }

    private suspend fun encodeCanonicalMap(scope: Scope, value: ObjMap): JsonElement {
        if (value.map.keys.all { it is ObjString } && TYPE_KEY !in value.map.keys.map { (it as ObjString).value }) {
            return JsonObject(
                value.map.entries.associate { (k, v) ->
                    (k as ObjString).value to encodeCanonical(scope, v)
                }
            )
        }
        return tagged("map", ENTRIES_KEY to encodeEntries(scope, value.map.entries.map { it.toPair() }))
    }

    private suspend fun encodeTypedMap(
        scope: Scope,
        map: Map<Obj, Obj>,
        keyType: TypeDecl?,
        valueType: TypeDecl?
    ): JsonElement {
        val stringKeys = keyType != null && expectedBaseName(keyType) == "String"
        if (stringKeys && map.keys.all { it is ObjString } && TYPE_KEY !in map.keys.map { (it as ObjString).value }) {
            return JsonObject(
                map.entries.associate { (k, v) ->
                    (k as ObjString).value to encode(scope, v, valueType)
                }
            )
        }
        return JsonArray(
            map.entries.map { (k, v) ->
                JsonArray(listOf(encode(scope, k, keyType), encode(scope, v, valueType)))
            }
        )
    }

    private suspend fun encodeEntries(scope: Scope, entries: List<Pair<Obj, Obj>>): JsonArray =
        JsonArray(entries.map { (k, v) -> JsonArray(listOf(encodeCanonical(scope, k), encodeCanonical(scope, v))) })

    private suspend fun encodeCanonicalInstance(scope: Scope, value: ObjInstance): JsonElement {
        val meta = value.objClass.constructorMeta
            ?: scope.raiseError("can't serialize non-serializable object (no constructor meta)")
        val args = linkedMapOf<String, JsonElement>()
        for (param in meta.params) {
            if (!param.isTransient) {
                args[param.name] = encodeCanonical(scope, value.readField(scope, param.name).value)
            }
        }
        val vars = linkedMapOf<String, JsonElement>()
        for ((key, record) in value.serializingVars) {
            vars[key.substringAfterLast("::")] = encodeCanonical(scope, record.value)
        }
        return tagged(
            "instance",
            CLASS_KEY to JsonPrimitive(value.objClass.className),
            ARGS_KEY to JsonObject(args),
            VARS_KEY to JsonObject(vars)
        )
    }

    private suspend fun encodeTypedInstance(scope: Scope, value: ObjInstance): JsonObject {
        val meta = value.objClass.constructorMeta
            ?: scope.raiseError("can't serialize non-serializable object (no constructor meta)")
        val fields = linkedMapOf<String, JsonElement>()
        for (param in meta.params) {
            if (!param.isTransient) {
                fields[param.name] = encode(scope, value.readField(scope, param.name).value, param.type)
            }
        }
        for ((key, record) in value.serializingVars) {
            fields[key.substringAfterLast("::")] = encode(scope, record.value, record.typeDecl)
        }
        return JsonObject(fields)
    }

    private suspend fun encodeCanonicalSingletonObject(scope: Scope, value: ObjInstance): JsonElement {
        val vars = linkedMapOf<String, JsonElement>()
        for ((key, record) in value.serializingVars) {
            vars[key.substringAfterLast("::")] = encodeCanonical(scope, record.value)
        }
        return tagged(
            "object",
            NAME_KEY to JsonPrimitive(value.objClass.className),
            VARS_KEY to JsonObject(vars)
        )
    }

    private suspend fun encodeTypedSingletonObject(scope: Scope, value: ObjInstance): JsonObject {
        val vars = linkedMapOf<String, JsonElement>()
        for ((key, record) in value.serializingVars) {
            vars[key.substringAfterLast("::")] = encode(scope, record.value, record.typeDecl)
        }
        return JsonObject(vars)
    }

    private suspend fun decodeCanonicalObject(scope: Scope, element: JsonObject): Obj {
        val tag = element[TYPE_KEY]?.jsonPrimitive?.content
        if (tag == null) {
            val map = linkedMapOf<Obj, Obj>()
            for ((k, v) in element) {
                map[ObjString(k)] = decodeCanonical(scope, v)
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
            "immutableList" -> ObjImmutableList(requiredArray(element, ITEMS_KEY).map { decodeCanonical(scope, it) })
            "set" -> ObjSet(requiredArray(element, ITEMS_KEY).map { decodeCanonical(scope, it) }.toMutableSet())
            "immutableSet" -> ObjImmutableSet(requiredArray(element, ITEMS_KEY).map { decodeCanonical(scope, it) })
            "map" -> decodeCanonicalMap(scope, requiredArray(element, ENTRIES_KEY), mutable = true)
            "immutableMap" -> decodeCanonicalMap(scope, requiredArray(element, ENTRIES_KEY), mutable = false)
            "class" -> resolveClass(scope, requiredString(element, NAME_KEY))
            "enum" -> decodeCanonicalEnum(scope, element)
            "instance" -> decodeCanonicalInstance(scope, element)
            "object" -> decodeCanonicalSingletonObject(scope, element)
            "exception" -> decodeCanonicalException(scope, element)
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

    private suspend fun decodePrimitiveWithExpectedType(scope: Scope, element: JsonPrimitive, expectedType: TypeDecl): Obj? {
        val expectedClass = expectedExactClass(scope, expectedType)
        val baseName = expectedBaseName(expectedType)
        return when {
            expectedClass is ObjEnumClass && element.isString ->
                expectedClass.invokeInstanceMethod(scope, "valueOf", ObjString(element.content))
            baseName == "Bool" -> element.booleanOrNull?.let { ObjBool(it) }
            baseName == "Int" -> if (!element.isString) element.longOrNull?.let { ObjInt.of(it) } else null
            baseName == "Real" -> decodeExpectedReal(element)
            baseName == "String" -> if (element.isString) ObjString(element.content) else null
            baseName == "Date" && element.isString -> ObjDate(LocalDate.parse(element.content))
            baseName == "Instant" && element.isString -> ObjInstant(Instant.parse(element.content))
            baseName == "DateTime" && element.isString ->
                ObjDateTime.type.invokeInstanceMethod(scope, "parseRFC3339", ObjString(element.content))
            baseName == "Buffer" && element.isString -> ObjBuffer(element.content.decodeBase64Url().asUByteArray())
            else -> decodePrimitive(element)
        }
    }

    private fun decodeExpectedReal(element: JsonPrimitive): ObjReal? {
        if (element.isString) {
            return when (element.content) {
                "NaN" -> ObjReal(Double.NaN)
                "Infinity" -> ObjReal(Double.POSITIVE_INFINITY)
                "-Infinity" -> ObjReal(Double.NEGATIVE_INFINITY)
                else -> null
            }
        }
        val raw = element.content
        return ObjReal((element.doubleOrNull ?: raw.toDouble()))
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

    private suspend fun decodeArrayWithExpectedType(scope: Scope, element: JsonArray, expectedType: TypeDecl): Obj? {
        val itemType = expectedElementType(expectedType)
        return when (expectedBaseName(expectedType)) {
            "List" -> ObjList(element.map { decode(scope, it, itemType) }.toMutableList())
            "ImmutableList" -> ObjImmutableList(element.map { decode(scope, it, itemType) })
            "Set" -> ObjSet(element.map { decode(scope, it, itemType) }.toMutableSet())
            "ImmutableSet" -> ObjImmutableSet(element.map { decode(scope, it, itemType) })
            "Map" -> decodeTypedMap(scope, element, mutable = true, keyType = expectedKeyType(expectedType), valueType = expectedValueType(expectedType))
            "ImmutableMap" -> decodeTypedMap(scope, element, mutable = false, keyType = expectedKeyType(expectedType), valueType = expectedValueType(expectedType))
            else -> null
        }
    }

    private suspend fun decodeObjectWithExpectedType(scope: Scope, element: JsonObject, expectedType: TypeDecl): Obj? {
        return when (expectedBaseName(expectedType)) {
            "Map" -> decodeTypedMapObject(scope, element, mutable = true, valueType = expectedValueType(expectedType))
            "ImmutableMap" -> decodeTypedMapObject(scope, element, mutable = false, valueType = expectedValueType(expectedType))
            "BitBuffer" -> {
                val base64 = element[BASE64_KEY]?.jsonPrimitive?.content ?: return null
                val bits = element[LAST_BYTE_BITS_KEY]?.jsonPrimitive?.content?.toInt() ?: return null
                ObjBitBuffer(BitArray(base64.decodeBase64Url().asUByteArray(), bits))
            }
            else -> {
                val klass = expectedExactClass(scope, expectedType) ?: return null
                when {
                    klass.isSingletonObject -> decodeTypedSingletonObject(scope, klass, element)
                    klass is ObjEnumClass -> null
                    else -> decodeTypedInstance(scope, klass, element)
                }
            }
        }
    }

    private suspend fun decodeCanonicalMap(scope: Scope, entries: JsonArray, mutable: Boolean): Obj {
        val pairs = entries.map { item ->
            val pair = item as? JsonArray ?: scope.raiseIllegalArgument("map entry must be a JSON array")
            if (pair.size != 2) scope.raiseIllegalArgument("map entry must contain exactly 2 items")
            decodeCanonical(scope, pair[0]) to decodeCanonical(scope, pair[1])
        }
        return if (mutable) ObjMap(pairs.toMap().toMutableMap()) else ObjImmutableMap(pairs.toMap())
    }

    private suspend fun decodeTypedMap(
        scope: Scope,
        entries: JsonArray,
        mutable: Boolean,
        keyType: TypeDecl?,
        valueType: TypeDecl?
    ): Obj {
        val pairs = entries.map { item ->
            val pair = item as? JsonArray ?: scope.raiseIllegalArgument("map entry must be a JSON array")
            if (pair.size != 2) scope.raiseIllegalArgument("map entry must contain exactly 2 items")
            decode(scope, pair[0], keyType) to decode(scope, pair[1], valueType)
        }
        return if (mutable) ObjMap(pairs.toMap().toMutableMap()) else ObjImmutableMap(pairs.toMap())
    }

    private suspend fun decodeTypedMapObject(
        scope: Scope,
        element: JsonObject,
        mutable: Boolean,
        valueType: TypeDecl?
    ): Obj {
        val map = linkedMapOf<Obj, Obj>()
        for ((k, v) in element) {
            map[ObjString(k)] = decode(scope, v, valueType)
        }
        return if (mutable) ObjMap(map.toMutableMap()) else ObjImmutableMap(map)
    }

    private suspend fun decodeCanonicalEnum(scope: Scope, element: JsonObject): Obj {
        val klass = resolveClass(scope, requiredString(element, CLASS_KEY))
        if (klass !is ObjEnumClass) scope.raiseClassCastError("${klass.className} is not an enum")
        return klass.invokeInstanceMethod(scope, "valueOf", ObjString(requiredString(element, NAME_KEY)))
    }

    private suspend fun decodeCanonicalInstance(scope: Scope, element: JsonObject): Obj {
        val klass = resolveClass(scope, requiredString(element, CLASS_KEY))
        return decodeCanonicalInstanceWithClass(scope, klass, requiredObject(element, ARGS_KEY), requiredObject(element, VARS_KEY))
    }

    private suspend fun decodeTypedInstance(scope: Scope, klass: ObjClass, element: JsonObject): Obj {
        val meta = klass.constructorMeta
            ?: scope.raiseError("can't deserialize ${klass.className} from Json: no constructor meta")
        val namedArgs = linkedMapOf<String, Obj>()
        for (param in meta.params) {
            if (param.isTransient) continue
            val encoded = element[param.name]
            if (encoded == null) {
                if (param.defaultValue == null && !param.type.isNullable) {
                    scope.raiseIllegalArgument("missing constructor field '${param.name}' for ${klass.className}")
                }
            } else {
                namedArgs[param.name] = decode(scope, encoded, param.type)
            }
        }
        val callScope = scope.createChildScope(args = Arguments(list = emptyList(), named = namedArgs))
        val instance = klass.callOn(callScope)
        if (instance is ObjInstance) {
            val ctorNames = meta.params.map { it.name }.toSet()
            for ((name, encoded) in element) {
                if (name in ctorNames) continue
                val target = resolveSerializableVar(instance, name)
                    ?: scope.raiseIllegalArgument("unknown serializable field '${klass.className}.$name'")
                target.value = decode(scope, encoded, target.typeDecl)
            }
        }
        return instance
    }

    private suspend fun decodeCanonicalInstanceWithClass(
        scope: Scope,
        klass: ObjClass,
        argsObject: JsonObject,
        varsObject: JsonObject
    ): Obj {
        val meta = klass.constructorMeta
            ?: scope.raiseError("can't deserialize ${klass.className} from Json: no constructor meta")
        val namedArgs = linkedMapOf<String, Obj>()
        for (param in meta.params) {
            if (param.isTransient) continue
            val encoded = argsObject[param.name]
            if (encoded == null) {
                if (param.defaultValue == null && !param.type.isNullable) {
                    scope.raiseIllegalArgument("missing constructor field '${param.name}' for ${klass.className}")
                }
            } else {
                namedArgs[param.name] = decodeCanonical(scope, encoded)
            }
        }
        val callScope = scope.createChildScope(args = Arguments(list = emptyList(), named = namedArgs))
        val instance = klass.callOn(callScope)
        if (instance is ObjInstance) {
            for ((name, encoded) in varsObject) {
                val target = resolveSerializableVar(instance, name)
                    ?: scope.raiseIllegalArgument("unknown serializable field '${klass.className}.$name'")
                target.value = decodeCanonical(scope, encoded)
            }
        }
        return instance
    }

    private suspend fun decodeCanonicalSingletonObject(scope: Scope, element: JsonObject): Obj {
        val instance = resolveObject(scope, requiredString(element, NAME_KEY))
        val varsObject = requiredObject(element, VARS_KEY)
        for ((name, encoded) in varsObject) {
            val target = resolveSerializableVar(instance, name)
                ?: scope.raiseIllegalArgument("unknown serializable field '${instance.objClass.className}.$name'")
            target.value = decodeCanonical(scope, encoded)
        }
        return instance
    }

    private suspend fun decodeTypedSingletonObject(scope: Scope, klass: ObjClass, element: JsonObject): Obj {
        val instance = resolveObject(scope, klass.className)
        for ((name, encoded) in element) {
            val target = resolveSerializableVar(instance, name)
                ?: scope.raiseIllegalArgument("unknown serializable field '${instance.objClass.className}.$name'")
            target.value = decode(scope, encoded, target.typeDecl)
        }
        return instance
    }

    private suspend fun decodeCanonicalException(scope: Scope, element: JsonObject): Obj {
        val klass = resolveClass(scope, requiredString(element, CLASS_KEY))
        if (klass !is ObjException.Companion.ExceptionClass) {
            scope.raiseClassCastError("${klass.className} is not an exception class")
        }
        val message = decodeCanonical(scope, requireElement(element, MESSAGE_KEY)) as? ObjString
            ?: scope.raiseClassCastError("exception message must be a string")
        val extraData = decodeCanonical(scope, requireElement(element, EXTRA_DATA_KEY))
        val stackTrace = decodeCanonical(scope, requireElement(element, STACK_TRACE_KEY)) as? ObjList
            ?: scope.raiseClassCastError("exception stackTrace must be a list")
        return ObjException(klass, scope, message, extraData, stackTrace)
    }

    private suspend fun ensureMatchesExpectedType(scope: Scope, value: Obj, expectedType: TypeDecl): Obj {
        if (!matchesTypeDecl(scope, value, expectedType)) {
            scope.raiseClassCastError("decoded Json value of type ${value.objClass.className} does not match expected type ${typeName(expectedType)}")
        }
        return value
    }

    private fun resolveSerializableVar(instance: ObjInstance, name: String): ObjRecord? =
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

    private suspend fun expectedExactClass(scope: Scope, expectedType: TypeDecl): ObjClass? {
        val nonNullable = nonNullableType(expectedType)
        val className = when (nonNullable) {
            is TypeDecl.Simple -> nonNullable.name
            is TypeDecl.Generic -> nonNullable.name
            else -> return null
        }
        return resolveClass(scope, className)
    }

    private suspend fun isExpectedExactClass(scope: Scope, expectedType: TypeDecl, actualClass: ObjClass): Boolean =
        expectedExactClass(scope, expectedType) == actualClass

    private fun expectedBaseName(expectedType: TypeDecl?): String? {
        val nonNullable = expectedType?.let { nonNullableType(it) } ?: return null
        return when (nonNullable) {
            is TypeDecl.Simple -> nonNullable.name.substringAfterLast('.')
            is TypeDecl.Generic -> nonNullable.name.substringAfterLast('.')
            else -> null
        }
    }

    private fun expectedTypeArgs(expectedType: TypeDecl?): List<TypeDecl> = when (val nonNullable = expectedType?.let { nonNullableType(it) }) {
        is TypeDecl.Generic -> nonNullable.args
        else -> emptyList()
    }

    private fun expectedElementType(expectedType: TypeDecl?): TypeDecl? = expectedTypeArgs(expectedType).getOrNull(0)

    private fun expectedKeyType(expectedType: TypeDecl?): TypeDecl? = expectedTypeArgs(expectedType).getOrNull(0)

    private fun expectedValueType(expectedType: TypeDecl?): TypeDecl? = expectedTypeArgs(expectedType).getOrNull(1)

    private fun nonNullableType(type: TypeDecl): TypeDecl = when (type) {
        is TypeDecl.Function -> type.copy(nullable = false)
        is TypeDecl.Ellipsis -> type.copy(nullable = false)
        is TypeDecl.TypeVar -> type.copy(nullable = false)
        is TypeDecl.Union -> type.copy(nullable = false)
        is TypeDecl.Intersection -> type.copy(nullable = false)
        is TypeDecl.Simple -> TypeDecl.Simple(type.name, false)
        is TypeDecl.Generic -> TypeDecl.Generic(type.name, type.args, false)
        else -> type
    }

    private fun typeName(type: TypeDecl): String = when (type) {
        TypeDecl.TypeAny -> "Any"
        TypeDecl.TypeNullableAny -> "Any?"
        is TypeDecl.Simple -> type.name + if (type.isNullable) "?" else ""
        is TypeDecl.Generic -> buildString {
            append(type.name)
            append('<')
            append(type.args.joinToString(",") { typeName(it) })
            append('>')
            if (type.isNullable) append('?')
        }
        is TypeDecl.Function -> "Callable"
        is TypeDecl.Ellipsis -> typeName(type.elementType) + "..."
        is TypeDecl.TypeVar -> type.name + if (type.isNullable) "?" else ""
        is TypeDecl.Union -> type.options.joinToString(" | ") { typeName(it) }
        is TypeDecl.Intersection -> type.options.joinToString(" & ") { typeName(it) }
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
