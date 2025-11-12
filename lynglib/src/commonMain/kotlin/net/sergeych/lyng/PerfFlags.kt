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
 * Runtime-togglable perf flags for micro-benchmarking and A/B comparisons.
 * Initialized from platform-specific defaults via PerfDefaults expect/actual.
 * Keep as `var` so tests can flip them at runtime.
 */
object PerfFlags {
    // Enable PIC inside LocalVarRef (runtime cache of name->slot per frame)
    var LOCAL_SLOT_PIC: Boolean = PerfDefaults.LOCAL_SLOT_PIC
    // Make the compiler emit fast local refs for identifiers known to be function locals/params
    var EMIT_FAST_LOCAL_REFS: Boolean = PerfDefaults.EMIT_FAST_LOCAL_REFS

    // Enable more efficient argument building and bulk-copy for splats
    var ARG_BUILDER: Boolean = PerfDefaults.ARG_BUILDER
    // Allow early-return in optional calls before building args (semantics-compatible). Present for A/B only.
    var SKIP_ARGS_ON_NULL_RECEIVER: Boolean = PerfDefaults.SKIP_ARGS_ON_NULL_RECEIVER
    // Enable pooling of Scope frames for calls (may be JVM-only optimization)
    var SCOPE_POOL: Boolean = PerfDefaults.SCOPE_POOL

    // Step 2: PICs for fields and methods
    var FIELD_PIC: Boolean = PerfDefaults.FIELD_PIC
    var METHOD_PIC: Boolean = PerfDefaults.METHOD_PIC

    // Debug/observability for PICs and fast paths (JVM-first)
    var PIC_DEBUG_COUNTERS: Boolean = PerfDefaults.PIC_DEBUG_COUNTERS

    // Step 3: Primitive arithmetic and comparison fast paths
    var PRIMITIVE_FASTOPS: Boolean = PerfDefaults.PRIMITIVE_FASTOPS

    // Step 4: R-value fast path to bypass ObjRecord in pure expression evaluation
    var RVAL_FASTPATH: Boolean = PerfDefaults.RVAL_FASTPATH

    // Regex: enable small LRU cache for compiled patterns (JVM-first usage)
    var REGEX_CACHE: Boolean = PerfDefaults.REGEX_CACHE
}
