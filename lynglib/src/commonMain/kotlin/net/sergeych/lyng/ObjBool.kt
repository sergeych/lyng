package net.sergeych.lyng

data class ObjBool(val value: Boolean) : Obj() {
    override val asStr by lazy { ObjString(value.toString()) }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is ObjBool) return -2
        return value.compareTo(other.value)
    }

    override fun toString(): String = value.toString()

    override val objClass: ObjClass = type

    override suspend fun logicalNot(scope: Scope): Obj = ObjBool(!value)

    override suspend fun logicalAnd(scope: Scope, other: Obj): Obj = ObjBool(value && other.toBool())

    override suspend fun logicalOr(scope: Scope, other: Obj): Obj = ObjBool(value || other.toBool())

    override suspend fun toKotlin(scope: Scope): Any {
        return value
    }

    companion object {
        val type = ObjClass("Bool")
    }
}

val ObjTrue = ObjBool(true)
val ObjFalse = ObjBool(false)