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
package net.sergeych.lyng.format

/**
 * Formatting configuration for Lyng source code.
 * Defaults are Kotlin-like.
 */
data class LyngFormatConfig(
    val indentSize: Int = 4,
    val useTabs: Boolean = false,
    val continuationIndentSize: Int = 4,
    val maxLineLength: Int = 120,
    val applySpacing: Boolean = false,
    val applyWrapping: Boolean = false,
    val trailingComma: Boolean = false,
) {
    init {
        require(indentSize > 0) { "indentSize must be > 0" }
        require(continuationIndentSize > 0) { "continuationIndentSize must be > 0" }
        require(maxLineLength > 0) { "maxLineLength must be > 0" }
    }
}
