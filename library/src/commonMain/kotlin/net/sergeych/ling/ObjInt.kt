package net.sergeych.ling

data class ObjInt(var value: Long) : Obj(), Numeric {
    override val asStr get() = ObjString(value.toString())
    override val longValue get() = value
    override val doubleValue get() = value.toDouble()
    override val toObjInt get() = this
    override val toObjReal = ObjReal(doubleValue)

    override fun byValueCopy(): Obj = ObjInt(value)

    override suspend fun getAndIncrement(context: Context): Obj {
        return ObjInt(value).also { value++ }
    }

    override suspend fun getAndDecrement(context: Context): Obj {
        return ObjInt(value).also { value-- }
    }

    override suspend fun incrementAndGet(context: Context): Obj {
        return ObjInt(++value)
    }

    override suspend fun decrementAndGet(context: Context): Obj {
        return ObjInt(--value)
    }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is Numeric) return -2
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String = value.toString()

    override val objClass: ObjClass = type

    override suspend fun plus(context: Context, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value + other.value)
        else
            ObjReal(this.doubleValue + other.toDouble())

    override suspend fun minus(context: Context, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value - other.value)
        else
            ObjReal(this.doubleValue - other.toDouble())

    override suspend fun mul(context: Context, other: Obj): Obj =
        if (other is ObjInt) {
            ObjInt(this.value * other.value)
        } else ObjReal(this.value * other.toDouble())

    override suspend fun div(context: Context, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value / other.value)
        else ObjReal(this.value / other.toDouble())

    override suspend fun mod(context: Context, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value % other.value)
        else ObjReal(this.value.toDouble() % other.toDouble())

    /**
     * We are by-value type ([byValueCopy] is implemented) so we can do in-place
     * assignment
     */
    override suspend fun assign(context: Context, other: Obj): Obj? {
        return if (other is ObjInt) {
            value = other.value
            this
        } else null
    }

    companion object {
        val type = ObjClass("Int")
    }
}