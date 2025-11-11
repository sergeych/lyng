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
        map[pattern]?.let { return it }
        // Compile new pattern
        val re = pattern.toRegex()
        // Keep the cache size bounded
        if (map.size >= MAX) {
            // Remove the oldest inserted entry (first key in iteration order)
            val it = map.keys.iterator()
            if (it.hasNext()) {
                val k = it.next()
                it.remove()
            }
        }
        map[pattern] = re
        return re
    }

    fun clear() = map.clear()
}