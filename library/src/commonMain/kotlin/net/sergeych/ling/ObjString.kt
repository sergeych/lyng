package net.sergeych.ling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("string")
data class ObjString(val value: String) : Obj() {

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is ObjString) return -2
        return this.value.compareTo(other.value)
    }

    override fun toString(): String = value

    override val asStr: ObjString by lazy { this }

    override fun inspect(): String {
        return "\"$value\""
    }

    override val objClass: ObjClass
        get() = type

    override suspend fun plus(context: Context, other: Obj): Obj {
        return ObjString(value + other.asStr.value)
    }

    companion object {
        val type = ObjClass("String").apply {
            addConst("startsWith",
                statement {
                    ObjBool(thisAs<ObjString>().value.startsWith(requiredArg<ObjString>(0).value))
                })
            addConst("length",
                statement { ObjInt(thisAs<ObjString>().value.length.toLong()) }
            )
        }
    }
}