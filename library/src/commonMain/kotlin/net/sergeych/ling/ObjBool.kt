package net.sergeych.lying

data class ObjBool(val value: Boolean) : Obj() {
    override val asStr by lazy { ObjString(value.toString()) }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is ObjBool) return -2
        return value.compareTo(other.value)
    }

    override fun toString(): String = value.toString()

    override val objClass: ObjClass = type

    override suspend fun logicalNot(context: Context): Obj = ObjBool(!value)

    override suspend fun logicalAnd(context: Context, other: Obj): Obj = ObjBool(value && other.toBool())

    override suspend fun logicalOr(context: Context, other: Obj): Obj = ObjBool(value || other.toBool())

    companion object {
        val type = ObjClass("Bool")
    }
}

val ObjTrue = ObjBool(true)
val ObjFalse = ObjBool(false)