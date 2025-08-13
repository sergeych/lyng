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

import net.sergeych.lyng.Scope
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

data class ObjBool(val value: Boolean) : Obj() {
    override val asStr by lazy { ObjString(value.toString()) }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is ObjBool) return -2
        return value.compareTo(other.value)
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun toString(): String = value.toString()

    override val objClass: ObjClass = type

    override suspend fun logicalNot(scope: Scope): Obj = ObjBool(!value)

    override suspend fun logicalAnd(scope: Scope, other: Obj): Obj = ObjBool(value && other.toBool())

    override suspend fun logicalOr(scope: Scope, other: Obj): Obj = ObjBool(value || other.toBool())

    override suspend fun toKotlin(scope: Scope): Any {
        return value
    }

    override suspend fun lynonType(): LynonType = LynonType.Bool

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeBoolean(value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjBool

        return value == other.value
    }

    companion object {
        val type = object : ObjClass("Bool") {
            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder,lynonType: LynonType?): Obj {
                return ObjBool(decoder.unpackBoolean())
            }
        }
    }
}

val ObjTrue = ObjBool(true)
val ObjFalse = ObjBool(false)