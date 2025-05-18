package net.sergeych.ling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.floor

@Serializable
sealed class Obj {
    open val asStr: ObjString by lazy {
        if( this is ObjString) this else ObjString(this.toString())
    }

    companion object {
        inline fun <reified T> from(obj: T): Obj {
            return when(obj) {
                is Obj -> obj
                is Double -> ObjReal(obj)
                is Float -> ObjReal(obj.toDouble())
                is Int -> ObjInt(obj.toLong())
                is Long -> ObjInt(obj)
                is String -> ObjString(obj)
                is CharSequence -> ObjString(obj.toString())
                is Boolean -> ObjBool(obj)
                Unit -> ObjVoid
                null -> ObjNull
                else -> throw IllegalArgumentException("cannot convert to Obj: $obj")
            }
        }
    }
}

@Serializable
@SerialName("void")
object ObjVoid: Obj() {
    override fun equals(other: Any?): Boolean {
        return other is ObjVoid || other is Unit
    }
}

@Serializable
@SerialName("null")
object ObjNull: Obj() {
    override fun equals(other: Any?): Boolean {
        return other is ObjNull || other == null
    }
}

@Serializable
@SerialName("string")
data class ObjString(val value: String): Obj() {
    override fun toString(): String = value
}

interface Numeric {
    val longValue: Long
    val doubleValue: Double
    val toObjInt: ObjInt
    val toObjReal: ObjReal
}

fun Obj.toDouble(): Double =
    (this as? Numeric)?.doubleValue
        ?: (this as? ObjString)?.value?.toDouble()
        ?: throw IllegalArgumentException("cannot convert to double $this")

@Suppress("unused")
fun Obj.toLong(): Long =
    (this as? Numeric)?.longValue
        ?: (this as? ObjString)?.value?.toLong()
        ?: throw IllegalArgumentException("cannot convert to double $this")



@Serializable
@SerialName("real")
data class ObjReal(val value: Double): Obj(), Numeric {
    override val asStr by lazy { ObjString(value.toString()) }
    override val longValue: Long by lazy { floor(value).toLong() }
    override val doubleValue: Double by lazy { value }
    override val toObjInt: ObjInt by lazy { ObjInt(longValue) }
    override val toObjReal: ObjReal by lazy { ObjReal(value) }
}

@Serializable
@SerialName("int")
data class ObjInt(val value: Long): Obj(), Numeric {
    override val asStr by lazy { ObjString(value.toString()) }
    override val longValue: Long by lazy { value }
    override val doubleValue: Double by lazy { value.toDouble() }
    override val toObjInt: ObjInt by lazy { ObjInt(value) }
    override val toObjReal: ObjReal by lazy { ObjReal(doubleValue) }
}

@Serializable
@SerialName("bool")
data class ObjBool(val value: Boolean): Obj() {
    override val asStr by lazy { ObjString(value.toString()) }
}

data class ObjNamespace(val name: String,val context: Context): Obj() {
    override fun toString(): String {
        return "namespace ${name}"
    }
}