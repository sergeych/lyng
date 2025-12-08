/*
 * Ensure stdlib Obj*-defined docs (like String methods added via ObjString.addFnDoc)
 * are initialized before registry lookups for completion/quick docs.
 */
package net.sergeych.lyng.miniast

import net.sergeych.lyng.obj.ObjString

object StdlibDocsBootstrap {
    // Simple idempotent guard; races are harmless as initializer side-effects are idempotent
    private var ensured = false

    fun ensure() {
        if (ensured) return
        try {
            // Touch core Obj* types whose docs are registered via addFnDoc/addConstDoc
            // Accessing .type forces their static initializers to run and register docs.
            @Suppress("UNUSED_VARIABLE")
            val _string = ObjString.type
        } catch (_: Throwable) {
            // Best-effort; absence should not break consumers
        } finally {
            ensured = true
        }
    }
}
