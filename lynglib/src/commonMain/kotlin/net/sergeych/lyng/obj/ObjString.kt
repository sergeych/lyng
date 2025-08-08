package net.sergeych.lyng.obj

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.sergeych.lyng.Scope
import net.sergeych.lyng.statement
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType
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

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
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

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        return ObjString(value + other.asStr.value)
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        if (index is ObjInt) return ObjChar(value[index.toInt()])
        if (index is ObjRange) {
            val start = if (index.start == null || index.start.isNull) 0 else index.start.toInt()
            val end = if (index.end == null || index.end.isNull) value.length else {
                val e = index.end.toInt()
                if (index.isEndInclusive) e + 1 else e
            }
            return ObjString(value.substring(start, end))
        }
        scope.raiseIllegalArgument("String index must be Int or Range")
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override suspend fun callOn(scope: Scope): Obj {
        return ObjString(this.value.sprintf(*scope.args
            .toKotlinList(scope)
            .map { if (it == null) "null" else it }
            .toTypedArray()))
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {
        return if (other is ObjString)
            value.contains(other.value)
        else if (other is ObjChar)
            value.contains(other.value)
        else scope.raiseIllegalArgument("String.contains can't take $other")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjString

        return value == other.value
    }

    override suspend fun lynonType(): LynonType = LynonType.String

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeBinaryData(value.encodeToByteArray())
    }

    companion object {
        val type = object : ObjClass("String") {
            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
                ObjString(decoder.unpackBinaryData().decodeToString())
        }.apply {
            addFn("toInt") {
                ObjInt(thisAs<ObjString>().value.toLongOrNull()
                    ?: raiseIllegalArgument("can't convert to int: $thisObj")
                )
            }
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
            addFn("characters") {
                ObjList(
                    thisAs<ObjString>().value.map { ObjChar(it) }.toMutableList()
                )
            }
            addFn("encodeUtf8") { ObjBuffer(thisAs<ObjString>().value.encodeToByteArray().asUByteArray()) }
            addFn("size") { ObjInt(thisAs<ObjString>().value.length.toLong()) }
            addFn("toReal") {
                ObjReal(thisAs<ObjString>().value.toDouble())
            }
        }
    }
}