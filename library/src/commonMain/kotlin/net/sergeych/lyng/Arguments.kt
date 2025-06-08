package net.sergeych.lyng

data class ParsedArgument(val value: Statement, val pos: Pos, val isSplat: Boolean = false)

suspend fun Collection<ParsedArgument>.toArguments(context: Context): Arguments {
    val list = mutableListOf<Arguments.Info>()

    for (x in this) {
        val value = x.value.execute(context)
        if (x.isSplat) {
            when {
                value is ObjList -> {
                    for (subitem in value.list) list.add(Arguments.Info(subitem, x.pos))
                }

                value.isInstanceOf(ObjIterable) -> {
                    val i = (value.invokeInstanceMethod(context, "toList") as ObjList).list
                    i.forEach { list.add(Arguments.Info(it, x.pos)) }
                }

                else -> context.raiseClassCastError("expected list of objects for splat argument")
            }
        } else
            list.add(Arguments.Info(value, x.pos))
    }
    return Arguments(list)
}

data class Arguments(val list: List<Info>) : Iterable<Obj> {

    data class Info(val value: Obj, val pos: Pos)

    val size by list::size

    operator fun get(index: Int): Obj = list[index].value

    val values: List<Obj>  by lazy { list.map { it.value } }

    fun firstAndOnly(): Obj {
        if (list.size != 1) throw IllegalArgumentException("Expected one argument, got ${list.size}")
        return list.first().value
    }

    companion object {
        val EMPTY = Arguments(emptyList())
        fun from(values: Collection<Obj>) = Arguments(values.map { Info(it, Pos.UNKNOWN) })
    }

    override fun iterator(): Iterator<Obj> {
        return list.map { it.value }.iterator()
    }

}

