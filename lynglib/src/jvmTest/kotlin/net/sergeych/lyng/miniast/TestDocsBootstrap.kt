package net.sergeych.lyng.miniast

/** Ensure docs modules are loaded for tests (stdlib + optional lyngio). */
object TestDocsBootstrap {
    @Volatile private var ensured = false

    fun ensure(vararg modules: String) {
        if (ensured) return
        synchronized(this) {
            if (ensured) return
            // Touch stdlib to seed lazy docs
            BuiltinDocRegistry.docsForModule("lyng.stdlib")
            // Try to load external fs docs registrar reflectively
            val ok = try {
                val cls = Class.forName("net.sergeych.lyngio.docs.FsBuiltinDocs")
                val m = cls.getMethod("ensure")
                m.invoke(null)
                true
            } catch (_: Throwable) { false }
            if (!ok) {
                // Minimal fallback for lyng.io.fs (Path with lines(): Iterator<String>)
                BuiltinDocRegistry.moduleReplace("lyng.io.fs") {
                    classDoc(name = "Path", doc = "Filesystem path class.") {
                        method(name = "lines", doc = "Iterate file as lines of text.", returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.String"))))
                        method(name = "exists", doc = "Whether the path exists.", returns = type("lyng.Bool"))
                    }
                    valDoc(name = "Path", doc = "Path class", type = type("Path"))
                }
            }
            ensured = true
        }
    }
}
