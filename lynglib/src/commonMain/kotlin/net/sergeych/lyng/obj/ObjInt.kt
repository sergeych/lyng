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

class ObjInt(var value: Long, override val isConst: Boolean = false) : Obj(), Numeric {
    override val asStr get() = ObjString(value.toString())
    override val longValue get() = value
    override val doubleValue get() = value.toDouble()
    override val toObjInt get() = this
    override val toObjReal = ObjReal(doubleValue)

    override fun byValueCopy(): Obj = ObjInt(value)

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override suspend fun getAndIncrement(scope: Scope): Obj {
        ensureNotConst(scope)
        return ObjInt(value).also { value++ }
    }

    override suspend fun getAndDecrement(scope: Scope): Obj {
        ensureNotConst(scope)
        return ObjInt(value).also { value-- }
    }

    override suspend fun incrementAndGet(scope: Scope): Obj {
        ensureNotConst(scope)
        return ObjInt(++value)
    }

    override suspend fun decrementAndGet(scope: Scope): Obj {
        ensureNotConst(scope)
        return ObjInt(--value)
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is Numeric) return -2
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String = value.toString()

    override val objClass: ObjClass by lazy { type }

    override suspend fun plus(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value + other.value)
        else
            ObjReal(this.doubleValue + other.toDouble())

    override suspend fun minus(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value - other.value)
        else
            ObjReal(this.doubleValue - other.toDouble())

    override suspend fun mul(scope: Scope, other: Obj): Obj =
        if (other is ObjInt) {
            ObjInt(this.value * other.value)
        } else ObjReal(this.value * other.toDouble())

    override suspend fun div(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value / other.value)
        else ObjReal(this.value / other.toDouble())

    override suspend fun mod(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value % other.value)
        else ObjReal(this.value.toDouble() % other.toDouble())

    /**
     * We are by-value type ([byValueCopy] is implemented) so we can do in-place
     * assignment
     */
    override suspend fun assign(scope: Scope, other: Obj): Obj? {
        return if (!isConst && other is ObjInt) {
            value = other.value
            this
        } else null
    }

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
        return ObjInt(-value)
    }

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

    companion object {
        val Zero = ObjInt(0, true)
        val One = ObjInt(1, true)
        val type = object : ObjClass("Int") {
            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
                when (lynonType) {
                    null -> ObjInt(decoder.unpackSigned())
                    LynonType.Int0 -> Zero
                    LynonType.IntPositive -> ObjInt(decoder.unpackUnsigned().toLong())
                    LynonType.IntNegative -> ObjInt(-decoder.unpackUnsigned().toLong())
                    LynonType.IntSigned -> ObjInt(decoder.unpackSigned())
                    else -> scope.raiseIllegalState("illegal type code for Int: $lynonType")
                }
        }
    }
}

fun Int.toObj() = ObjInt(this.toLong())