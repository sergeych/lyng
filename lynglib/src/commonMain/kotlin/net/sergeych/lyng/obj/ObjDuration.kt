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
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.type
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class ObjDuration(val duration: Duration) : Obj() {
    override val objClass: ObjClass = type

    override fun toString(): String {
        return duration.toString()
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        return if( other is ObjDuration)
            duration.compareTo(other.duration)
        else -1
    }

    override fun hashCode(): Int {
        return duration.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjDuration

        return duration == other.duration
    }

    companion object {
        val type = object : ObjClass("Duration") {
            override suspend fun callOn(scope: Scope): Obj {
                val args = scope.args
                if( args.list.size > 1 )
                    scope.raiseIllegalArgument("can't construct Duration(${args.inspect(scope)})")
                val a0 = args.list.getOrNull(0)

                return ObjDuration(
                    when (a0) {
                        null -> Duration.ZERO
                        is ObjInt -> a0.value.seconds
                        is ObjReal -> a0.value.seconds
                        else -> {
                            scope.raiseIllegalArgument("can't construct Instant(${args.inspect(scope)})")
                        }
                    }
                )
            }
        }.apply {
            addFnDoc(
                name = "days",
                doc = "Return this duration as a real number of days.",
                returns = type("lyng.Real"),
                moduleName = "lyng.time"
            ) { thisAs<ObjDuration>().duration.toDouble(DurationUnit.DAYS).toObj() }
            addFnDoc(
                name = "hours",
                doc = "Return this duration as a real number of hours.",
                returns = type("lyng.Real"),
                moduleName = "lyng.time"
            ) { thisAs<ObjDuration>().duration.toDouble(DurationUnit.HOURS).toObj() }
            addFnDoc(
                name = "minutes",
                doc = "Return this duration as a real number of minutes.",
                returns = type("lyng.Real"),
                moduleName = "lyng.time"
            ) { thisAs<ObjDuration>().duration.toDouble(DurationUnit.MINUTES).toObj() }
            addFnDoc(
                name = "seconds",
                doc = "Return this duration as a real number of seconds.",
                returns = type("lyng.Real"),
                moduleName = "lyng.time"
            ) { thisAs<ObjDuration>().duration.toDouble(DurationUnit.SECONDS).toObj() }
            addFnDoc(
                name = "milliseconds",
                doc = "Return this duration as a real number of milliseconds.",
                returns = type("lyng.Real"),
                moduleName = "lyng.time"
            ) { thisAs<ObjDuration>().duration.toDouble(DurationUnit.MILLISECONDS).toObj() }
            addFnDoc(
                name = "microseconds",
                doc = "Return this duration as a real number of microseconds.",
                returns = type("lyng.Real"),
                moduleName = "lyng.time"
            ) { thisAs<ObjDuration>().duration.toDouble(DurationUnit.MICROSECONDS).toObj() }
            // extensions

            ObjInt.type.addFnDoc(
                name = "seconds",
                doc = "Construct a `Duration` equal to this integer number of seconds.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjInt>().value.seconds) }

            ObjInt.type.addFnDoc(
                name = "second",
                doc = "Construct a `Duration` equal to this integer number of seconds.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjInt>().value.seconds) }
            ObjInt.type.addFnDoc(
                name = "milliseconds",
                doc = "Construct a `Duration` equal to this integer number of milliseconds.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjInt>().value.milliseconds) }

            ObjInt.type.addFnDoc(
                name = "millisecond",
                doc = "Construct a `Duration` equal to this integer number of milliseconds.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjInt>().value.milliseconds) }
            ObjReal.type.addFnDoc(
                name = "seconds",
                doc = "Construct a `Duration` equal to this real number of seconds.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjReal>().value.seconds) }

            ObjReal.type.addFnDoc(
                name = "second",
                doc = "Construct a `Duration` equal to this real number of seconds.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjReal>().value.seconds) }

            ObjReal.type.addFnDoc(
                name = "milliseconds",
                doc = "Construct a `Duration` equal to this real number of milliseconds.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjReal>().value.milliseconds) }
            ObjReal.type.addFnDoc(
                name = "millisecond",
                doc = "Construct a `Duration` equal to this real number of milliseconds.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjReal>().value.milliseconds) }

            ObjInt.type.addFnDoc(
                name = "minutes",
                doc = "Construct a `Duration` equal to this integer number of minutes.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjInt>().value.minutes) }
            ObjReal.type.addFnDoc(
                name = "minutes",
                doc = "Construct a `Duration` equal to this real number of minutes.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjReal>().value.minutes) }
            ObjInt.type.addFnDoc(
                name = "minute",
                doc = "Construct a `Duration` equal to this integer number of minutes.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjInt>().value.minutes) }
            ObjReal.type.addFnDoc(
                name = "minute",
                doc = "Construct a `Duration` equal to this real number of minutes.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjReal>().value.minutes) }
            ObjInt.type.addFnDoc(
                name = "hours",
                doc = "Construct a `Duration` equal to this integer number of hours.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjInt>().value.hours) }
            ObjReal.type.addFnDoc(
                name = "hours",
                doc = "Construct a `Duration` equal to this real number of hours.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjReal>().value.hours) }
            ObjInt.type.addFnDoc(
                name = "hour",
                doc = "Construct a `Duration` equal to this integer number of hours.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjInt>().value.hours) }
            ObjReal.type.addFnDoc(
                name = "hour",
                doc = "Construct a `Duration` equal to this real number of hours.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjReal>().value.hours) }
            ObjInt.type.addFnDoc(
                name = "days",
                doc = "Construct a `Duration` equal to this integer number of days.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjInt>().value.days) }
            ObjReal.type.addFnDoc(
                name = "days",
                doc = "Construct a `Duration` equal to this real number of days.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjReal>().value.days) }
            ObjInt.type.addFnDoc(
                name = "day",
                doc = "Construct a `Duration` equal to this integer number of days.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjInt>().value.days) }
            ObjReal.type.addFnDoc(
                name = "day",
                doc = "Construct a `Duration` equal to this real number of days.",
                returns = type("lyng.Duration"),
                moduleName = "lyng.time"
            ) { ObjDuration(thisAs<ObjReal>().value.days) }


//            addFn("epochSeconds") {
//                val instant = thisAs<ObjInstant>().instant
//                ObjReal(instant.epochSeconds + instant.nanosecondsOfSecond * 1e-9)
//            }
//            addFn("epochMilliseconds") {
//                ObjInt(instant.toEpochMilliseconds())
//            }
        }
    }
}