package net.sergeych.ling

/**
 * Whatever [Obj] stored somewhere
 */
data class StoredObj(
    val name: String,
    var value: Obj?,
    val isMutable: Boolean = false
)