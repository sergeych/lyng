package net.sergeych.lyng

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.isDistantFuture
import kotlinx.datetime.isDistantPast

class ObjInstant(val instant: Instant) : Obj() {
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
                            Instant.fromEpochSeconds(t.epochSeconds, (t.nanosecondsOfSecond / 1_000_000).toLong()*1_000_000)
                        }
                        is ObjInt -> Instant.fromEpochSeconds(a0.value)
                        is ObjReal -> {
                            val seconds = a0.value.toLong()
                            val nanos = (a0.value - seconds) * 1e9
                            Instant.fromEpochSeconds(seconds, nanos.toLong())
                        }
                        is ObjInstant -> a0.instant

                        else -> {
                            scope.raiseIllegalArgument("can't construct Instant(${args.inspect()})")
                        }
                    }
                )
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
            addClassConst("distantFuture", distantFuture)
            addClassConst("distantPast", distantPast)
//            addFn("epochMilliseconds") {
//                ObjInt(instant.toEpochMilliseconds())
//            }
        }

    }
}


