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

import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeCallable
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class ObjDuration(val duration: Duration) : Obj() {
    override val objClass: ObjClass get() = type

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
            addPropertyDoc(
                name = "days",
                doc = "Return this duration as a real number of days.",
                type = type("lyng.Real"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDuration>().duration.toDouble(DurationUnit.DAYS).toObj()
                }
            )
            addPropertyDoc(
                name = "hours",
                doc = "Return this duration as a real number of hours.",
                type = type("lyng.Real"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDuration>().duration.toDouble(DurationUnit.HOURS).toObj()
                }
            )
            addPropertyDoc(
                name = "minutes",
                doc = "Return this duration as a real number of minutes.",
                type = type("lyng.Real"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDuration>().duration.toDouble(DurationUnit.MINUTES).toObj()
                }
            )
            addPropertyDoc(
                name = "seconds",
                doc = "Return this duration as a real number of seconds.",
                type = type("lyng.Real"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDuration>().duration.toDouble(DurationUnit.SECONDS).toObj()
                }
            )
            addPropertyDoc(
                name = "milliseconds",
                doc = "Return this duration as a real number of milliseconds.",
                type = type("lyng.Real"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDuration>().duration.toDouble(DurationUnit.MILLISECONDS).toObj()
                }
            )
            addPropertyDoc(
                name = "microseconds",
                doc = "Return this duration as a real number of microseconds.",
                type = type("lyng.Real"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjDuration>().duration.toDouble(DurationUnit.MICROSECONDS).toObj()
                }
            )
            // extensions

            ObjInt.type.addPropertyDoc(
                name = "seconds",
                doc = "Construct a `Duration` equal to this integer number of seconds.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjInt>().value.seconds)
                }
            )

            ObjInt.type.addPropertyDoc(
                name = "second",
                doc = "Construct a `Duration` equal to this integer number of seconds.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjInt>().value.seconds)
                }
            )
            ObjInt.type.addPropertyDoc(
                name = "milliseconds",
                doc = "Construct a `Duration` equal to this integer number of milliseconds.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjInt>().value.milliseconds)
                }
            )

            ObjInt.type.addPropertyDoc(
                name = "millisecond",
                doc = "Construct a `Duration` equal to this integer number of milliseconds.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjInt>().value.milliseconds)
                }
            )
            ObjReal.type.addPropertyDoc(
                name = "seconds",
                doc = "Construct a `Duration` equal to this real number of seconds.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjReal>().value.seconds)
                }
            )

            ObjReal.type.addPropertyDoc(
                name = "second",
                doc = "Construct a `Duration` equal to this real number of seconds.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjReal>().value.seconds)
                }
            )

            ObjReal.type.addPropertyDoc(
                name = "milliseconds",
                doc = "Construct a `Duration` equal to this real number of milliseconds.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjReal>().value.milliseconds)
                }
            )
            ObjReal.type.addPropertyDoc(
                name = "millisecond",
                doc = "Construct a `Duration` equal to this real number of milliseconds.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjReal>().value.milliseconds)
                }
            )

            ObjInt.type.addPropertyDoc(
                name = "minutes",
                doc = "Construct a `Duration` equal to this integer number of minutes.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjInt>().value.minutes)
                }
            )
            ObjReal.type.addPropertyDoc(
                name = "minutes",
                doc = "Construct a `Duration` equal to this real number of minutes.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjReal>().value.minutes)
                }
            )
            ObjInt.type.addPropertyDoc(
                name = "minute",
                doc = "Construct a `Duration` equal to this integer number of minutes.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjInt>().value.minutes)
                }
            )
            ObjReal.type.addPropertyDoc(
                name = "minute",
                doc = "Construct a `Duration` equal to this real number of minutes.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjReal>().value.minutes)
                }
            )
            ObjInt.type.addPropertyDoc(
                name = "hours",
                doc = "Construct a `Duration` equal to this integer number of hours.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjInt>().value.hours)
                }
            )
            ObjReal.type.addPropertyDoc(
                name = "hours",
                doc = "Construct a `Duration` equal to this real number of hours.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjReal>().value.hours)
                }
            )
            ObjInt.type.addPropertyDoc(
                name = "hour",
                doc = "Construct a `Duration` equal to this integer number of hours.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjInt>().value.hours)
                }
            )
            ObjReal.type.addPropertyDoc(
                name = "hour",
                doc = "Construct a `Duration` equal to this real number of hours.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjReal>().value.hours)
                }
            )
            ObjInt.type.addPropertyDoc(
                name = "days",
                doc = "Construct a `Duration` equal to this integer number of days.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjInt>().value.days)
                }
            )
            ObjReal.type.addPropertyDoc(
                name = "days",
                doc = "Construct a `Duration` equal to this real number of days.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjReal>().value.days)
                }
            )
            ObjInt.type.addPropertyDoc(
                name = "day",
                doc = "Construct a `Duration` equal to this integer number of days.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjInt>().value.days)
                }
            )
            ObjReal.type.addPropertyDoc(
                name = "day",
                doc = "Construct a `Duration` equal to this real number of days.",
                type = type("lyng.Duration"),
                moduleName = "lyng.time",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = ObjDuration(scp.thisAs<ObjReal>().value.days)
                }
            )


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