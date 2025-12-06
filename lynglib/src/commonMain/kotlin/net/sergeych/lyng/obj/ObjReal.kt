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
import kotlinx.serialization.json.JsonPrimitive
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.addConstDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lyng.statement
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType
import kotlin.math.floor
import kotlin.math.roundToLong

data class ObjReal(val value: Double) : Obj(), Numeric {
    override val longValue: Long by lazy { floor(value).toLong() }
    override val doubleValue: Double by lazy { value }
    override val toObjInt: ObjInt by lazy { ObjInt(longValue) }
    override val toObjReal: ObjReal by lazy { ObjReal(value) }

    override val objClass: ObjClass = type

    override fun byValueCopy(): Obj = ObjReal(value)

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is Numeric) return -2
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String {
        // Normalize scientific notation to match tests across platforms.
        // Kotlin/JVM prints 1e-6 as "1.0E-6" by default; tests accept "1E-6" (or a plain decimal).
        val s = value.toString()
        val ePos = s.indexOf('E').let { if (it >= 0) it else s.indexOf('e') }
        if (ePos >= 0) {
            val mantissa = s.substring(0, ePos)
            val exponent = s.substring(ePos + 1) // skip the 'E'/'e'
            val mantissaNorm = if (mantissa.endsWith(".0")) mantissa.dropLast(2) else mantissa
            return mantissaNorm + "E" + exponent
        }
        return s
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj =
        ObjReal(this.value + other.toDouble())

    override suspend fun minus(scope: Scope, other: Obj): Obj =
        ObjReal(this.value - other.toDouble())

    override suspend fun mul(scope: Scope, other: Obj): Obj =
        ObjReal(this.value * other.toDouble())

    override suspend fun div(scope: Scope, other: Obj): Obj =
        ObjReal(this.value / other.toDouble())

    override suspend fun mod(scope: Scope, other: Obj): Obj =
        ObjReal(this.value % other.toDouble())

    /**
     * Returns unboxed Double value
     */
    override suspend fun toKotlin(scope: Scope): Any {
        return value
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjReal

        return value == other.value
    }

    override suspend fun negate(scope: Scope): Obj {
        return ObjReal(-value)
    }

    override suspend fun lynonType(): LynonType = LynonType.Real

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeReal(value)
    }

    override suspend fun toJson(scope: Scope): JsonElement {
        return JsonPrimitive(value)
    }

    companion object {
        val type: ObjClass = object : ObjClass("Real") {
            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
                ObjReal(decoder.unpackDouble())
        }.apply {
            // roundToInt: number rounded to the nearest integer
            addConstDoc(
                name = "roundToInt",
                value = statement(Pos.builtIn) {
                    (it.thisObj as ObjReal).value.roundToLong().toObj()
                },
                doc = "This real number rounded to the nearest integer.",
                type = type("lyng.Int"),
                moduleName = "lyng.stdlib"
            )
            addFnDoc(
                name = "toInt",
                doc = "Truncate this real number toward zero to an integer.",
                returns = type("lyng.Int"),
                moduleName = "lyng.stdlib"
            ) {
                ObjInt(thisAs<ObjReal>().value.toLong())
            }
        }
    }
}