package net.sergeych.lyng

sealed class ListEntry {
    data class Element(val accessor: Accessor) : ListEntry()

    data class Spread(val accessor: Accessor) : ListEntry()
}