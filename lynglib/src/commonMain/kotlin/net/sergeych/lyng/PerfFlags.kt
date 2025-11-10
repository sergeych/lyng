package net.sergeych.lyng

/**
 * Runtime-togglable perf flags for micro-benchmarking and A/B comparisons on the JVM.
 * Keep as `var` so tests can flip them.
 */
object PerfFlags {
    // Enable PIC inside LocalVarRef (runtime cache of name->slot per frame)
    var LOCAL_SLOT_PIC: Boolean = true
    // Make the compiler emit fast local refs for identifiers known to be function locals/params
    var EMIT_FAST_LOCAL_REFS: Boolean = true

    // Enable more efficient argument building and bulk-copy for splats
    var ARG_BUILDER: Boolean = true
    // Allow early-return in optional calls before building args (semantics-compatible). Present for A/B only.
    var SKIP_ARGS_ON_NULL_RECEIVER: Boolean = true
    // Enable pooling of Scope frames for calls (planned; JVM-only)
    var SCOPE_POOL: Boolean = false

    // Step 2: PICs for fields and methods
    var FIELD_PIC: Boolean = true
    var METHOD_PIC: Boolean = true

    // Step 3: Primitive arithmetic and comparison fast paths
    var PRIMITIVE_FASTOPS: Boolean = true
}
