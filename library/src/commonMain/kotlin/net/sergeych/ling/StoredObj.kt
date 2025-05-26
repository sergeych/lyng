package net.sergeych.ling

/**
 * Whatever [Obj] stored somewhere
 */
data class StoredObj(
    var value: Obj?,
    val isMutable: Boolean = false
)