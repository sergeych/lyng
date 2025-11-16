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

import net.sergeych.lyng.PerfProfiles.restore


/**
 * Helper to quickly apply groups of [PerfFlags] for different workload profiles and restore them back.
 * JVM-first defaults; safe to use on all targets. Applying a preset returns a [Snapshot] that can be
 * passed to [restore] to revert the change.
 */
object PerfProfiles {
    data class Snapshot(
        val LOCAL_SLOT_PIC: Boolean,
        val EMIT_FAST_LOCAL_REFS: Boolean,

        val ARG_BUILDER: Boolean,
        val ARG_SMALL_ARITY_12: Boolean,
        val SKIP_ARGS_ON_NULL_RECEIVER: Boolean,
        val SCOPE_POOL: Boolean,

        val FIELD_PIC: Boolean,
        val METHOD_PIC: Boolean,
        val FIELD_PIC_SIZE_4: Boolean,
        val METHOD_PIC_SIZE_4: Boolean,
        val PIC_ADAPTIVE_2_TO_4: Boolean,
        val PIC_ADAPTIVE_METHODS_ONLY: Boolean,
        val PIC_ADAPTIVE_HEURISTIC: Boolean,

        val INDEX_PIC: Boolean,
        val INDEX_PIC_SIZE_4: Boolean,

        val PIC_DEBUG_COUNTERS: Boolean,

        val PRIMITIVE_FASTOPS: Boolean,
        val RVAL_FASTPATH: Boolean,

        val REGEX_CACHE: Boolean,
        val RANGE_FAST_ITER: Boolean,
    )

    fun snapshot(): Snapshot = Snapshot(
        LOCAL_SLOT_PIC = PerfFlags.LOCAL_SLOT_PIC,
        EMIT_FAST_LOCAL_REFS = PerfFlags.EMIT_FAST_LOCAL_REFS,

        ARG_BUILDER = PerfFlags.ARG_BUILDER,
        ARG_SMALL_ARITY_12 = PerfFlags.ARG_SMALL_ARITY_12,
        SKIP_ARGS_ON_NULL_RECEIVER = PerfFlags.SKIP_ARGS_ON_NULL_RECEIVER,
        SCOPE_POOL = PerfFlags.SCOPE_POOL,

        FIELD_PIC = PerfFlags.FIELD_PIC,
        METHOD_PIC = PerfFlags.METHOD_PIC,
        FIELD_PIC_SIZE_4 = PerfFlags.FIELD_PIC_SIZE_4,
        METHOD_PIC_SIZE_4 = PerfFlags.METHOD_PIC_SIZE_4,
        PIC_ADAPTIVE_2_TO_4 = PerfFlags.PIC_ADAPTIVE_2_TO_4,
        PIC_ADAPTIVE_METHODS_ONLY = PerfFlags.PIC_ADAPTIVE_METHODS_ONLY,
        PIC_ADAPTIVE_HEURISTIC = PerfFlags.PIC_ADAPTIVE_HEURISTIC,

        INDEX_PIC = PerfFlags.INDEX_PIC,
        INDEX_PIC_SIZE_4 = PerfFlags.INDEX_PIC_SIZE_4,

        PIC_DEBUG_COUNTERS = PerfFlags.PIC_DEBUG_COUNTERS,

        PRIMITIVE_FASTOPS = PerfFlags.PRIMITIVE_FASTOPS,
        RVAL_FASTPATH = PerfFlags.RVAL_FASTPATH,

        REGEX_CACHE = PerfFlags.REGEX_CACHE,
        RANGE_FAST_ITER = PerfFlags.RANGE_FAST_ITER,
    )

    fun restore(s: Snapshot) {
        PerfFlags.LOCAL_SLOT_PIC = s.LOCAL_SLOT_PIC
        PerfFlags.EMIT_FAST_LOCAL_REFS = s.EMIT_FAST_LOCAL_REFS

        PerfFlags.ARG_BUILDER = s.ARG_BUILDER
        PerfFlags.ARG_SMALL_ARITY_12 = s.ARG_SMALL_ARITY_12
        PerfFlags.SKIP_ARGS_ON_NULL_RECEIVER = s.SKIP_ARGS_ON_NULL_RECEIVER
        PerfFlags.SCOPE_POOL = s.SCOPE_POOL

        PerfFlags.FIELD_PIC = s.FIELD_PIC
        PerfFlags.METHOD_PIC = s.METHOD_PIC
        PerfFlags.FIELD_PIC_SIZE_4 = s.FIELD_PIC_SIZE_4
        PerfFlags.METHOD_PIC_SIZE_4 = s.METHOD_PIC_SIZE_4
        PerfFlags.PIC_ADAPTIVE_2_TO_4 = s.PIC_ADAPTIVE_2_TO_4
        PerfFlags.PIC_ADAPTIVE_METHODS_ONLY = s.PIC_ADAPTIVE_METHODS_ONLY
        PerfFlags.PIC_ADAPTIVE_HEURISTIC = s.PIC_ADAPTIVE_HEURISTIC

        PerfFlags.INDEX_PIC = s.INDEX_PIC
        PerfFlags.INDEX_PIC_SIZE_4 = s.INDEX_PIC_SIZE_4

        PerfFlags.PIC_DEBUG_COUNTERS = s.PIC_DEBUG_COUNTERS

        PerfFlags.PRIMITIVE_FASTOPS = s.PRIMITIVE_FASTOPS
        PerfFlags.RVAL_FASTPATH = s.RVAL_FASTPATH

        PerfFlags.REGEX_CACHE = s.REGEX_CACHE
        PerfFlags.RANGE_FAST_ITER = s.RANGE_FAST_ITER
    }

    enum class Preset { BASELINE, BENCH, BOOKS }

    /** Apply a preset and return a [Snapshot] of the previous state to allow restoring later. */
    fun apply(preset: Preset): Snapshot {
        val saved = snapshot()
        when (preset) {
            Preset.BASELINE -> applyBaseline()
            Preset.BENCH -> applyBench()
            Preset.BOOKS -> applyBooks()
        }
        return saved
    }

    private fun applyBaseline() {
        // Restore platform defaults. Note: INDEX_PIC follows FIELD_PIC parity by convention.
        PerfFlags.LOCAL_SLOT_PIC = PerfDefaults.LOCAL_SLOT_PIC
        PerfFlags.EMIT_FAST_LOCAL_REFS = PerfDefaults.EMIT_FAST_LOCAL_REFS

        PerfFlags.ARG_BUILDER = PerfDefaults.ARG_BUILDER
        PerfFlags.ARG_SMALL_ARITY_12 = PerfDefaults.ARG_SMALL_ARITY_12
        PerfFlags.SKIP_ARGS_ON_NULL_RECEIVER = PerfDefaults.SKIP_ARGS_ON_NULL_RECEIVER
        PerfFlags.SCOPE_POOL = PerfDefaults.SCOPE_POOL

        PerfFlags.FIELD_PIC = PerfDefaults.FIELD_PIC
        PerfFlags.METHOD_PIC = PerfDefaults.METHOD_PIC
        PerfFlags.FIELD_PIC_SIZE_4 = PerfDefaults.FIELD_PIC_SIZE_4
        PerfFlags.METHOD_PIC_SIZE_4 = PerfDefaults.METHOD_PIC_SIZE_4
        PerfFlags.PIC_ADAPTIVE_2_TO_4 = PerfDefaults.PIC_ADAPTIVE_2_TO_4
        PerfFlags.PIC_ADAPTIVE_METHODS_ONLY = PerfDefaults.PIC_ADAPTIVE_METHODS_ONLY
        PerfFlags.PIC_ADAPTIVE_HEURISTIC = PerfDefaults.PIC_ADAPTIVE_HEURISTIC

        PerfFlags.INDEX_PIC = PerfFlags.FIELD_PIC
        PerfFlags.INDEX_PIC_SIZE_4 = PerfDefaults.INDEX_PIC_SIZE_4

        PerfFlags.PIC_DEBUG_COUNTERS = PerfDefaults.PIC_DEBUG_COUNTERS

        PerfFlags.PRIMITIVE_FASTOPS = PerfDefaults.PRIMITIVE_FASTOPS
        PerfFlags.RVAL_FASTPATH = PerfDefaults.RVAL_FASTPATH

        PerfFlags.REGEX_CACHE = PerfDefaults.REGEX_CACHE
        PerfFlags.RANGE_FAST_ITER = PerfDefaults.RANGE_FAST_ITER
    }

    private fun applyBench() {
        // Expression-heavy micro-bench focus (JVM-first assumptions)
        PerfFlags.LOCAL_SLOT_PIC = true
        PerfFlags.EMIT_FAST_LOCAL_REFS = true

        PerfFlags.ARG_BUILDER = true
        PerfFlags.ARG_SMALL_ARITY_12 = false
        PerfFlags.SKIP_ARGS_ON_NULL_RECEIVER = true
        PerfFlags.SCOPE_POOL = true

        PerfFlags.FIELD_PIC = true
        PerfFlags.METHOD_PIC = true
        PerfFlags.FIELD_PIC_SIZE_4 = false
        PerfFlags.METHOD_PIC_SIZE_4 = false
        PerfFlags.PIC_ADAPTIVE_2_TO_4 = false
        PerfFlags.PIC_ADAPTIVE_METHODS_ONLY = false
        PerfFlags.PIC_ADAPTIVE_HEURISTIC = false

        PerfFlags.INDEX_PIC = true
        PerfFlags.INDEX_PIC_SIZE_4 = true

        PerfFlags.PIC_DEBUG_COUNTERS = false

        PerfFlags.PRIMITIVE_FASTOPS = true
        PerfFlags.RVAL_FASTPATH = true

        // Keep regex cache/platform setting; enable on JVM typically
        PerfFlags.REGEX_CACHE = PerfDefaults.REGEX_CACHE
        PerfFlags.RANGE_FAST_ITER = false
    }

    private fun applyBooks() {
        // Documentation/book workload focus based on profiling observations.
        // Favor simpler paths when hot expression paths are not dominant.
        PerfFlags.LOCAL_SLOT_PIC = PerfDefaults.LOCAL_SLOT_PIC
        PerfFlags.EMIT_FAST_LOCAL_REFS = PerfDefaults.EMIT_FAST_LOCAL_REFS

        PerfFlags.ARG_BUILDER = false
        PerfFlags.ARG_SMALL_ARITY_12 = false
        PerfFlags.SKIP_ARGS_ON_NULL_RECEIVER = true
        PerfFlags.SCOPE_POOL = false

        PerfFlags.FIELD_PIC = false
        PerfFlags.METHOD_PIC = false
        PerfFlags.FIELD_PIC_SIZE_4 = false
        PerfFlags.METHOD_PIC_SIZE_4 = false
        PerfFlags.PIC_ADAPTIVE_2_TO_4 = false
        PerfFlags.PIC_ADAPTIVE_METHODS_ONLY = false
        PerfFlags.PIC_ADAPTIVE_HEURISTIC = false

        PerfFlags.INDEX_PIC = false
        PerfFlags.INDEX_PIC_SIZE_4 = false

        PerfFlags.PIC_DEBUG_COUNTERS = false

        PerfFlags.PRIMITIVE_FASTOPS = false
        PerfFlags.RVAL_FASTPATH = false

        // Keep regex cache/platform default; ON on JVM usually helps string APIs in books
        PerfFlags.REGEX_CACHE = PerfDefaults.REGEX_CACHE
        PerfFlags.RANGE_FAST_ITER = false
    }
}
