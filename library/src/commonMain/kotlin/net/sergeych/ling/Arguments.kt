package net.sergeych.ling

data class Arguments(val callerPos: Pos,val list: List<Info>): Iterable<Obj> {

    data class Info(val value: Obj,val pos: Pos)

    val size by list::size

    operator fun get(index: Int): Obj = list[index].value

    fun firstAndOnly(): Obj {
        if( list.size != 1 ) throw IllegalArgumentException("Expected one argument, got ${list.size}")
        return list.first().value
    }

    companion object {
        val EMPTY = Arguments("".toSource().startPos,emptyList())
    }

    override fun iterator(): Iterator<Obj> {
        return list.map { it.value }.iterator()
    }
}