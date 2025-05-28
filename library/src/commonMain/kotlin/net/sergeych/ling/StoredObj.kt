package net.sergeych.ling

/**
 * Whatever [Obj] stored somewhere
 */
data class StoredObj(
    var value: Obj?,
    val isMutable: Boolean = false
) {
    val asAccess: WithAccess<Obj>? get() = value?.let { WithAccess(it, isMutable) }
}