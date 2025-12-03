/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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
package net.sergeych.lyng.idea.grazie

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Very simple English dictionary loader for offline suggestions on IC-243.
 * It loads a word list from classpath resources. Supports plain text (one word per line)
 * and gzipped text if the resource ends with .gz.
 */
object EnglishDictionary {
    private val log = Logger.getInstance(EnglishDictionary::class.java)

    @Volatile private var loaded = false
    @Volatile private var words: Set<String> = emptySet()

    /**
     * Load dictionary from bundled resources (once).
     * If multiple candidates exist, the first found is used.
     */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val candidates = listOf(
                // preferred large bundles first (add en-basic.txt.gz ~3–5MB here)
                "/dictionaries/en-basic.txt.gz",
                "/dictionaries/en-large.txt.gz",
                // plain text fallbacks
                "/dictionaries/en-basic.txt",
                "/dictionaries/en-large.txt",
            )
            val merged = HashSet<String>(128_000)
            for (res in candidates) {
                try {
                    val stream = javaClass.getResourceAsStream(res) ?: continue
                    val reader = if (res.endsWith(".gz"))
                        BufferedReader(InputStreamReader(GZIPInputStream(stream)))
                    else
                        BufferedReader(InputStreamReader(stream))
                    var loadedCount = 0
                    reader.useLines { seq -> seq.forEach { line ->
                        val w = line.trim()
                        if (w.isNotEmpty() && !w.startsWith("#")) { merged += w.lowercase(); loadedCount++ }
                    } }
                    log.info("EnglishDictionary: loaded $loadedCount words from $res (total=${merged.size})")
                } catch (t: Throwable) {
                    log.info("EnglishDictionary: failed to load $res: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            if (merged.isEmpty()) {
                // Fallback minimal set
                merged += setOf("comment","comments","error","errors","found","file","not","word","words","count","value","name","class","function","string")
                log.info("EnglishDictionary: using minimal built-in set (${merged.size})")
            }
            words = merged
            loaded = true
        }
    }

    fun allWords(): Set<String> {
        ensureLoaded()
        return words
    }
}
