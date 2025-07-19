package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope
import net.sergeych.lyng.Visibility

/**
 * Record to store object with access rules, e.g. [isMutable] and access level [visibility].
 */
data class ObjRecord(
    var value: Obj,
    val isMutable: Boolean,
    val visibility: Visibility = Visibility.Public,
    var importedFrom: Scope? = null
) {
    @Suppress("unused")
    fun qualifiedName(name: String): String =
        "${importedFrom?.packageName ?: "anonymous"}.$name"
}