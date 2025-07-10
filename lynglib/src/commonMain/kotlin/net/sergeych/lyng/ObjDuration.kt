package net.sergeych.lyng

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

    companion object {
        val type = object : ObjClass("Duration") {
            override suspend fun callOn(scope: Scope): Obj {
                val args = scope.args
                if( args.list.size > 1 )
                    scope.raiseIllegalArgument("can't construct Duration(${args.inspect()})")
                val a0 = args.list.getOrNull(0)

                return ObjDuration(
                    when (a0) {
                        null -> Duration.ZERO
                        is ObjInt -> a0.value.seconds
                        is ObjReal -> a0.value.seconds
                        else -> {
                            scope.raiseIllegalArgument("can't construct Instant(${args.inspect()})")
                        }
                    }
                )
            }
        }.apply {
            addFn("days") {
                thisAs<ObjDuration>().duration.toDouble(DurationUnit.DAYS).toObj()
            }
            addFn("hours") {
                thisAs<ObjDuration>().duration.toDouble(DurationUnit.HOURS).toObj()
            }
            addFn("minutes") {
                thisAs<ObjDuration>().duration.toDouble(DurationUnit.MINUTES).toObj()
            }
            addFn("seconds") {
                thisAs<ObjDuration>().duration.toDouble(DurationUnit.SECONDS).toObj()
            }
            addFn("milliseconds") {
                thisAs<ObjDuration>().duration.toDouble(DurationUnit.MILLISECONDS).toObj()
            }
            addFn("microseconds") {
                thisAs<ObjDuration>().duration.toDouble(DurationUnit.MICROSECONDS).toObj()
            }
            // extensions

            ObjInt.type.addFn("seconds") {
                ObjDuration(thisAs<ObjInt>().value.seconds)
            }

            ObjInt.type.addFn("second") {
                ObjDuration(thisAs<ObjInt>().value.seconds)
            }
            ObjInt.type.addFn("milliseconds") {
                ObjDuration(thisAs<ObjInt>().value.milliseconds)
            }

            ObjInt.type.addFn("millisecond") {
                ObjDuration(thisAs<ObjInt>().value.milliseconds)
            }
            ObjReal.type.addFn("seconds") {
                ObjDuration(thisAs<ObjReal>().value.seconds)
            }

            ObjReal.type.addFn("second") {
                ObjDuration(thisAs<ObjReal>().value.seconds)
            }

            ObjReal.type.addFn("milliseconds") {
                ObjDuration(thisAs<ObjReal>().value.milliseconds)
            }
            ObjReal.type.addFn("millisecond") {
                ObjDuration(thisAs<ObjReal>().value.milliseconds)
            }

            ObjInt.type.addFn("minutes") {
                ObjDuration(thisAs<ObjInt>().value.minutes)
            }
            ObjReal.type.addFn("minutes") {
                ObjDuration(thisAs<ObjReal>().value.minutes)
            }
            ObjInt.type.addFn("minute") {
                ObjDuration(thisAs<ObjInt>().value.minutes)
            }
            ObjReal.type.addFn("minute") {
                ObjDuration(thisAs<ObjReal>().value.minutes)
            }
            ObjInt.type.addFn("hours") {
                ObjDuration(thisAs<ObjInt>().value.hours)
            }
            ObjReal.type.addFn("hours") {
                ObjDuration(thisAs<ObjReal>().value.hours)
            }
            ObjInt.type.addFn("hour") {
                ObjDuration(thisAs<ObjInt>().value.hours)
            }
            ObjReal.type.addFn("hour") {
                ObjDuration(thisAs<ObjReal>().value.hours)
            }
            ObjInt.type.addFn("days") {
                ObjDuration(thisAs<ObjInt>().value.days)
            }
            ObjReal.type.addFn("days") {
                ObjDuration(thisAs<ObjReal>().value.days)
            }
            ObjInt.type.addFn("day") {
                ObjDuration(thisAs<ObjInt>().value.days)
            }
            ObjReal.type.addFn("day") {
                ObjDuration(thisAs<ObjReal>().value.days)
            }


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