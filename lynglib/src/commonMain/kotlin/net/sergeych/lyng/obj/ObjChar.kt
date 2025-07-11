package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope

class ObjChar(val value: Char): Obj() {

    override val objClass: ObjClass = type

    override suspend fun compareTo(scope: Scope, other: Obj): Int =
        (other as? ObjChar)?.let { value.compareTo(it.value) } ?: -1

    override fun toString(): String = value.toString()

    override fun inspect(): String = "'$value'"

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjChar

        return value == other.value
    }

    companion object {
        val type = ObjClass("Char").apply {
            addFn("code") { ObjInt(thisAs<ObjChar>().value.code.toLong()) }
        }
    }
}