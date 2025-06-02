package net.sergeych.lyng

data class ParsedArgument(val value: Statement, val pos: Pos, val isSplat: Boolean = false)

suspend fun Collection<ParsedArgument>.toArguments(context: Context): Arguments {
    val list = mutableListOf<Arguments.Info>()

    for (x in this) {
        val value = x.value.execute(context)
        if (x.isSplat) {
            (value as? ObjList) ?: context.raiseClassCastError("expected list of objects for splat argument")
            for (subitem in value.list) list.add(Arguments.Info(subitem, x.pos))
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
    }

    override fun iterator(): Iterator<Obj> {
        return list.map { it.value }.iterator()
    }

}

