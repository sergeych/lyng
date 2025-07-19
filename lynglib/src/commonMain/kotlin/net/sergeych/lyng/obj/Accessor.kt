
package net.sergeych.lyng.obj

import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScriptError

// avoid KDOC bug: keep it
@Suppress("unused")
typealias DocCompiler = Compiler
/**
 * When we need read-write access to an object in some abstract storage, we need Accessor,
 * as in-site assigning is not always sufficient, in general case we need to replace the object
 * in the storage.
 *
 * Note that assigning new value is more complex than just replacing the object, see how assignment
 * operator is implemented in [Compiler.allOps].
 */
data class Accessor(
    val getter: suspend (Scope) -> ObjRecord,
    val setterOrNull: (suspend (Scope, Obj) -> Unit)?
) {
    /**
     * Simplified constructor for immutable stores.
     */
    constructor(getter: suspend (Scope) -> ObjRecord) : this(getter, null)

    /**
     * Get the setter or throw.
     */
    fun setter(pos: Pos) = setterOrNull ?: throw ScriptError(pos, "can't assign value")
}