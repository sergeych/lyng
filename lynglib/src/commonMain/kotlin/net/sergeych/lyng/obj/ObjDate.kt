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

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.asFacade
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.addClassFnDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class ObjDate(val date: LocalDate) : Obj() {
    override val objClass: ObjClass get() = type

    override fun toString(): String = date.toString()

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        return when (other) {
            is ObjDuration -> ObjDate(addDays(date, requireWholeDays(scope, other.duration)))
            else -> super.plus(scope, other)
        }
    }

    override suspend fun minus(scope: Scope, other: Obj): Obj {
        return when (other) {
            is ObjDuration -> ObjDate(addDays(date, -requireWholeDays(scope, other.duration)))
            is ObjDate -> ObjInt.of(daysBetween(other.date, date).toLong())
            else -> super.minus(scope, other)
        }
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        return if (other is ObjDate) {
            date.compareTo(other.date)
        } else super.compareTo(scope, other)
    }

    override suspend fun toKotlin(scope: Scope): Any = date

    override fun hashCode(): Int = date.hashCode()

    override fun equals(other: Any?): Boolean = other is ObjDate && date == other.date

    override suspend fun lynonType(): LynonType = LynonType.Date

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeSigned(date.year.toLong())
        encoder.encodeUnsigned(date.month.number.toULong())
        encoder.encodeUnsigned(date.day.toULong())
    }

    override suspend fun toJson(scope: Scope): JsonElement = JsonPrimitive(date.toString())

    companion object {
        val type = object : ObjClass("Date") {
            override suspend fun callOn(scope: Scope): Obj {
                val args = scope.args
                return when (val a0 = args.list.getOrNull(0)) {
                    null -> ObjDate(today(TimeZone.currentSystemDefault()))
                    is ObjDate -> a0
                    is ObjInt -> {
                        val year = a0.value.toInt()
                        val month = args.list.getOrNull(1)?.toInt() ?: scope.raiseIllegalArgument("month is required")
                        val day = args.list.getOrNull(2)?.toInt() ?: scope.raiseIllegalArgument("day is required")
                        ObjDate(createDate(scope, year, month, day))
                    }
                    is ObjString -> ObjDate(parseIso(scope, a0.value))
                    is ObjDateTime -> ObjDate(toDate(a0.instant, a0.timeZone))
                    is ObjInstant -> {
                        val tz = parseTimeZoneArg(scope, args.list.getOrNull(1), TimeZone.currentSystemDefault())
                        ObjDate(toDate(a0.instant, tz))
                    }
                    else -> scope.raiseIllegalArgument("can't construct Date from ${args.inspect(scope)}")
                }
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                val year = decoder.unpackSigned().toInt()
                val month = decoder.unpackUnsigned().toInt()
                val day = decoder.unpackUnsigned().toInt()
                return ObjDate(createDate(scope, year, month, day))
            }
        }.apply {
            addPropertyDoc("year", "The year component.", type("lyng.Int"), moduleName = "lyng.time",
                getter = { thisAs<ObjDate>().date.year.toObj() })
            addPropertyDoc("month", "The month component (1..12).", type("lyng.Int"), moduleName = "lyng.time",
                getter = { thisAs<ObjDate>().date.month.number.toObj() })
            addPropertyDoc("dayOfMonth", "The day of month component.", type("lyng.Int"), moduleName = "lyng.time",
                getter = { thisAs<ObjDate>().date.day.toObj() })
            addPropertyDoc("day", "Alias to dayOfMonth.", type("lyng.Int"), moduleName = "lyng.time",
                getter = { thisAs<ObjDate>().date.day.toObj() })
            addPropertyDoc("dayOfWeek", "The day of week (1=Monday, 7=Sunday).", type("lyng.Int"), moduleName = "lyng.time",
                getter = { thisAs<ObjDate>().date.dayOfWeek.isoDayNumber.toObj() })
            addPropertyDoc("dayOfYear", "The day of year (1..365/366).", type("lyng.Int"), moduleName = "lyng.time",
                getter = { thisAs<ObjDate>().date.dayOfYear.toObj() })
            addPropertyDoc("isLeapYear", "Whether this date is in a leap year.", type("lyng.Bool"), moduleName = "lyng.time",
                getter = { isLeapYear(thisAs<ObjDate>().date.year).toObj() })
            addPropertyDoc("lengthOfMonth", "Number of days in this date's month.", type("lyng.Int"), moduleName = "lyng.time",
                getter = { monthLength(thisAs<ObjDate>().date.year, thisAs<ObjDate>().date.month.number).toObj() })
            addPropertyDoc("lengthOfYear", "Number of days in this date's year.", type("lyng.Int"), moduleName = "lyng.time",
                getter = { (if (isLeapYear(thisAs<ObjDate>().date.year)) 366 else 365).toObj() })

            addFnDoc("toIsoString", "Return the canonical ISO date string representation (`YYYY-MM-DD`).",
                returns = type("lyng.String"), moduleName = "lyng.time") {
                thisAs<ObjDate>().date.toString().toObj()
            }
            addFnDoc("toSortableString", "Alias to toIsoString.", returns = type("lyng.String"), moduleName = "lyng.time") {
                thisAs<ObjDate>().date.toString().toObj()
            }
            addFnDoc("toDateTime", "Convert this date to a DateTime at the start of day in the specified timezone.",
                params = listOf(ParamDoc("tz", type = type("lyng.Any", true))),
                returns = type("lyng.DateTime"), moduleName = "lyng.time") {
                val tz = parseTimeZoneArg(this, args.list.getOrNull(0), TimeZone.UTC)
                toDateTime(thisAs<ObjDate>().date, tz)
            }
            addFnDoc("atStartOfDay", "Alias to toDateTime.",
                params = listOf(ParamDoc("tz", type = type("lyng.Any", true))),
                returns = type("lyng.DateTime"), moduleName = "lyng.time") {
                val tz = parseTimeZoneArg(this, args.list.getOrNull(0), TimeZone.UTC)
                toDateTime(thisAs<ObjDate>().date, tz)
            }
            addFnDoc("addDays", "Return a new Date with the specified number of days added (or subtracted if negative).",
                params = listOf(ParamDoc("days", type = type("lyng.Int"))),
                returns = type("lyng.Date"), moduleName = "lyng.time") {
                val n = args.list.getOrNull(0)?.toInt() ?: 0
                ObjDate(addDays(thisAs<ObjDate>().date, n))
            }
            addFnDoc("addMonths", "Return a new Date with the specified number of months added (or subtracted if negative). End-of-month values are normalized.",
                params = listOf(ParamDoc("months", type = type("lyng.Int"))),
                returns = type("lyng.Date"), moduleName = "lyng.time") {
                val n = args.list.getOrNull(0)?.toInt() ?: 0
                ObjDate(addMonths(thisAs<ObjDate>().date, n))
            }
            addFnDoc("addYears", "Return a new Date with the specified number of years added (or subtracted if negative).",
                params = listOf(ParamDoc("years", type = type("lyng.Int"))),
                returns = type("lyng.Date"), moduleName = "lyng.time") {
                val n = args.list.getOrNull(0)?.toInt() ?: 0
                ObjDate(addYears(thisAs<ObjDate>().date, n))
            }
            addFnDoc("daysUntil", "Return the number of whole calendar days until the other date.",
                params = listOf(ParamDoc("other", type = type("lyng.Date"))),
                returns = type("lyng.Int"), moduleName = "lyng.time") {
                val other = requiredArg<ObjDate>(0)
                daysBetween(thisAs<ObjDate>().date, other.date).toObj()
            }
            addFnDoc("daysSince", "Return the number of whole calendar days since the other date.",
                params = listOf(ParamDoc("other", type = type("lyng.Date"))),
                returns = type("lyng.Int"), moduleName = "lyng.time") {
                val other = requiredArg<ObjDate>(0)
                daysBetween(other.date, thisAs<ObjDate>().date).toObj()
            }

            addClassFnDoc("today", "Return today's date in the specified timezone, or in the current system timezone if omitted.",
                params = listOf(ParamDoc("tz", type = type("lyng.Any", true))),
                returns = type("lyng.Date"), moduleName = "lyng.time") {
                val tz = parseTimeZoneArg(this, args.list.getOrNull(0), TimeZone.currentSystemDefault())
                ObjDate(today(tz))
            }
            addClassFnDoc("parseIso", "Parse an ISO date string (`YYYY-MM-DD`) into a Date.",
                params = listOf(ParamDoc("string", type = type("lyng.String"))),
                returns = type("lyng.Date"), moduleName = "lyng.time") {
                ObjDate(parseIso(this, requiredArg<ObjString>(0).value))
            }
        }
    }
}

internal fun parseTimeZoneArg(scope: ScopeFacade, value: Obj?, default: TimeZone): TimeZone {
    return when (value) {
        null -> default
        is ObjString -> TimeZone.of(value.value)
        is ObjInt -> UtcOffset(seconds = value.value.toInt()).asTimeZone()
        else -> scope.raiseIllegalArgument("invalid timezone: $value")
    }
}

internal fun parseTimeZoneArg(scope: Scope, value: Obj?, default: TimeZone): TimeZone =
    parseTimeZoneArg(scope.asFacade(), value, default)

internal fun toDate(instant: kotlin.time.Instant, tz: TimeZone): LocalDate {
    val ldt = instant.toLocalDateTime(tz)
    return LocalDate(ldt.year, ldt.month.number, ldt.day)
}

internal fun toDateTime(date: LocalDate, tz: TimeZone): ObjDateTime {
    val ldt = LocalDateTime(date.year, date.month.number, date.day, 0, 0, 0)
    return ObjDateTime(ldt.toInstant(tz), tz)
}

private fun parseIso(scope: ScopeFacade, value: String): LocalDate {
    val match = DATE_REGEX.matchEntire(value.trim()) ?: scope.raiseIllegalArgument("invalid ISO date string: $value")
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    return createDate(scope, year, month, day)
}

private fun parseIso(scope: Scope, value: String): LocalDate = parseIso(scope.asFacade(), value)

private fun createDate(scope: ScopeFacade, year: Int, month: Int, day: Int): LocalDate {
    if (month !in 1..12) scope.raiseIllegalArgument("month must be in 1..12")
    val maxDay = monthLength(year, month)
    if (day !in 1..maxDay) scope.raiseIllegalArgument("day must be in 1..$maxDay")
    return LocalDate(year, month, day)
}

private fun createDate(scope: Scope, year: Int, month: Int, day: Int): LocalDate =
    createDate(scope.asFacade(), year, month, day)

private fun today(tz: TimeZone): LocalDate = toDate(Clock.System.now(), tz)

private fun addDays(date: LocalDate, days: Int): LocalDate {
    val start = LocalDateTime(date.year, date.month.number, date.day, 0, 0, 0).toInstant(TimeZone.UTC)
    return toDate(start + days.days, TimeZone.UTC)
}

private fun daysBetween(start: LocalDate, end: LocalDate): Int {
    val startInstant = LocalDateTime(start.year, start.month.number, start.day, 0, 0, 0).toInstant(TimeZone.UTC)
    val endInstant = LocalDateTime(end.year, end.month.number, end.day, 0, 0, 0).toInstant(TimeZone.UTC)
    return ((endInstant.epochSeconds - startInstant.epochSeconds) / 86_400L).toInt()
}

private fun addMonths(date: LocalDate, months: Int): LocalDate {
    if (months == 0) return date
    val totalMonths = date.year.toLong() * 12L + (date.month.number - 1).toLong() + months.toLong()
    val newYear = floorDiv(totalMonths, 12L).toInt()
    val newMonth = floorMod(totalMonths, 12L).toInt() + 1
    val newDay = minOf(date.day, monthLength(newYear, newMonth))
    return LocalDate(newYear, newMonth, newDay)
}

private fun addYears(date: LocalDate, years: Int): LocalDate {
    if (years == 0) return date
    val newYear = date.year + years
    val newDay = minOf(date.day, monthLength(newYear, date.month.number))
    return LocalDate(newYear, date.month.number, newDay)
}

private fun requireWholeDays(scope: Scope, duration: Duration): Int {
    val days = duration.inWholeDays
    if (days.absoluteValue > Int.MAX_VALUE.toLong()) {
        scope.raiseIllegalArgument("date arithmetic day count is too large")
    }
    if (duration != days.days) {
        scope.raiseIllegalArgument("Date arithmetic supports only whole-day durations")
    }
    return days.toInt()
}

private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

private fun monthLength(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(year)) 29 else 28
    else -> error("invalid month: $month")
}

private fun floorDiv(a: Long, b: Long): Long {
    var q = a / b
    if ((a xor b) < 0 && q * b != a) q -= 1
    return q
}

private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

private val DATE_REGEX = Regex("""([+-]?\d{4,})-(\d{2})-(\d{2})""")
