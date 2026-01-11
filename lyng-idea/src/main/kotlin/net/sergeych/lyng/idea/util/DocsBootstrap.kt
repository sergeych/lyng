/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

/*
 * Ensure external/bundled docs are registered in BuiltinDocRegistry
 * so completion/quickdoc can resolve things like lyng.io.fs.Path.
 */
package net.sergeych.lyng.idea.util

import com.intellij.openapi.diagnostic.Logger
import net.sergeych.lyng.idea.docs.FsDocsFallback
import net.sergeych.lyng.idea.docs.ProcessDocsFallback

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

    private fun tryLoadExternal(): Boolean {
        var anyLoaded = false
        try {
            val cls = Class.forName("net.sergeych.lyngio.docs.FsBuiltinDocs")
            val m = cls.getMethod("ensure")
            m.invoke(null)
            log.info("[LYNG_DEBUG] DocsBootstrap: external docs loaded: net.sergeych.lyngio.docs.FsBuiltinDocs.ensure() OK")
            anyLoaded = true
        } catch (_: Throwable) {}

        try {
            val cls = Class.forName("net.sergeych.lyngio.docs.ProcessBuiltinDocs")
            val m = cls.getMethod("ensure")
            m.invoke(null)
            log.info("[LYNG_DEBUG] DocsBootstrap: external docs loaded: net.sergeych.lyngio.docs.ProcessBuiltinDocs.ensure() OK")
            anyLoaded = true
        } catch (_: Throwable) {}
        return anyLoaded
    }

    private fun trySeedFallback(): Boolean = try {
        val seededFs = FsDocsFallback.ensureOnce()
        val seededProcess = ProcessDocsFallback.ensureOnce()
        val seeded = seededFs || seededProcess
        if (seeded) {
            log.info("[LYNG_DEBUG] DocsBootstrap: external docs not found; seeded plugin fallback for lyng.io.fs/process")
        } else {
            log.info("[LYNG_DEBUG] DocsBootstrap: external docs not found; no fallback seeded")
        }
        seeded
    } catch (_: Throwable) {
        false
    }
}
