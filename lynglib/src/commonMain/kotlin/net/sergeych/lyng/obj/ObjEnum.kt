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

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.*
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

open class ObjEnumEntry(enumClass: ObjEnumClass, val name: ObjString, val ordinal: ObjInt) : Obj() {
    override val objClass = enumClass

    override fun toString(): String {
        return "$objClass.$name"
    }

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeUnsigned(ordinal.value.toULong())
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if( other !is ObjEnumEntry) return -2
        if( other.objClass != objClass ) return -2
        return ordinal.compareTo(scope, other.ordinal)
    }

    override suspend fun toJson(scope: Scope): JsonElement {
        return JsonPrimitive(name.value)
    }

}

object EnumBase : ObjClass("Enum") {

}

class ObjEnumClass(val name: String) : ObjClass(name, EnumBase) {
    val objEntries = ObjList()
    val byName by lazy { objEntries.list.associateBy { (it as ObjEnumEntry).name } }

    init {
        addClassConst("entries", objEntries )
        addClassFn("valueOf") {
            val name = requireOnlyArg<ObjString>()
            byName[name] ?: raiseSymbolNotFound("does not exists: enum ${className}.$name")
        }
        addFn("name") { thisAs<ObjEnumEntry>().name }
        addFn("ordinal") { thisAs<ObjEnumEntry>().ordinal }

    }

    override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
        val index = decoder.unpackUnsigned().toInt()
        return objEntries.list[index]
    }

    companion object {
        fun createSimpleEnum(enumName: String, names: List<String>): ObjEnumClass {
            val klass = ObjEnumClass(enumName)
            names.forEachIndexed { index, name ->
                val entry = ObjEnumEntry(klass, ObjString(name), ObjInt(index.toLong(), isConst = true))
                klass.objEntries.list += entry
                klass.addClassConst(name, entry)
            }
            return klass
        }
    }

}