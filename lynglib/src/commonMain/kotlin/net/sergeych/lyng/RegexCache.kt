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

package net.sergeych.lyng

/**
 * Tiny, size-bounded cache for compiled Regex patterns. Activated only when [PerfFlags.REGEX_CACHE] is true.
 * This is a very simple FIFO-ish cache sufficient for micro-benchmarks and common repeated patterns.
 * Not thread-safe by design; the interpreter typically runs scripts on confined executors.
 */
object RegexCache {
    private const val MAX = 64
    private val map: MutableMap<String, Regex> = LinkedHashMap()

    fun get(pattern: String): Regex {
        // Fast path: return cached instance if present
        map[pattern]?.let {
            // Emulate access-order LRU on all targets by moving the entry to the tail
            // (LinkedHashMap preserves insertion order; remove+put moves it to the end)
            map.remove(pattern)
            map[pattern] = it
            return it
        }
        // Compile new pattern
        val re = pattern.toRegex()
        // Keep the cache size bounded
        if (map.size >= MAX) {
            // Remove the oldest inserted entry (first key in iteration order)
            val it = map.keys.iterator()
            if (it.hasNext()) { val k = it.next(); it.remove() }
        }
        map[pattern] = re
        return re
    }

    fun clear() = map.clear()
}