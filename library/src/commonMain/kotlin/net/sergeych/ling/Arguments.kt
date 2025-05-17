package net.sergeych.ling

data class Arguments(val list: List<Statement> ) {

    val size by list::size

    operator fun get(index: Int): Statement = list[index]

    companion object {
        val EMPTY = Arguments(emptyList())
    }
}