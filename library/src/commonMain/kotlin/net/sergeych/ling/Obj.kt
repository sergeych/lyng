package net.sergeych.ling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.floor

@Serializable
sealed class Obj : Comparable<Obj> {
    open val asStr: ObjString by lazy {
        if (this is ObjString) this else ObjString(this.toString())
    }

    open val type: Type = Type.Any

    @Suppress("unused")
    enum class Type {
        @SerialName("Void")
        Void,
        @SerialName("Null")
        Null,
        @SerialName("String")
        String,
        @SerialName("Int")
        Int,
        @SerialName("Real")
        Real,
        @SerialName("Bool")
        Bool,
        @SerialName("Fn")
        Fn,
        @SerialName("Any")
        Any,
    }

    companion object {
        inline fun <reified T> from(obj: T): Obj {
            return when (obj) {
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

@Suppress("unused")
inline fun <reified T> T.toObj(): Obj = Obj.from(this)

@Serializable
@SerialName("void")
object ObjVoid : Obj() {
    override fun equals(other: Any?): Boolean {
        return other is ObjVoid || other is Unit
    }

    override fun compareTo(other: Obj): Int {
        return if (other === this) 0 else -1
    }

    override fun toString(): String = "void"
}

@Serializable
@SerialName("null")
object ObjNull : Obj() {
    override fun compareTo(other: Obj): Int {
        return if (other === this) 0 else -1
    }

    override fun equals(other: Any?): Boolean {
        return other is ObjNull || other == null
    }
}

@Serializable
@SerialName("string")
data class ObjString(val value: String) : Obj() {

    override fun compareTo(other: Obj): Int {
        if (other !is ObjString) throw IllegalArgumentException("cannot compare string with $other")
        return this.value.compareTo(other.value)
    }

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

fun Obj.toInt(): Int = toLong().toInt()

fun Obj.toBool(): Boolean = (this as? ObjBool)?.value ?: throw IllegalArgumentException("cannot convert to boolean ${this.type}:$this")


@Serializable
@SerialName("real")
data class ObjReal(val value: Double) : Obj(), Numeric {
    override val asStr by lazy { ObjString(value.toString()) }
    override val longValue: Long by lazy { floor(value).toLong() }
    override val doubleValue: Double by lazy { value }
    override val toObjInt: ObjInt by lazy { ObjInt(longValue) }
    override val toObjReal: ObjReal by lazy { ObjReal(value) }

    override fun compareTo(other: Obj): Int {
        if( other !is Numeric) throw IllegalArgumentException("cannot compare $this with $other")
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String = value.toString()
}

@Serializable
@SerialName("int")
data class ObjInt(val value: Long) : Obj(), Numeric {
    override val asStr by lazy { ObjString(value.toString()) }
    override val longValue: Long by lazy { value }
    override val doubleValue: Double by lazy { value.toDouble() }
    override val toObjInt: ObjInt by lazy { ObjInt(value) }
    override val toObjReal: ObjReal by lazy { ObjReal(doubleValue) }

    override fun compareTo(other: Obj): Int {
        if( other !is Numeric) throw IllegalArgumentException("cannot compare $this with $other")
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String = value.toString()
}

@Serializable
@SerialName("bool")
data class ObjBool(val value: Boolean) : Obj() {
    override val asStr by lazy { ObjString(value.toString()) }

    override fun compareTo(other: Obj): Int {
        if( other !is ObjBool) throw IllegalArgumentException("cannot compare $this with $other")
        return value.compareTo(other.value)
    }
    override fun toString(): String = value.toString()
}

data class ObjNamespace(val name: String, val context: Context) : Obj() {
    override fun toString(): String {
        return "namespace ${name}"
    }

    override fun compareTo(other: Obj): Int {
        throw IllegalArgumentException("cannot compare namespaces")
    }
}