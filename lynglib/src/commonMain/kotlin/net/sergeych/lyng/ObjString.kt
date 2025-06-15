package net.sergeych.lyng

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.sergeych.sprintf.sprintf

@Serializable
@SerialName("string")
data class ObjString(val value: String) : Obj() {

//    fun normalize(context: Context, index: Int, allowsEndInclusive: Boolean = false): Int {
//        val i = if (index < 0) value.length + index else index
//        if (allowsEndInclusive && i == value.length) return i
//        if (i !in value.indices) context.raiseError("index $index out of bounds for length ${value.length} of \"$value\"")
//        return i
//    }


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

    override suspend fun getAt(context: Context, index: Obj): Obj {
        if( index is ObjInt ) return ObjChar(value[index.toInt()])
        if( index is ObjRange ) {
            val start = if(index.start == null || index.start.isNull) 0 else  index.start.toInt()
            val end = if( index.end  == null || index.end.isNull ) value.length else  {
                val e = index.end.toInt()
                if( index.isEndInclusive) e + 1 else e
            }
            return ObjString(value.substring(start, end))
        }
        context.raiseIllegalArgument("String index must be Int or Range")
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override suspend fun callOn(context: Context): Obj {
        return ObjString(this.value.sprintf(*context.args.toKotlinList(context).toTypedArray()))
    }

    override suspend fun contains(context: Context, other: Obj): Boolean {
        return if (other is ObjString)
            value.contains(other.value)
        else if (other is ObjChar)
            value.contains(other.value)
        else context.raiseIllegalArgument("String.contains can't take $other")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjString

        return value == other.value
    }

    companion object {
        val type = ObjClass("String").apply {
            addFn("startsWith") {
                ObjBool(thisAs<ObjString>().value.startsWith(requiredArg<ObjString>(0).value))
            }
            addFn("endsWith") {
                ObjBool(thisAs<ObjString>().value.endsWith(requiredArg<ObjString>(0).value))
            }
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