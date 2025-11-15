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

actual object PerfDefaults {
    actual val LOCAL_SLOT_PIC: Boolean = true
    actual val EMIT_FAST_LOCAL_REFS: Boolean = true

    actual val ARG_BUILDER: Boolean = true
    actual val SKIP_ARGS_ON_NULL_RECEIVER: Boolean = true
    actual val SCOPE_POOL: Boolean = true

    actual val FIELD_PIC: Boolean = true
    actual val METHOD_PIC: Boolean = true
    actual val FIELD_PIC_SIZE_4: Boolean = false
    actual val METHOD_PIC_SIZE_4: Boolean = false
    actual val PIC_ADAPTIVE_2_TO_4: Boolean = false
    actual val PIC_ADAPTIVE_METHODS_ONLY: Boolean = false
    actual val PIC_ADAPTIVE_HEURISTIC: Boolean = false

    actual val PIC_DEBUG_COUNTERS: Boolean = false

    actual val PRIMITIVE_FASTOPS: Boolean = true
    actual val RVAL_FASTPATH: Boolean = true

    // Regex caching (JVM-first): enabled by default on JVM
    actual val REGEX_CACHE: Boolean = true

    // Extended small-arity calls 9..12 (experimental; keep OFF by default)
    actual val ARG_SMALL_ARITY_12: Boolean = false

    // Index PIC size (beneficial on JVM in A/B): enable size=4 by default
    actual val INDEX_PIC_SIZE_4: Boolean = true

    // Range fast-iteration (experimental; OFF by default)
    actual val RANGE_FAST_ITER: Boolean = false
}