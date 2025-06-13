package net.sergeych.lyng

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

    override suspend fun getAt(context: Context, index: Int): Obj {
        return ObjChar(value[index])
    }

    override suspend fun contains(context: Context, other: Obj): Boolean {
        return if (other is ObjString)
            value.contains(other.value)
        else if (other is ObjChar)
            value.contains(other.value)
        else context.raiseArgumentError("String.contains can't take $other")
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
            addFn("takeLast") {
                thisAs<ObjString>().value.takeLast(
                    requiredArg<ObjInt>(0).toInt()
                ).let(::ObjString)
            }
            addFn("take") {
                thisAs<ObjString>().value.take(
                    requiredArg<ObjInt>(0).toInt()
                ).let(::ObjString)
            }
            addFn("drop") {
                thisAs<ObjString>().value.drop(
                    requiredArg<ObjInt>(0).toInt()
                ).let(::ObjString)
            }
            addFn("dropLast") {
                thisAs<ObjString>().value.dropLast(
                    requiredArg<ObjInt>(0).toInt()
                ).let(::ObjString)
            }
            addFn("lower") {
                thisAs<ObjString>().value.lowercase().let(::ObjString)
            }
            addFn("upper") {
                thisAs<ObjString>().value.uppercase().let(::ObjString)
            }
            addFn("size") { ObjInt(thisAs<ObjString>().value.length.toLong()) }
        }
    }
}