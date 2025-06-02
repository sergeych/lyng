package net.sergeych.lyng

class ObjList(val list: MutableList<Obj>) : Obj() {

    init {
        for (p in objClass.parents)
            parentInstances.add(p.defaultInstance())
    }

    override fun toString(): String = "[${
        list.joinToString(separator = ", ") { it.inspect() }
    }]"

    fun normalize(context: Context, index: Int, allowisEndInclusive: Boolean = false): Int {
        val i = if (index < 0) list.size + index else index
        if (allowisEndInclusive && i == list.size) return i
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
        if (other !is ObjList) return -2
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

    override suspend fun plus(context: Context, other: Obj): Obj =
        when {
            other is ObjList ->
                ObjList((list + other.list).toMutableList())

            other.isInstanceOf(ObjIterable) -> {
                val l = other.callMethod<ObjList>(context, "toList")
                ObjList((list + l.list).toMutableList())
            }

            else ->
                context.raiseError("'+': can't concatenate $this with $other")
        }


    override suspend fun plusAssign(context: Context, other: Obj): Obj {
        // optimization
        if (other is ObjList) {
            list += other.list
            return this
        }
        if (other.isInstanceOf(ObjIterable)) {
            val otherList = other.invokeInstanceMethod(context, "toList") as ObjList
            list += otherList.list
        } else
            list += other
        return this
    }

    override val objClass: ObjClass
        get() = type

    companion object {
        val type = ObjClass("List", ObjArray).apply {

            createField("size",
                statement {
                    (thisObj as ObjList).list.size.toObj()
                }
            )
            addFn("getAt") {
                requireExactCount(1)
                thisAs<ObjList>().getAt(this, requiredArg<ObjInt>(0).value.toInt())
            }
            addFn("putAt") {
                requireExactCount(2)
                val newValue = args[1]
                thisAs<ObjList>().putAt(this, requiredArg<ObjInt>(0).value.toInt(), newValue)
                newValue
            }
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
                    var index = l.normalize(
                        this, requiredArg<ObjInt>(0).value.toInt(),
                        allowisEndInclusive = true
                    )
                    for (i in 1..<args.size) l.list.add(index++, args[i])
                    ObjVoid
                }
            )
            addFn("removeAt") {
                val self = thisAs<ObjList>()
                val start = self.normalize(this, requiredArg<ObjInt>(0).value.toInt())
                if (args.size == 2) {
                    val end = requireOnlyArg<ObjInt>().value.toInt()
                    self.list.subList(start, self.normalize(this, end)).clear()
                } else
                    self.list.removeAt(start)
                self
            }
            addFn("removeRangeInclusive") {
                val self = thisAs<ObjList>()
                val start = self.normalize(this, requiredArg<ObjInt>(0).value.toInt())
                val end = self.normalize(this, requiredArg<ObjInt>(1).value.toInt()) + 1
                self.list.subList(start, end).clear()
                self
            }
        }
    }
}