/*
 * Ensure external/bundled docs are registered in BuiltinDocRegistry
 * so completion/quickdoc can resolve things like lyng.io.fs.Path.
 */
package net.sergeych.lyng.idea.util

import com.intellij.openapi.diagnostic.Logger
import net.sergeych.lyng.idea.docs.FsDocsFallback

object DocsBootstrap {
    private val log = Logger.getInstance(DocsBootstrap::class.java)
    @Volatile private var ensured = false

    fun ensure() {
        if (ensured) return
        synchronized(this) {
            if (ensured) return
            val loaded = tryLoadExternal() || trySeedFallback()
            if (loaded) ensured = true else ensured = true // mark done to avoid repeated attempts
        }
    }

    private fun tryLoadExternal(): Boolean = try {
        val cls = Class.forName("net.sergeych.lyngio.docs.FsBuiltinDocs")
        val m = cls.getMethod("ensure")
        m.invoke(null)
        log.info("[LYNG_DEBUG] DocsBootstrap: external docs loaded: net.sergeych.lyngio.docs.FsBuiltinDocs.ensure() OK")
        true
    } catch (_: Throwable) {
        false
    }

    private fun trySeedFallback(): Boolean = try {
        val seeded = FsDocsFallback.ensureOnce()
        if (seeded) {
            log.info("[LYNG_DEBUG] DocsBootstrap: external docs not found; seeded plugin fallback for lyng.io.fs")
        } else {
            log.info("[LYNG_DEBUG] DocsBootstrap: external docs not found; no fallback seeded")
        }
        seeded
    } catch (_: Throwable) {
        false
    }
}
