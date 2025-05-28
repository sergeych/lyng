package net.sergeych.ling

data class Arguments(val list: List<Info>): Iterable<Obj> {

    data class Info(val value: Obj,val pos: Pos)

    val size by list::size

    operator fun get(index: Int): Obj = list[index].value

    fun firstAndOnly(): Obj {
        if( list.size != 1 ) throw IllegalArgumentException("Expected one argument, got ${list.size}")
        return list.first().value
    }

    inline fun <reified T: Obj>required(index: Int, context: Context): T {
        if( list.size <= index ) context.raiseError("Expected at least ${index+1} argument, got ${list.size}")
        return (list[index].value as? T)
            ?: context.raiseError("Expected type ${T::class.simpleName}, got ${list[index].value::class.simpleName}")
    }

    companion object {
        val EMPTY = Arguments(emptyList())
    }

    override fun iterator(): Iterator<Obj> {
        return list.map { it.value }.iterator()
    }
}

fun List<Arguments.Info>.toArguments() = Arguments(this )