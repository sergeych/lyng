package net.sergeych.lyng

actual object PerfDefaults {
    actual val LOCAL_SLOT_PIC: Boolean = true
    actual val EMIT_FAST_LOCAL_REFS: Boolean = true

    actual val ARG_BUILDER: Boolean = true
    actual val SKIP_ARGS_ON_NULL_RECEIVER: Boolean = true
    actual val SCOPE_POOL: Boolean = false

    actual val FIELD_PIC: Boolean = true
    actual val METHOD_PIC: Boolean = true

    actual val PIC_DEBUG_COUNTERS: Boolean = false

    actual val PRIMITIVE_FASTOPS: Boolean = true
    // Conservative default for non-JVM until validated
    actual val RVAL_FASTPATH: Boolean = false
    // Regex caching: keep OFF by default on Native until benchmarks validate it
    actual val REGEX_CACHE: Boolean = false
}