package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope
import net.sergeych.lyng.statement

class ObjList(val list: MutableList<Obj> = mutableListOf()) : Obj() {

    override fun toString(): String = "[${
        list.joinToString(separator = ", ") { it.inspect() }
    }]"

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        return when (index) {
            is ObjInt -> {
                list[index.toInt()]
            }

            is ObjRange -> {
                when {
                    index.start is ObjInt && index.end is ObjInt -> {
                        if (index.isEndInclusive)
                            ObjList(list.subList(index.start.toInt(), index.end.toInt() + 1).toMutableList())
                        else
                            ObjList(list.subList(index.start.toInt(), index.end.toInt()).toMutableList())
                    }

                    index.isOpenStart && !index.isOpenEnd -> {
                        if (index.isEndInclusive)
                            ObjList(list.subList(0, index.end!!.toInt() + 1).toMutableList())
                        else
                            ObjList(list.subList(0, index.end!!.toInt()).toMutableList())
                    }

                    index.isOpenEnd && !index.isOpenStart -> {
                        ObjList(list.subList(index.start!!.toInt(), list.size).toMutableList())
                    }

                    index.isOpenStart && index.isOpenEnd -> {
                        ObjList(list.toMutableList())
                    }

                    else -> {
                        throw RuntimeException("Can't apply range for index: $index")
                    }
                }
            }

            else -> scope.raiseIllegalArgument("Illegal index object for a list: ${index.inspect()}")
        }
    }

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        list[index.toInt()] = newValue
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is ObjList) return -2
        val mySize = list.size
        val otherSize = other.list.size
        val commonSize = minOf(mySize, otherSize)
        for (i in 0..<commonSize) {
            if (list[i].compareTo(scope, other.list[i]) != 0) {
                return list[i].compareTo(scope, other.list[i])
            }
        }
        // equal so far, longer is greater:
        return when {
            mySize < otherSize -> -1
            mySize > otherSize -> 1
            else -> 0
        }
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj =
        when {
            other is ObjList ->
                ObjList((list + other.list).toMutableList())

            other.isInstanceOf(ObjIterable) -> {
                val l = other.callMethod<ObjList>(scope, "toList")
                ObjList((list + l.list).toMutableList())
            }

            else ->
                scope.raiseError("'+': can't concatenate $this with $other")
        }


    override suspend fun plusAssign(scope: Scope, other: Obj): Obj {
        // optimization
        if (other is ObjList) {
            list += other.list
            return this
        }
        if (other.isInstanceOf(ObjIterable)) {
            val otherList = other.invokeInstanceMethod(scope, "toList") as ObjList
            list += otherList.list
        } else
            list += other
        return this
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {
        return list.contains(other)
    }

    override val objClass: ObjClass
        get() = type

    override suspend fun toKotlin(scope: Scope): Any {
        return list.map { it.toKotlin(scope) }
    }

    override fun hashCode(): Int {
        // check?
        return list.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjList

        return list == other.list
    }

    companion object {
        val type = ObjClass("List", ObjArray).apply {

            createField("size",
                statement {
                    (thisObj as ObjList).list.size.toObj()
                }
            )
            createField("add",
                statement {
                    val l = thisAs<ObjList>().list
                    for (a in args) l.add(a)
                    ObjVoid
                }
            )
            addFn("insertAt") {
                if (args.size < 2) raiseError("addAt takes 2+ arguments")
                val l = thisAs<ObjList>()
                var index = requiredArg<ObjInt>(0).value.toInt()
                for (i in 1..<args.size) l.list.add(index++, args[i])
                ObjVoid
            }

            addFn("removeAt") {
                val self = thisAs<ObjList>()
                val start = requiredArg<ObjInt>(0).value.toInt()
                if (args.size == 2) {
                    val end = requireOnlyArg<ObjInt>().value.toInt()
                    self.list.subList(start, end).clear()
                } else
                    self.list.removeAt(start)
                self
            }

            addFn("removeLast") {
                val self = thisAs<ObjList>()
                if (args.isNotEmpty()) {
                    val count = requireOnlyArg<ObjInt>().value.toInt()
                    val size = self.list.size
                    if (count >= size) self.list.clear()
                    else self.list.subList(size - count, size).clear()
                } else self.list.removeLast()
                self
            }

            addFn("removeRange") {
                val self = thisAs<ObjList>()
                val list = self.list
                val range = requiredArg<Obj>(0)
                if (range is ObjRange) {
                    val index = range
                    when {
                        index.start is ObjInt && index.end is ObjInt -> {
                            if (index.isEndInclusive)
                                list.subList(index.start.toInt(), index.end.toInt() + 1)
                            else
                                list.subList(index.start.toInt(), index.end.toInt())
                        }

                        index.isOpenStart && !index.isOpenEnd -> {
                            if (index.isEndInclusive)
                                list.subList(0, index.end!!.toInt() + 1)
                            else
                                list.subList(0, index.end!!.toInt())
                        }

                        index.isOpenEnd && !index.isOpenStart -> {
                            list.subList(index.start!!.toInt(), list.size)
                        }

                        index.isOpenStart && index.isOpenEnd -> {
                            list
                        }

                        else -> {
                            throw RuntimeException("Can't apply range for index: $index")
                        }
                    }.clear()
                } else {
                    val start = range.toInt()
                    val end = requiredArg<ObjInt>(1).value.toInt() + 1
                    self.list.subList(start, end).clear()
                }
                self
            }
        }
    }
}


