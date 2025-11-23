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

package net.sergeych.lyngweb

/**
 * Minimal HTML renderer for Lyng syntax highlighting, compatible with the site CSS.
 *
 * This object is kept in the legacy package `net.sergeych.site` to preserve
 * backward compatibility with existing imports and tests in dependent modules.
 * It renders spans with the `hl-*` classes used by the site (e.g., `hl-kw`,
 * `hl-id`, `hl-num`).
 */
object SiteHighlight {
    /**
     * Converts plain Lyng source [text] into HTML with `<span>` wrappers using
     * site-compatible `hl-*` classes. This uses the merged highlighter that
     * overlays declaration/parameter roles on top of token highlighting so
     * functions, variables, classes, and params get distinct styles.
     */
    fun renderHtml(text: String): String = applyLyngHighlightToText(text)

    /**
     * Suspend variant that uses Mini-AST for precise declaration/param/type ranges
     * when possible, with a graceful fallback to token+overlay highlighter.
     */
    suspend fun renderHtmlAsync(text: String): String = applyLyngHighlightToTextAst(text)
}