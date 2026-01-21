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
import net.sergeych.lyng.ScopeCallable
import net.sergeych.lyng.Statement
import net.sergeych.lyng.miniast.addClassFnDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjDateTime(val instant: Instant, val timeZone: TimeZone) : Obj() {
    override val objClass: ObjClass get() = type

    val localDateTime: LocalDateTime by lazy {
        instant.toLocalDateTime(timeZone)
    }

    override fun toString(): String {
        return localDateTime.toString() + timeZone.toString()
    }

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        for (cls in objClass.mro) {
            val rec = cls.members[name]
            if (rec != null) {
                if (rec.type == ObjRecord.Type.Property) {
                    val prop = rec.value as? ObjProperty
                        ?: (rec.value as? Statement)?.execute(scope) as? ObjProperty
                    if (prop != null) {
                        return ObjRecord(prop.callGetter(scope, this, rec.declaringClass ?: cls), rec.isMutable)
                    }
                }
                if (rec.type == ObjRecord.Type.Fun || rec.value is Statement) {
                    val s = rec.value as Statement
                    return ObjRecord(net.sergeych.lyng.statement(f = object : ScopeCallable {
                        override suspend fun call(scp: Scope): Obj = s.execute(scp.createChildScope(newThisObj = this@ObjDateTime))
                    }), rec.isMutable)
                }
                return resolveRecord(scope, rec, name, rec.declaringClass ?: cls)
            }
        }
        return super.readField(scope, name)
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        return when (other) {
            is ObjDuration -> ObjDateTime(instant + other.duration, timeZone)
            else -> super.plus(scope, other)
        }
    }

    override suspend fun minus(scope: Scope, other: Obj): Obj {
        return when (other) {
            is ObjDuration -> ObjDateTime(instant - other.duration, timeZone)
            is ObjDateTime -> ObjDuration(instant - other.instant)
            is ObjInstant -> ObjDuration(instant - other.instant)
            else -> super.minus(scope, other)
        }
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other is ObjDateTime) {
            return instant.compareTo(other.instant)
        }
        if (other is ObjInstant) {
            return instant.compareTo(other.instant)
        }
        return super.compareTo(scope, other)
    }

    override suspend fun toKotlin(scope: Scope): Any {
        return localDateTime
    }

    override fun hashCode(): Int {
        return instant.hashCode() xor timeZone.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ObjDateTime) return false
        return instant == other.instant && timeZone == other.timeZone
    }

    override suspend fun lynonType(): LynonType = LynonType.Other

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeCached(this) {
            encodeAny(scope, ObjInstant(instant))
            encodeCached(timeZone.id) {
                encodeBinaryData(timeZone.id.encodeToByteArray())
            }
        }
    }

    override suspend fun toJson(scope: Scope): JsonElement = JsonPrimitive(toRFC3339())

    fun toRFC3339(): String {
        val s = localDateTime.toString()
        val tz = if (timeZone == TimeZone.UTC) "Z" else timeZone.id
        return if (tz.startsWith("+") || tz.startsWith("-") || tz == "Z") s + tz else s + "[" + tz + "]"
    }

    companion object {
        val type = object : ObjClass("DateTime") {
            override suspend fun callOn(scope: Scope): Obj {
                val args = scope.args
                return when (val a0 = args.list.getOrNull(0)) {
                    is ObjInstant -> {
                        val tz = when (val a1 = args.list.getOrNull(1)) {
                            null -> TimeZone.currentSystemDefault()
                            is ObjString -> TimeZone.of(a1.value)
                            is ObjInt -> UtcOffset(seconds = a1.value.toInt()).asTimeZone()
                            else -> scope.raiseIllegalArgument("invalid timezone: $a1")
                        }
                        ObjDateTime(a0.instant, tz)
                    }

                    is ObjInt -> {
                        // DateTime(year, month, day, hour=0, minute=0, second=0, timeZone="UTC")
                        val year = a0.value.toInt()
                        val month = args.list.getOrNull(1)?.toInt() ?: scope.raiseIllegalArgument("month is required")
                        val day = args.list.getOrNull(2)?.toInt() ?: scope.raiseIllegalArgument("day is required")
                        val hour = args.list.getOrNull(3)?.toInt() ?: 0
                        val minute = args.list.getOrNull(4)?.toInt() ?: 0
                        val second = args.list.getOrNull(5)?.toInt() ?: 0
                        val tz = when (val a6 = args.list.getOrNull(6)) {
                            null -> TimeZone.UTC
                            is ObjString -> TimeZone.of(a6.value)
                            is ObjInt -> UtcOffset(seconds = a6.value.toInt()).asTimeZone()
                            else -> scope.raiseIllegalArgument("invalid timezone: $a6")
                        }
                        val ldt = LocalDateTime(year, month, day, hour, minute, second)
                        ObjDateTime(ldt.toInstant(tz), tz)
                    }

                    is ObjString -> {
                        val instant = Instant.parse(a0.value)
                        ObjDateTime(instant, TimeZone.UTC)
                    }

                    else -> scope.raiseIllegalArgument("can't construct DateTime from ${args.inspect(scope)}")
                }
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                return decoder.decodeCached {
                    val instant = (decoder.decodeAny(scope) as ObjInstant).instant
                    val tzId = decoder.decodeCached { decoder.unpackBinaryData().decodeToString() }
                    ObjDateTime(instant, TimeZone.of(tzId))
                }
            }
        }.apply {
            addPropertyDoc("year", "The year component.", type("lyng.Int"), moduleName = "lyng.time",
                getter = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().localDateTime.year.toObj() })
            addPropertyDoc("month", "The month component (1..12).", type("lyng.Int"), moduleName = "lyng.time",
                getter = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().localDateTime.monthNumber.toObj() })
            addPropertyDoc("dayOfMonth", "The day of month component.", type("lyng.Int"), moduleName = "lyng.time",
                getter = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().localDateTime.dayOfMonth.toObj() })
            addPropertyDoc("day", "Alias to dayOfMonth.", type("lyng.Int"), moduleName = "lyng.time",
                getter = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().localDateTime.dayOfMonth.toObj() })
            addPropertyDoc("hour", "The hour component (0..23).", type("lyng.Int"), moduleName = "lyng.time",
                getter = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().localDateTime.hour.toObj() })
            addPropertyDoc("minute", "The minute component (0..59).", type("lyng.Int"), moduleName = "lyng.time",
                getter = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().localDateTime.minute.toObj() })
            addPropertyDoc("second", "The second component (0..59).", type("lyng.Int"), moduleName = "lyng.time",
                getter = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().localDateTime.second.toObj() })
            addPropertyDoc("dayOfWeek", "The day of week (1=Monday, 7=Sunday).", type("lyng.Int"), moduleName = "lyng.time",
                getter = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().localDateTime.dayOfWeek.isoDayNumber.toObj() })
            addPropertyDoc("timeZone", "The time zone ID (e.g. 'Z', '+02:00', 'Europe/Prague').", type("lyng.String"), moduleName = "lyng.time",
                getter = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().timeZone.id.toObj() })

            addFnDoc("toInstant", "Convert this localized date time back to an absolute Instant.", returns = type("lyng.Instant"), moduleName = "lyng.time",
                code = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjInstant(scp.thisAs<ObjDateTime>().instant) })
            addFnDoc("toEpochSeconds", "Return the number of full seconds since the Unix epoch (UTC).", returns = type("lyng.Int"), moduleName = "lyng.time",
                code = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().instant.epochSeconds.toObj() })
            addFnDoc("toRFC3339", "Return the RFC3339 string representation of this date time, including its timezone offset.", returns = type("lyng.String"), moduleName = "lyng.time",
                code = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().toRFC3339().toObj() })
            addFnDoc("toSortableString", "Alias to toRFC3339.", returns = type("lyng.String"), moduleName = "lyng.time",
                code = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().toRFC3339().toObj() })

            addFnDoc("toEpochMilliseconds", "Return the number of milliseconds since the Unix epoch (UTC).", returns = type("lyng.Int"), moduleName = "lyng.time",
                code = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDateTime>().instant.toEpochMilliseconds().toObj() })
            addFnDoc("toTimeZone", "Return a new DateTime representing the same instant but in a different time zone. " +
                    "Accepts a timezone ID string (e.g., 'UTC', '+02:00') or an integer offset in seconds.",
                params = listOf(net.sergeych.lyng.miniast.ParamDoc("tz", type = type("lyng.Any"))),
                returns = type("lyng.DateTime"), moduleName = "lyng.time",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val tz = when (val a = scp.args.list.getOrNull(0)) {
                            is ObjString -> TimeZone.of(a.value)
                            is ObjInt -> UtcOffset(seconds = a.value.toInt()).asTimeZone()
                            else -> scp.raiseIllegalArgument("invalid timezone: $a")
                        }
                        return ObjDateTime(scp.thisAs<ObjDateTime>().instant, tz)
                    }
                })
            addFnDoc("toUTC", "Shortcut to convert this date time to the UTC time zone.", returns = type("lyng.DateTime"), moduleName = "lyng.time",
                code = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjDateTime(scp.thisAs<ObjDateTime>().instant, TimeZone.UTC) })

            addFnDoc("addMonths", "Return a new DateTime with the specified number of months added (or subtracted if negative). " +
                    "Normalizes the day of month if necessary (e.g., Jan 31 + 1 month = Feb 28/29).",
                params = listOf(net.sergeych.lyng.miniast.ParamDoc("months", type = type("lyng.Int"))),
                returns = type("lyng.DateTime"), moduleName = "lyng.time",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val n = scp.args.list.getOrNull(0)?.toInt() ?: 0
                        val res = scp.thisAs<ObjDateTime>().instant.plus(n, DateTimeUnit.MONTH, scp.thisAs<ObjDateTime>().timeZone)
                        return ObjDateTime(res, scp.thisAs<ObjDateTime>().timeZone)
                    }
                })
            addFnDoc("addYears", "Return a new DateTime with the specified number of years added (or subtracted if negative).",
                params = listOf(net.sergeych.lyng.miniast.ParamDoc("years", type = type("lyng.Int"))),
                returns = type("lyng.DateTime"), moduleName = "lyng.time",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val n = scp.args.list.getOrNull(0)?.toInt() ?: 0
                        val res = scp.thisAs<ObjDateTime>().instant.plus(n, DateTimeUnit.YEAR, scp.thisAs<ObjDateTime>().timeZone)
                        return ObjDateTime(res, scp.thisAs<ObjDateTime>().timeZone)
                    }
                })

            addClassFn("now", code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val tz = when (val a = scp.args.list.getOrNull(0)) {
                        null -> TimeZone.currentSystemDefault()
                        is ObjString -> TimeZone.of(a.value)
                        is ObjInt -> UtcOffset(seconds = a.value.toInt()).asTimeZone()
                        else -> scp.raiseIllegalArgument("invalid timezone: $a")
                    }
                    return ObjDateTime(kotlin.time.Clock.System.now(), tz)
                }
            })

            addClassFnDoc("parseRFC3339",
                "Parse an RFC3339 string into a DateTime object. " +
                        "Note: if the string does not specify a timezone, UTC is assumed.",
                params = listOf(net.sergeych.lyng.miniast.ParamDoc("string", type = type("lyng.String"))),
                returns = type("lyng.DateTime"),
                moduleName = "lyng.time",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val s = (scp.args.firstAndOnly() as ObjString).value
                        // kotlinx-datetime's Instant.parse handles RFC3339
                        // But we want to preserve the offset if present for DateTime.
                        // However, Instant.parse("...") always gives an Instant.
                        // If we want the specific offset from the string, we might need a more complex parse.
                        // For now, let's stick to parsing it as Instant and converting to UTC or specified TZ.
                        // Actually, if the string has an offset, Instant.parse handles it but returns UTC instant.

                        // Let's try to detect if there is an offset in the string.
                        // If not, use UTC.
                        val instant = Instant.parse(s)

                        // RFC3339 can have Z or +/-HH:mm or +/-HHmm or +/-HH
                        val tz = try {
                            if (s.endsWith("Z", ignoreCase = true)) {
                                TimeZone.of("Z")
                            } else {
                                // Look for the last + or - which is likely the start of the offset
                                val lastPlus = s.lastIndexOf('+')
                                val lastMinus = s.lastIndexOf('-')
                                val offsetStart = if (lastPlus > lastMinus) lastPlus else lastMinus
                                if (offsetStart > s.lastIndexOf('T')) {
                                    // Likely an offset
                                    val offsetStr = s.substring(offsetStart)
                                    TimeZone.of(offsetStr)
                                } else {
                                    TimeZone.UTC
                                }
                            }
                        } catch (e: Exception) {
                            TimeZone.UTC
                        }
                        return ObjDateTime(instant, tz)
                    }
                })
        }
    }
}
