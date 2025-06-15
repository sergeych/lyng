package net.sergeych.lyng

class ObjSet(val set: MutableSet<Obj> = mutableSetOf()) : Obj() {

    override val objClass = type

    override suspend fun contains(context: Context, other: Obj): Boolean {
        return set.contains(other)
    }

    override suspend fun plus(context: Context, other: Obj): Obj {
        return ObjSet(
            if (other is ObjSet)
                (set + other.set).toMutableSet()
            else
                (set + other).toMutableSet()
        )
    }

    override suspend fun plusAssign(context: Context, other: Obj): Obj {
        when (other) {
            is ObjSet -> {
                set += other.set
            }

            is ObjList -> {
                set += other.list
            }

            else -> {
                if (other.isInstanceOf(ObjIterable)) {
                    val i = other.invokeInstanceMethod(context, "iterable")
                    while (i.invokeInstanceMethod(context, "hasNext").toBool()) {
                        set += i.invokeInstanceMethod(context, "next")
                    }
                }
                set += other
            }
        }
        return this
    }

    override suspend fun mul(context: Context, other: Obj): Obj {
        return if (other is ObjSet) {
            ObjSet(set.intersect(other.set).toMutableSet())
        } else
            context.raiseIllegalArgument("set operator * requires another set")
    }

    override suspend fun minus(context: Context, other: Obj): Obj {
        if (other !is ObjSet)
            context.raiseIllegalArgument("set operator - requires another set")
        return ObjSet(set.minus(other.set).toMutableSet())
    }

    override fun toString(): String {
        return "Set(${set.joinToString(", ")})"
    }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        return if (other !is ObjSet) -1
        else {
            if (set == other.set) 0
            else -1
        }
    }

    companion object {


        val type = object : ObjClass("Set", ObjCollection) {
            override suspend fun callOn(context: Context): Obj {
                return ObjSet(context.args.list.toMutableSet())
            }
        }.apply {
            addFn("size") {
                thisAs<ObjSet>().set.size.toObj()
            }
            addFn("intersect") {
                thisAs<ObjSet>().mul(this, args.firstAndOnly())
            }
            addFn("iterator") {
                thisAs<ObjSet>().set.iterator().toObj()
            }
            addFn("union") {
                thisAs<ObjSet>().plus(this, args.firstAndOnly())
            }
            addFn("subtract") {
                thisAs<ObjSet>().minus(this, args.firstAndOnly())
            }
            addFn("remove") {
                val set = thisAs<ObjSet>().set
                val n = set.size
                for( x in args.list ) set -= x
                if( n == set.size ) ObjFalse else ObjTrue
            }
        }
    }
}