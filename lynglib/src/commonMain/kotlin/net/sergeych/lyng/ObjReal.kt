package net.sergeych.lyng

import kotlin.math.floor
import kotlin.math.roundToLong

data class ObjReal(val value: Double) : Obj(), Numeric {
    override val asStr by lazy { ObjString(value.toString()) }
    override val longValue: Long by lazy { floor(value).toLong() }
    override val doubleValue: Double by lazy { value }
    override val toObjInt: ObjInt by lazy { ObjInt(longValue) }
    override val toObjReal: ObjReal by lazy { ObjReal(value) }

    override val objClass: ObjClass = type

    override fun byValueCopy(): Obj = ObjReal(value)

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is Numeric) return -2
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String = value.toString()

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override suspend fun plus(context: Context, other: Obj): Obj =
        ObjReal(this.value + other.toDouble())

    override suspend fun minus(context: Context, other: Obj): Obj =
        ObjReal(this.value - other.toDouble())

    override suspend fun mul(context: Context, other: Obj): Obj =
        ObjReal(this.value * other.toDouble())

    override suspend fun div(context: Context, other: Obj): Obj =
        ObjReal(this.value / other.toDouble())

    override suspend fun mod(context: Context, other: Obj): Obj =
        ObjReal(this.value % other.toDouble())

    /**
     * Returns unboxed Double value
     */
    override suspend fun toKotlin(context: Context): Any {
        return value
    }

    companion object {
        val type: ObjClass = ObjClass("Real").apply {
            createField(
                "roundToInt",
                statement(Pos.builtIn) {
                    (it.thisObj as ObjReal).value.roundToLong().toObj()
                },
            )
        }
    }
}