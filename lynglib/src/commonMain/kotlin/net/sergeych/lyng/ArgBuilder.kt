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
