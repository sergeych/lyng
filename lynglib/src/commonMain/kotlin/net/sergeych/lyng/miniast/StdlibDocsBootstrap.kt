/*
 * Ensure stdlib Obj*-defined docs (like String methods added via ObjString.addFnDoc)
 * are initialized before registry lookups for completion/quick docs.
 */
package net.sergeych.lyng.miniast

object StdlibDocsBootstrap {
    // Simple idempotent guard; races are harmless as initializer side-effects are idempotent
    private var ensured = false

    fun ensure() {
        if (ensured) return
        try {
            // Touch core Obj* types whose docs are registered via addFnDoc/addConstDoc
            // Accessing .type forces their static initializers to run and register docs.
            @Suppress("UNUSED_VARIABLE")
            val _string = net.sergeych.lyng.obj.ObjString.type
            @Suppress("UNUSED_VARIABLE")
            val _any = net.sergeych.lyng.obj.Obj.rootObjectType
            @Suppress("UNUSED_VARIABLE")
            val _list = net.sergeych.lyng.obj.ObjList.type
            @Suppress("UNUSED_VARIABLE")
            val _map = net.sergeych.lyng.obj.ObjMap.type
            @Suppress("UNUSED_VARIABLE")
            val _int = net.sergeych.lyng.obj.ObjInt.type
            @Suppress("UNUSED_VARIABLE")
            val _real = net.sergeych.lyng.obj.ObjReal.type
            @Suppress("UNUSED_VARIABLE")
            val _bool = net.sergeych.lyng.obj.ObjBool.type
            @Suppress("UNUSED_VARIABLE")
            val _regex = net.sergeych.lyng.obj.ObjRegex.type
            @Suppress("UNUSED_VARIABLE")
            val _range = net.sergeych.lyng.obj.ObjRange.type
            @Suppress("UNUSED_VARIABLE")
            val _buffer = net.sergeych.lyng.obj.ObjBuffer.type
        } catch (_: Throwable) {
            // Best-effort; absence should not break consumers
        } finally {
            ensured = true
        }
    }
}
