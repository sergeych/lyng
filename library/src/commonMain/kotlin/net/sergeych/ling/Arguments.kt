package net.sergeych.ling

data class Arguments(val list: List<Obj> ) {

    val size by list::size

    operator fun get(index: Int): Obj = list[index]

    fun firstAndOnly(): Obj {
        if( list.size != 1 ) throw IllegalArgumentException("Expected one argument, got ${list.size}")
        return list.first()
    }

    companion object {
        val EMPTY = Arguments(emptyList())
    }
}