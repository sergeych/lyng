package net.sergeych.lyng

data class ParsedArgument(val value: Statement, val pos: Pos, val isSplat: Boolean = false)

suspend fun Collection<ParsedArgument>.toArguments(context: Context): Arguments {
    val list = mutableListOf<Obj>()

    for (x in this) {
        val value = x.value.execute(context)
        if (x.isSplat) {
            when {
                value is ObjList -> {
                    for (subitem in value.list) list.add(subitem)
                }

                value.isInstanceOf(ObjIterable) -> {
                    val i = (value.invokeInstanceMethod(context, "toList") as ObjList).list
                    i.forEach { list.add(it) }
                }

                else -> context.raiseClassCastError("expected list of objects for splat argument")
            }
        } else
            list.add(value)
    }
    return Arguments(list)
}

data class Arguments(val list: List<Obj>) : List<Obj> by list {

    data class Info(val value: Obj, val pos: Pos)

    val values = list

    fun firstAndOnly(): Obj {
        if (list.size != 1) throw IllegalArgumentException("Expected one argument, got ${list.size}")
        return list.first()
    }

    companion object {
        val EMPTY = Arguments(emptyList())
        fun from(values: Collection<Obj>) = Arguments(values.toList())
    }
}

