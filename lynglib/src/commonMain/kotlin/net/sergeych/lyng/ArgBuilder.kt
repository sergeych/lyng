package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj

/**
 * Expect/actual API for an arguments builder that can reuse buffers on JVM to reduce allocations.
 * Default (non-JVM) implementation just allocates fresh collections and has no pooling.
 */
expect object ArgBuilderProvider {
    fun acquire(): ArgsBuilder
}

interface ArgsBuilder {
    /** Prepare the builder for a new build, optionally hinting capacity. */
    fun reset(expectedSize: Int = 0)
    fun add(v: Obj)
    fun addAll(vs: List<Obj>)
    /** Build immutable [Arguments] snapshot from the current buffer. */
    fun build(tailBlockMode: Boolean): Arguments
    /** Return builder to pool/reset state. Safe to no-op on non-JVM. */
    fun release()
}
