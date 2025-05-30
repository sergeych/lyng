package net.sergeych.ling

class ObjList(val list: MutableList<Obj>) : Obj() {

    override fun toString(): String = "[${
        list.joinToString(separator = ", ") { it.inspect() }
    }]"

    fun normalize(context: Context, index: Int): Int {
        val i = if (index < 0) list.size + index else index
        if (i !in list.indices) context.raiseError("index $index out of bounds for size ${list.size}")
        return i
    }

    override suspend fun getAt(context: Context, index: Int): Obj {
        val i = normalize(context, index)
        return list[i]
    }

    override suspend fun putAt(context: Context, index: Int, newValue: Obj) {
        val i = normalize(context, index)
        list[i] = newValue
    }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is ObjList) context.raiseError("cannot compare $this with $other")
        val mySize = list.size
        val otherSize = other.list.size
        val commonSize = minOf(mySize, otherSize)
        for (i in 0..<commonSize) {
            if (list[i].compareTo(context, other.list[i]) != 0) {
                return list[i].compareTo(context, other.list[i])
            }
        }
        // equal so far, longer is greater:
        return when {
            mySize < otherSize -> -1
            mySize > otherSize -> 1
            else -> 0
        }
    }

    override suspend fun plus(context: Context, other: Obj): Obj {
        (other as? ObjList) ?: context.raiseError("cannot concatenate $this with $other")
        return ObjList((list + other.list).toMutableList())
    }

    override suspend fun plusAssign(context: Context, other: Obj): Obj {
        (other as? ObjList) ?: context.raiseError("cannot concatenate $this with $other")
        list += other.list
        return this
    }

    override val objClass: ObjClass
        get() = type

    companion object {
        val type = ObjClass("List").apply {
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
            createField("addAt",
                statement {
                    if (args.size < 2) raiseError("addAt takes 2+ arguments")
                    val l = thisAs<ObjList>()
                    var index = l.normalize(this, requiredArg<ObjInt>(0).value.toInt())
                    for (i in 1..<args.size) l.list.add(index++, args[i])
                    ObjVoid
                }
            )
            createField("removeAt",
                statement {
                    val self = thisAs<ObjList>()
                    val start = self.normalize(this, requiredArg<ObjInt>(0).value.toInt())
                    if (args.size == 2) {
                        val end = requiredArg<ObjInt>(1).value.toInt()
                        self.list.subList(start, self.normalize(this, end)).clear()
                    } else
                        self.list.removeAt(start)
                    self
                })
        }
    }
}