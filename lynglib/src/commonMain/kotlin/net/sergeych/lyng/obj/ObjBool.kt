package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

data class ObjBool(val value: Boolean) : Obj() {
    override val asStr by lazy { ObjString(value.toString()) }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is ObjBool) return -2
        return value.compareTo(other.value)
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun toString(): String = value.toString()

    override val objClass: ObjClass = type

    override suspend fun logicalNot(scope: Scope): Obj = ObjBool(!value)

    override suspend fun logicalAnd(scope: Scope, other: Obj): Obj = ObjBool(value && other.toBool())

    override suspend fun logicalOr(scope: Scope, other: Obj): Obj = ObjBool(value || other.toBool())

    override suspend fun toKotlin(scope: Scope): Any {
        return value
    }

    override suspend fun lynonType(): LynonType = LynonType.Bool

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeBoolean(value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjBool

        return value == other.value
    }

    companion object {
        val type = object : ObjClass("Bool") {
            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder,lynonType: LynonType?): Obj {
                return ObjBool(decoder.unpackBoolean())
            }
        }
    }
}

val ObjTrue = ObjBool(true)
val ObjFalse = ObjBool(false)