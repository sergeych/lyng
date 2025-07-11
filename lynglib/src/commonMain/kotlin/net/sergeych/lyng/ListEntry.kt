package net.sergeych.lyng

import net.sergeych.lyng.obj.Accessor

sealed class ListEntry {
    data class Element(val accessor: Accessor) : ListEntry()

    data class Spread(val accessor: Accessor) : ListEntry()
}