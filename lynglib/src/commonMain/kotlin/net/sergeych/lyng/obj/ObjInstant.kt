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

package net.sergeych.lyng.obj

import kotlinx.datetime.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonSettings
import net.sergeych.lynon.LynonType
import kotlin.time.Clock
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
                        is ObjString -> Instant.parse(a0.value)
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
            addPropertyDoc(
                name = "epochSeconds",
                doc = "Return the number of seconds since the Unix epoch as a real number (including fractions).",
                type = type("lyng.Real"),
                moduleName = "lyng.time",
                getter = {
                    val instant = thisAs<ObjInstant>().instant
                    ObjReal(instant.epochSeconds + instant.nanosecondsOfSecond * 1e-9)
                }
            )
            addPropertyDoc(
                name = "isDistantFuture",
                doc = "Whether this instant represents the distant future.",
                type = type("lyng.Bool"),
                moduleName = "lyng.time",
                getter = { thisAs<ObjInstant>().instant.isDistantFuture.toObj() }
            )
            addPropertyDoc(
                name = "isDistantPast",
                doc = "Whether this instant represents the distant past.",
                type = type("lyng.Bool"),
                moduleName = "lyng.time",
                getter = { thisAs<ObjInstant>().instant.isDistantPast.toObj() }
            )
            addPropertyDoc(
                name = "epochWholeSeconds",
                doc = "Return the number of full seconds since the Unix epoch.",
                type = type("lyng.Int"),
                moduleName = "lyng.time",
                getter = { ObjInt(thisAs<ObjInstant>().instant.epochSeconds) }
            )
            addPropertyDoc(
                name = "nanosecondsOfSecond",
                doc = "The number of nanoseconds within the current second.",
                type = type("lyng.Int"),
                moduleName = "lyng.time",
                getter = { ObjInt(thisAs<ObjInstant>().instant.nanosecondsOfSecond.toLong()) }
            )
            addFnDoc(
                name = "truncateToMinute",
                doc = "Truncate this instant to the nearest minute.",
                returns = type("lyng.Instant"),
                moduleName = "lyng.time"
            ) {
                val t = thisAs<ObjInstant>().instant
                val tz = TimeZone.UTC
                val dt = t.toLocalDateTime(tz)
                val truncated = LocalDateTime(dt.year, dt.month, dt.dayOfMonth, dt.hour, dt.minute, 0, 0)
                ObjInstant(truncated.toInstant(tz), LynonSettings.InstantTruncateMode.Second)
            }
            addFnDoc(
                name = "truncateToSecond",
                doc = "Truncate this instant to the nearest second.",
                returns = type("lyng.Instant"),
                moduleName = "lyng.time"
            ) {
                val t = thisAs<ObjInstant>().instant
                ObjInstant(Instant.fromEpochSeconds(t.epochSeconds), LynonSettings.InstantTruncateMode.Second)
            }
            addFnDoc(
                name = "truncateToMillisecond",
                doc = "Truncate this instant to the nearest millisecond.",
                returns = type("lyng.Instant"),
                moduleName = "lyng.time"
            ) {
                val t = thisAs<ObjInstant>().instant
                ObjInstant(
                    Instant.fromEpochSeconds(t.epochSeconds, t.nanosecondsOfSecond / 1_000_000 * 1_000_000),
                    LynonSettings.InstantTruncateMode.Millisecond
                )
            }
            addFnDoc(
                name = "truncateToMicrosecond",
                doc = "Truncate this instant to the nearest microsecond.",
                returns = type("lyng.Instant"),
                moduleName = "lyng.time"
            ) {
                val t = thisAs<ObjInstant>().instant
                ObjInstant(
                    Instant.fromEpochSeconds(t.epochSeconds, t.nanosecondsOfSecond / 1_000 * 1_000),
                    LynonSettings.InstantTruncateMode.Microsecond
                )
            }

            addFnDoc(
                name = "toRFC3339",
                doc = "Return the RFC3339 string representation of this instant in UTC (e.g., '1970-01-01T00:00:00Z').",
                returns = type("lyng.String"),
                moduleName = "lyng.time"
            ) {
                thisAs<ObjInstant>().instant.toString().toObj()
            }

            addFnDoc(
                name = "toSortableString",
                doc = "Alias to toRFC3339.",
                returns = type("lyng.String"),
                moduleName = "lyng.time"
            ) {
                thisAs<ObjInstant>().instant.toString().toObj()
            }

            addFnDoc(
                name = "toDateTime",
                doc = "Convert this absolute instant to a localized DateTime object in the specified time zone. " +
                        "Accepts a timezone ID string (e.g., 'UTC', '+02:00') or an integer offset in seconds. " +
                        "If no argument is provided, the system's current default time zone is used.",
                params = listOf(net.sergeych.lyng.miniast.ParamDoc("tz", type = type("lyng.Any", true))),
                returns = type("lyng.DateTime"),
                moduleName = "lyng.time"
            ) {
                val tz = when (val a = args.list.getOrNull(0)) {
                    null -> TimeZone.currentSystemDefault()
                    is ObjString -> TimeZone.of(a.value)
                    is ObjInt -> UtcOffset(seconds = a.value.toInt()).asTimeZone()
                    else -> raiseIllegalArgument("invalid timezone: $a")
                }
                ObjDateTime(thisAs<ObjInstant>().instant, tz)
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


