package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjSet(val set: MutableSet<Obj> = mutableSetOf()) : Obj() {

    override val objClass = type

    override suspend fun contains(scope: Scope, other: Obj): Boolean {
        return set.contains(other)
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        return ObjSet(
            if (other is ObjSet)
                (set + other.set).toMutableSet()
            else
                (set + other).toMutableSet()
        )
    }

    override suspend fun plusAssign(scope: Scope, other: Obj): Obj {
        when (other) {
            is ObjSet -> {
                set += other.set
            }

            is ObjList -> {
                set += other.list
            }

            else -> {
                if (other.isInstanceOf(ObjIterable)) {
                    val i = other.invokeInstanceMethod(scope, "iterable")
                    while (i.invokeInstanceMethod(scope, "hasNext").toBool()) {
                        set += i.invokeInstanceMethod(scope, "next")
                    }
                }
                set += other
            }
        }
        return this
    }

    override suspend fun mul(scope: Scope, other: Obj): Obj {
        return if (other is ObjSet) {
            ObjSet(set.intersect(other.set).toMutableSet())
        } else
            scope.raiseIllegalArgument("set operator * requires another set")
    }

    override suspend fun minus(scope: Scope, other: Obj): Obj {
        if (other !is ObjSet)
            scope.raiseIllegalArgument("set operator - requires another set")
        return ObjSet(set.minus(other.set).toMutableSet())
    }

    override fun toString(): String {
        return "Set(${set.joinToString(", ")})"
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        return if (other !is ObjSet) -1
        else {
            if (set == other.set) 0
            else -1
        }
    }

    override fun hashCode(): Int {
        return set.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjSet

        return set == other.set
    }

    override suspend fun lynonType(): LynonType = LynonType.Set

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeAnyList(scope, set.toList())
    }

    companion object {


        val type = object : ObjClass("Set", ObjCollection) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjSet(scope.args.list.toMutableSet())
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
                ObjSet(decoder.decodeAnyList(scope).toMutableSet())
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