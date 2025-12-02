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

package net.sergeych.lyng.idea.docs

import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet

/**
 * Rich Markdown renderer for the IDEA Quick Docs using Flexmark.
 *
 * - Supports fenced code blocks (with language class "language-xyz")
 * - Autolinks, tables, strikethrough
 * - Converts soft breaks to <br/>
 * - Tiny in-memory cache to avoid repeated parsing of the same doc blocks
 */
object MarkdownRenderer {
    private val options = MutableDataSet().apply {
        set(Parser.EXTENSIONS, listOf(
            AutolinkExtension.create(),
            TablesExtension.create(),
            StrikethroughExtension.create(),
        ))
        // Add CSS class for code fences like ```lyng → class="language-lyng"
        set(HtmlRenderer.FENCED_CODE_LANGUAGE_CLASS_PREFIX, "language-")
        // Treat single newlines as a space (soft break) so consecutive lines remain one paragraph.
        // Real paragraph breaks require an empty line, hard breaks still work via Markdown (two spaces + \n).
        set(HtmlRenderer.SOFT_BREAK, " ")
    }

    private val parser: Parser = Parser.builder(options).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder(options).build()

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 256
    }

    fun render(markdown: String): String {
        // Fast path: cache
        synchronized(cache) { cache[markdown]?.let { return it } }
        val node = parser.parse(markdown)
        val html = renderer.render(node)
        synchronized(cache) { cache[markdown] = html }
        return html
    }
}
