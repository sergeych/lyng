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
import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjInt(val value: Long, override val isConst: Boolean = false) : Obj(), Numeric {
    override val longValue get() = value
    override val doubleValue get() = value.toDouble()
    override val toObjInt get() = this
    override val toObjReal = ObjReal.of(doubleValue)

    override fun byValueCopy(): Obj = this

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override suspend fun getAndIncrement(scope: Scope): Obj {
        return this
    }

    override suspend fun getAndDecrement(scope: Scope): Obj {
        return this
    }

    override suspend fun incrementAndGet(scope: Scope): Obj {
        return of(value + 1)
    }

    override suspend fun decrementAndGet(scope: Scope): Obj {
        return of(value - 1)
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is Numeric) return -2
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String = value.toString()

    override val objClass: ObjClass by lazy { type }

    override suspend fun plus(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            of(this.value + other.value)
        else
            ObjReal.of(this.doubleValue + other.toDouble())

    override suspend fun minus(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            of(this.value - other.value)
        else
            ObjReal.of(this.doubleValue - other.toDouble())

    override suspend fun mul(scope: Scope, other: Obj): Obj =
        if (other is ObjInt) {
            of(this.value * other.value)
        } else ObjReal.of(this.value * other.toDouble())

    override suspend fun div(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            of(this.value / other.value)
        else ObjReal.of(this.value / other.toDouble())

    override suspend fun mod(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            of(this.value % other.value)
        else ObjReal.of(this.value.toDouble() % other.toDouble())

    /**
     * Numbers are now immutable, so we can't do in-place assignment.
     */
    override suspend fun assign(scope: Scope, other: Obj): Obj? = null

    override suspend fun toKotlin(scope: Scope): Any {
        return value
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjInt

        return value == other.value
    }

    override suspend fun negate(scope: Scope): Obj {
        return of(-value)
    }

    // Bitwise operations
    override suspend fun bitAnd(scope: Scope, other: Obj): Obj =
        if (other is ObjInt) of(this.value and other.value)
        else scope.raiseIllegalArgument("bitwise and '&' requires Int, got ${other.objClass.className}")

    override suspend fun bitOr(scope: Scope, other: Obj): Obj =
        if (other is ObjInt) of(this.value or other.value)
        else scope.raiseIllegalArgument("bitwise or '|' requires Int, got ${other.objClass.className}")

    override suspend fun bitXor(scope: Scope, other: Obj): Obj =
        if (other is ObjInt) of(this.value xor other.value)
        else scope.raiseIllegalArgument("bitwise xor '^' requires Int, got ${other.objClass.className}")

    override suspend fun shl(scope: Scope, other: Obj): Obj =
        if (other is ObjInt) of(this.value shl (other.value.toInt() and 63))
        else scope.raiseIllegalArgument("shift left '<<' requires Int, got ${other.objClass.className}")

    override suspend fun shr(scope: Scope, other: Obj): Obj =
        if (other is ObjInt) of(this.value shr (other.value.toInt() and 63))
        else scope.raiseIllegalArgument("shift right '>>' requires Int, got ${other.objClass.className}")

    override suspend fun bitNot(scope: Scope): Obj = of(this.value.inv())

    override suspend fun lynonType(): LynonType = when (value) {
        0L -> LynonType.Int0
        else -> {
            if (value > 0) LynonType.IntPositive
            else LynonType.IntNegative
        }
    }


    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        when (lynonType) {
            null -> encoder.encodeSigned(value)
            LynonType.Int0 -> {}
            LynonType.IntPositive -> encoder.encodeUnsigned(value.toULong())
            LynonType.IntNegative -> encoder.encodeUnsigned((-value).toULong())
            LynonType.IntSigned -> encoder.encodeSigned(value)
            else -> scope.raiseIllegalArgument("Unsupported lynon type code for Int: $lynonType")
        }
    }

    override suspend fun toJson(scope: Scope): JsonElement {
        return JsonPrimitive(value)
    }

    companion object {
        private val cache = Array(256) { ObjInt((it - 128).toLong(), true) }

        fun of(value: Long): ObjInt {
            return if (value in -128L..127L) cache[(value + 128).toInt()]
            else ObjInt(value)
        }

        val Zero = of(0)
        val One = of(1)
        val type = object : ObjClass("Int") {
            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
                when (lynonType) {
                    null -> of(decoder.unpackSigned())
                    LynonType.Int0 -> Zero
                    LynonType.IntPositive -> of(decoder.unpackUnsigned().toLong())
                    LynonType.IntNegative -> of(-decoder.unpackUnsigned().toLong())
                    LynonType.IntSigned -> of(decoder.unpackSigned())
                    else -> scope.raiseIllegalState("illegal type code for Int: $lynonType")
                }
        }.apply {
            addFnDoc(
                name = "toInt",
                doc = "Returns this integer (identity operation).",
                returns = net.sergeych.lyng.miniast.type("lyng.Int"),
                moduleName = "lyng.stdlib"
            ) {
                thisObj
            }
        }
    }
}

fun Int.toObj() = ObjInt.of(this.toLong())
fun Long.toObj() = ObjInt.of(this)