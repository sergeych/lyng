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
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonSettings
import net.sergeych.lynon.LynonType
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.isDistantFuture
import kotlin.time.isDistantPast

class ObjInstant(val instant: Instant,val truncateMode: LynonSettings.InstantTruncateMode=LynonSettings.InstantTruncateMode.Microsecond) : Obj() {
    override val objClass: ObjClass get() = type

    override fun toString(): String {
        return instant.toString()
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        return when (other) {
            is ObjDuration -> ObjInstant(instant + other.duration)
            else -> super.plus(scope, other)
        }
    }

    override suspend fun minus(scope: Scope, other: Obj): Obj {
        return when (other) {
            is ObjDuration -> ObjInstant(instant - other.duration)
            is ObjInstant -> ObjDuration(instant - other.instant)
            else -> super.plus(scope, other)
        }
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if( other is ObjInstant) {
            return instant.compareTo(other.instant)
        }
        return super.compareTo(scope, other)
    }

    override suspend fun toKotlin(scope: Scope): Any {
        return instant
    }

    override fun hashCode(): Int {
        return instant.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjInstant

        return instant == other.instant
    }

    override suspend fun lynonType(): LynonType = LynonType.Instant

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.putBits(truncateMode.ordinal, 2)
        when(truncateMode) {
            LynonSettings.InstantTruncateMode.Millisecond ->
                encoder.encodeSigned(instant.toEpochMilliseconds())
            LynonSettings.InstantTruncateMode.Second ->
                encoder.encodeSigned(instant.epochSeconds)
            LynonSettings.InstantTruncateMode.Microsecond -> {
                encoder.encodeSigned(instant.epochSeconds)
                encoder.encodeUnsigned(instant.nanosecondsOfSecond.toULong() / 1000UL)
            }
        }
    }

    override suspend fun toJson(scope: Scope): JsonElement = JsonPrimitive(instant.toString())


    companion object {
        val distantFuture by lazy {
            ObjInstant(Instant.DISTANT_FUTURE)
        }

        val distantPast by lazy {
            ObjInstant(Instant.DISTANT_PAST)
        }

        val type = object : ObjClass("Instant") {
            override suspend fun callOn(scope: Scope): Obj {
                val args = scope.args
                val a0 = args.list.getOrNull(0)
                return ObjInstant(
                    when (a0) {
                        null -> {
                            val t = Clock.System.now()
                            Instant.fromEpochSeconds(t.epochSeconds, t.nanosecondsOfSecond)
                        }
                        is ObjInt -> Instant.fromEpochSeconds(a0.value)
                        is ObjReal -> {
                            val seconds = a0.value.toLong()
                            val nanos = (a0.value - seconds) * 1e9
                            Instant.fromEpochSeconds(seconds, nanos.toLong())
                        }
                        is ObjInstant -> a0.instant

                        else -> {
                            scope.raiseIllegalArgument("can't construct Instant(${args.inspect(scope)})")
                        }
                    }
                )
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                val mode = LynonSettings.InstantTruncateMode.entries[decoder.getBitsAsInt(2)]
                return when (mode) {
                    LynonSettings.InstantTruncateMode.Microsecond -> ObjInstant(
                        Instant.fromEpochSeconds(
                            decoder.unpackSigned(), decoder.unpackUnsignedInt() * 1000
                        )
                    )
                    LynonSettings.InstantTruncateMode.Millisecond -> ObjInstant(
                        Instant.fromEpochMilliseconds(
                            decoder.unpackSigned()
                        )
                    )
                    LynonSettings.InstantTruncateMode.Second -> ObjInstant(
                        Instant.fromEpochSeconds(decoder.unpackSigned())
                    )
                }
            }

        }.apply {
            addFn("epochSeconds") {
                val instant = thisAs<ObjInstant>().instant
                ObjReal(instant.epochSeconds + instant.nanosecondsOfSecond * 1e-9)
            }
            addFn("isDistantFuture") {
                thisAs<ObjInstant>().instant.isDistantFuture.toObj()
            }
            addFn("isDistantPast") {
                thisAs<ObjInstant>().instant.isDistantPast.toObj()
            }
            addFn("epochWholeSeconds") {
                ObjInt(thisAs<ObjInstant>().instant.epochSeconds)
            }
            addFn("nanosecondsOfSecond") {
                ObjInt(thisAs<ObjInstant>().instant.nanosecondsOfSecond.toLong())
            }
            addFn("truncateToSecond") {
                val t = thisAs<ObjInstant>().instant
                ObjInstant(Instant.fromEpochSeconds(t.epochSeconds), LynonSettings.InstantTruncateMode.Second)
            }
            addFn("truncateToMillisecond") {
                val t = thisAs<ObjInstant>().instant
                ObjInstant(
                    Instant.fromEpochSeconds(t.epochSeconds, t.nanosecondsOfSecond / 1_000_000 * 1_000_000),
                    LynonSettings.InstantTruncateMode.Millisecond
                )
            }
            addFn("truncateToMicrosecond") {
                val t = thisAs<ObjInstant>().instant
                ObjInstant(
                    Instant.fromEpochSeconds(t.epochSeconds, t.nanosecondsOfSecond / 1_000 * 1_000),
                    LynonSettings.InstantTruncateMode.Microsecond
                )
            }
            // class members

            addClassConst("distantFuture", distantFuture)
            addClassConst("distantPast", distantPast)
            addClassFn("now") {
                ObjInstant(Clock.System.now())
            }
//            addFn("epochMilliseconds") {
//                ObjInt(instant.toEpochMilliseconds())
//            }
        }

    }
}


