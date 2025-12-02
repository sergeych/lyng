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
 * Lightweight documentation payload for symbols defined from Kotlin code (built-ins, stdlib, host bindings).
 *
 * The [summary] is optional; if not provided, it will be derived as the first non-empty line of [raw].
 * Simple tag lines like "@since 1.0" can be stored in [tags] when needed.
 */
data class DocString(
    val raw: String,
    val summary: String? = null,
    val tags: Map<String, List<String>> = emptyMap()
) {
    val effectiveSummary: String? by lazy {
        summary ?: raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    companion object {
        /** Convenience to create a [DocString] taking the first non-empty line as a summary. */
        fun of(text: String, tags: Map<String, List<String>> = emptyMap()): DocString = DocString(text, null, tags)
    }
}
