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

/*
 * Tests for link and image rewriting in rendered markdown HTML.
 */

import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLImageElement
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkRewriteTest {
    private fun makeContainer(html: String): HTMLDivElement {
        val div = document.createElement("div") as HTMLDivElement
        div.innerHTML = html.trimIndent()
        return div
    }

    @Test
    fun testRewriteAnchorsAndImagesUsingDocBasePath() {
        val html = """
            <div class="markdown-body">
              <p>
                <a id="a1" href="Iterator.md">iterator page</a>
                <a id="a2" href="Iterator.md#intro">iterator with frag</a>
                <a id="a3" href="#install">install section</a>
                <a id="a4" href="https://example.com">external</a>
                <a id="a5" href="img/p.png">asset</a>
              </p>
              <p>
                <img id="i1" src="images/pic.png" />
              </p>
            </div>
        """
        val root = makeContainer(html)

        val currentDoc = "docs/tutorial.md"
        val basePath = currentDoc.substringBeforeLast('/')

        // exercise rewrites
        rewriteImages(root, basePath)
        rewriteAnchors(root, basePath, currentDoc) { /* no-op for tests */ }

        // Validate anchors
        val a1 = root.querySelector("#a1") as HTMLAnchorElement
        assertEquals("#/docs/Iterator.md", a1.getAttribute("href"))

        val a2 = root.querySelector("#a2") as HTMLAnchorElement
        assertEquals("#/docs/Iterator.md#intro", a2.getAttribute("href"))

        val a3 = root.querySelector("#a3") as HTMLAnchorElement
        assertEquals("#/docs/tutorial.md#install", a3.getAttribute("href"))

        val a4 = root.querySelector("#a4") as HTMLAnchorElement
        // external should remain unchanged
        assertEquals("https://example.com", a4.getAttribute("href"))

        val a5 = root.querySelector("#a5") as HTMLAnchorElement
        // non-md relative assets should become relative to doc directory (no SPA hash)
        assertEquals("docs/img/p.png", a5.getAttribute("href"))

        // Validate image src
        val i1 = root.querySelector("#i1") as HTMLImageElement
        assertEquals("docs/images/pic.png", i1.getAttribute("src"))
    }
}
