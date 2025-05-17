package net.sergeych.ling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.floor

@Serializable
sealed class Obj {
    open val asStr: ObjString by lazy {
        if( this is ObjString) this else ObjString(this.toString())
    }
}

@Serializable
@SerialName("void")
object Void: Obj() {
    override fun equals(other: Any?): Boolean {
        return other is Void || other is Unit
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
