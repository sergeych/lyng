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
}
