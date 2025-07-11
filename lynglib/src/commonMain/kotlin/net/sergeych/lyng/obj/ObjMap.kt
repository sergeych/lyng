package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope
import net.sergeych.lyng.Statement

class ObjMapEntry(val key: Obj, val value: Obj) : Obj() {

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is ObjMapEntry) return -1
        val c = key.compareTo(scope, other.key)
        if (c != 0) return c
        return value.compareTo(scope, other.value)
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj = when (index.toInt()) {
        0 -> key
        1 -> value
        else -> scope.raiseIndexOutOfBounds()
    }

    override fun toString(): String {
        return "$key=>$value"
    }

    override val objClass = type

    companion object {
        val type = object : ObjClass("MapEntry", ObjArray) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjMapEntry(scope.requiredArg<Obj>(0), scope.requiredArg<Obj>(1))
            }
        }.apply {
            addFn("key") { thisAs<ObjMapEntry>().key }
            addFn("value") { thisAs<ObjMapEntry>().value }
            addFn("size") { 2.toObj() }
        }
    }
}

class ObjMap(val map: MutableMap<Obj, Obj> = mutableMapOf()) : Obj() {

    override val objClass = type

    override suspend fun getAt(scope: Scope, index: Obj): Obj =
        map.get(index) ?: ObjNull

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        map[index] = newValue
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {
        return other in map
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if( other is ObjMap && other.map == map) return 0
        return -1
    }
    override fun toString(): String = map.toString()

    override fun hashCode(): Int {
        return map.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjMap

        return map == other.map
    }

    companion object {

        suspend fun listToMap(scope: Scope, list: List<Obj>): MutableMap<Obj, Obj> {
            val map = mutableMapOf<Obj, Obj>()
            if (list.isEmpty()) return map

            val first = list.first()
            if (first.isInstanceOf(ObjArray)) {
                if (first.invokeInstanceMethod(scope, "size").toInt() != 2)
                    scope.raiseIllegalArgument(
                        "list to construct map entry should exactly be 2 element Array like [key,value], got $list"
                    )
            } else scope.raiseIllegalArgument("first element of map list be a Collection of 2 elements; got $first")



            list.forEach {
                map[it.getAt(scope, ObjInt.Zero)] = it.getAt(scope, ObjInt.One)
            }
            return map
        }


        val type = object : ObjClass("Map", ObjCollection) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjMap(listToMap(scope, scope.args.list))
            }
        }.apply {
            addFn("getOrNull") {
                val key = args.firstAndOnly(pos)
                thisAs<ObjMap>().map.getOrElse(key) { ObjNull }
            }
            addFn("getOrPut") {
                val key = requiredArg<Obj>(0)
                thisAs<ObjMap>().map.getOrPut(key) {
                    val lambda = requiredArg<Statement>(1)
                    lambda.execute(this)
                }
            }
            addFn("size") {
                thisAs<ObjMap>().map.size.toObj()
            }
            addFn("remove") {
                thisAs<ObjMap>().map.remove(requiredArg<Obj>(0))?.toObj() ?: ObjNull
            }
            addFn("clear") {
                thisAs<ObjMap>().map.clear()
                thisObj
            }
            addFn("keys") {
                thisAs<ObjMap>().map.keys.toObj()
            }
            addFn("values") {
                ObjList(thisAs<ObjMap>().map.values.toMutableList())
            }
            addFn("iterator") {
                ObjKotlinIterator(thisAs<ObjMap>().map.entries.iterator())
            }
        }
    }
}