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
