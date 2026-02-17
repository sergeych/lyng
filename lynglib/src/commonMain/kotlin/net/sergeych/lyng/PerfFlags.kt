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
    // Extend small-arity no-alloc argument paths to 9..12 (JVM-first; default via PerfDefaults)
    var ARG_SMALL_ARITY_12: Boolean = PerfDefaults.ARG_SMALL_ARITY_12
    // Allow early-return in optional calls before building args (semantics-compatible). Present for A/B only.
    var SKIP_ARGS_ON_NULL_RECEIVER: Boolean = PerfDefaults.SKIP_ARGS_ON_NULL_RECEIVER
    // Enable pooling of Scope frames for calls (may be JVM-only optimization)
    var SCOPE_POOL: Boolean = PerfDefaults.SCOPE_POOL

    // Step 2: PICs for fields and methods
    var FIELD_PIC: Boolean = PerfDefaults.FIELD_PIC
    var METHOD_PIC: Boolean = PerfDefaults.METHOD_PIC

    // Optional: expand PIC size for fields/methods from 2 to 4 entries (JVM-first tuning)
    // Initialized from platform defaults; still runtime-togglable.
    var FIELD_PIC_SIZE_4: Boolean = PerfDefaults.FIELD_PIC_SIZE_4
    var METHOD_PIC_SIZE_4: Boolean = PerfDefaults.METHOD_PIC_SIZE_4
    // Adaptive growth from 2→4 entries per-site for field/method PICs when polymorphism is high (JVM-first)
    var PIC_ADAPTIVE_2_TO_4: Boolean = PerfDefaults.PIC_ADAPTIVE_2_TO_4
    // Adaptive growth for methods only (independent of fields)
    var PIC_ADAPTIVE_METHODS_ONLY: Boolean = PerfDefaults.PIC_ADAPTIVE_METHODS_ONLY
    // Heuristic to avoid or revert promotion when it shows no benefit (experimental)
    var PIC_ADAPTIVE_HEURISTIC: Boolean = PerfDefaults.PIC_ADAPTIVE_HEURISTIC

    // Index PIC/fast paths (JVM-first). By default follow FIELD_PIC enablement to avoid extra flags churn.
    // Host apps/tests can flip independently if needed.
    var INDEX_PIC: Boolean = FIELD_PIC
    // Optional 4-entry PIC for IndexRef (JVM-first tuning); initialized from platform defaults
    var INDEX_PIC_SIZE_4: Boolean = PerfDefaults.INDEX_PIC_SIZE_4

    // Debug/observability for PICs and fast paths (JVM-first)
    var PIC_DEBUG_COUNTERS: Boolean = PerfDefaults.PIC_DEBUG_COUNTERS

    // Step 3: Primitive arithmetic and comparison fast paths
    var PRIMITIVE_FASTOPS: Boolean = PerfDefaults.PRIMITIVE_FASTOPS

    // Step 4: R-value fast path to bypass ObjRecord in pure expression evaluation
    var RVAL_FASTPATH: Boolean = PerfDefaults.RVAL_FASTPATH

    // Regex: enable small LRU cache for compiled patterns (JVM-first usage)
    var REGEX_CACHE: Boolean = PerfDefaults.REGEX_CACHE

    // Specialized non-allocating integer range iteration in hot loops
    var RANGE_FAST_ITER: Boolean = PerfDefaults.RANGE_FAST_ITER

}
