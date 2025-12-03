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
 * Lightweight technical/Lyng vocabulary dictionary.
 * Loaded from classpath resources; supports .txt and .txt.gz. Merged with EnglishDictionary.
 */
object TechDictionary {
    private val log = Logger.getInstance(TechDictionary::class.java)
    @Volatile private var loaded = false
    @Volatile private var words: Set<String> = emptySet()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val candidates = listOf(
                "/dictionaries/tech-lyng.txt.gz",
                "/dictionaries/tech-lyng.txt"
            )
            val merged = HashSet<String>(8_000)
            for (res in candidates) {
                try {
                    val stream = javaClass.getResourceAsStream(res) ?: continue
                    val reader = if (res.endsWith(".gz"))
                        BufferedReader(InputStreamReader(GZIPInputStream(stream)))
                    else
                        BufferedReader(InputStreamReader(stream))
                    var n = 0
                    reader.useLines { seq -> seq.forEach { line ->
                        val w = line.trim()
                        if (w.isNotEmpty() && !w.startsWith("#")) { merged += w.lowercase(); n++ }
                    } }
                    log.info("TechDictionary: loaded $n words from $res (total=${merged.size})")
                } catch (t: Throwable) {
                    log.info("TechDictionary: failed to load $res: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            if (merged.isEmpty()) {
                merged += setOf(
                    // minimal Lyng/tech seeding to avoid empty dictionary
                    "lyng","miniast","binder","printf","specifier","specifiers","regex","token","tokens",
                    "identifier","identifiers","keyword","keywords","comment","comments","string","strings",
                    "literal","literals","formatting","formatter","grazie","typo","typos","dictionary","dictionaries"
                )
                log.info("TechDictionary: using minimal built-in set (${merged.size})")
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
