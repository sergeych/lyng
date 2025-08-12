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
    var importedFrom: Scope? = null,
    val isTransient: Boolean = false,
    val type: Type = Type.Other
) {
    enum class Type(val comparable: Boolean = false,val serializable: Boolean = false) {
        Field(true, true),
        @Suppress("unused")
        Fun,
        ConstructorField(true, true),
        @Suppress("unused")
        Class,
        Enum,
        Other
    }
    @Suppress("unused")
    fun qualifiedName(name: String): String =
        "${importedFrom?.packageName ?: "anonymous"}.$name"
}