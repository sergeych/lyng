package net.sergeych.lyng

class ObjInt(var value: Long,val isConst: Boolean = false) : Obj(), Numeric {
    override val asStr get() = ObjString(value.toString())
    override val longValue get() = value
    override val doubleValue get() = value.toDouble()
    override val toObjInt get() = this
    override val toObjReal = ObjReal(doubleValue)

    override fun byValueCopy(): Obj = ObjInt(value)

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override suspend fun getAndIncrement(scope: Scope): Obj {
        return ObjInt(value).also { value++ }
    }

    override suspend fun getAndDecrement(scope: Scope): Obj {
        return ObjInt(value).also { value-- }
    }

    override suspend fun incrementAndGet(scope: Scope): Obj {
        return ObjInt(++value)
    }

    override suspend fun decrementAndGet(scope: Scope): Obj {
        return ObjInt(--value)
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is Numeric) return -2
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String = value.toString()

    override val objClass: ObjClass = type

    override suspend fun plus(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value + other.value)
        else
            ObjReal(this.doubleValue + other.toDouble())

    override suspend fun minus(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value - other.value)
        else
            ObjReal(this.doubleValue - other.toDouble())

    override suspend fun mul(scope: Scope, other: Obj): Obj =
        if (other is ObjInt) {
            ObjInt(this.value * other.value)
        } else ObjReal(this.value * other.toDouble())

    override suspend fun div(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value / other.value)
        else ObjReal(this.value / other.toDouble())

    override suspend fun mod(scope: Scope, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value % other.value)
        else ObjReal(this.value.toDouble() % other.toDouble())

    /**
     * We are by-value type ([byValueCopy] is implemented) so we can do in-place
     * assignment
     */
    override suspend fun assign(scope: Scope, other: Obj): Obj? {
        return if (!isConst && other is ObjInt) {
            value = other.value
            this
        } else null
    }

    override suspend fun toKotlin(scope: Scope): Any {
        return value
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjInt

        return value == other.value
    }

    companion object {
        val Zero = ObjInt(0, true)
        val One = ObjInt(1, true)
        val type = ObjClass("Int")
    }
}

fun Int.toObj() = ObjInt(this.toLong())