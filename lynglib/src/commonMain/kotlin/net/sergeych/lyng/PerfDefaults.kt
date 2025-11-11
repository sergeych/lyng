package net.sergeych.lyng

/**
 * Platform-specific default values for performance flags.
 * These defaults are applied once at static init time in [PerfFlags] and can
 * be overridden at runtime by tests or callers.
 */
expect object PerfDefaults {
    val LOCAL_SLOT_PIC: Boolean
    val EMIT_FAST_LOCAL_REFS: Boolean

    val ARG_BUILDER: Boolean
    val SKIP_ARGS_ON_NULL_RECEIVER: Boolean
    val SCOPE_POOL: Boolean

    val FIELD_PIC: Boolean
    val METHOD_PIC: Boolean

    val PIC_DEBUG_COUNTERS: Boolean

    val PRIMITIVE_FASTOPS: Boolean
    val RVAL_FASTPATH: Boolean

    // Regex caching (JVM-first): small LRU for compiled patterns
    val REGEX_CACHE: Boolean
}
