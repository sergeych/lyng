/*
 * Tests for routing helpers, anchor rewriting, and TOC building
 */

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.*

class RouteAndDomRewriteTest {

    @Test
    fun testAnchorFromHash() {
        assertNull(anchorFromHash("#"))
        assertNull(anchorFromHash("") )
        assertNull(anchorFromHash("#/docs/Iterator.md"))
        assertEquals("section-1", anchorFromHash("#/docs/Iterator.md#section-1"))
        assertEquals("a", anchorFromHash("#/docs/x/y.md#a"))
    }

    @Test
    fun testNormalizePath() {
        assertEquals("docs/a/b.md", normalizePath("docs/./a/../a/b.md"))
        assertEquals("docs/a.md", normalizePath("docs/x/../a.md"))
        assertEquals("a/b", normalizePath("a//b"))
    }

    @Test
    fun testRewriteAnchors_forIntraPageAndMdLinks() {
        val root = document.createElement("div") as HTMLElement
        root.innerHTML = """
            <p>
              <a id=one href="#local">Local</a>
              <a id=two href="Sibling.md#sec">Sibling</a>
              <a id=three href="image.png">Img</a>
            </p>
        """.trimIndent()

        val basePath = "docs" // current doc in docs root
        val currentDoc = "docs/Iterator.md"
        rewriteAnchors(root, basePath, currentDoc) { /* noop for test */ }

        val one = root.querySelector("#one") as HTMLElement
        assertEquals("#/${currentDoc}#local", one.getAttribute("href"))

        val two = root.querySelector("#two") as HTMLElement
        assertEquals("#/docs/Sibling.md#sec", two.getAttribute("href"))

        val three = root.querySelector("#three") as HTMLElement
        // non-md stays relative to base path
        assertEquals("docs/image.png", three.getAttribute("href"))
    }

    @Test
    fun testBuildToc_assignsIdsAndLevels() {
        val root = document.createElement("div") as HTMLElement
        root.innerHTML = """
            <h1>Title</h1>
            <h2>Section</h2>
            <h2>Section</h2>
            <h3>Sub</h3>
        """.trimIndent()

        val toc = buildToc(root)
        assertTrue(toc.isNotEmpty(), "TOC should not be empty")
        // Ensure we produced unique IDs for duplicate headings
        val ids = toc.map { it.id }
        assertEquals(ids.toSet().size, ids.size)

        // Also verify IDs are actually set on DOM
        ids.forEach { id ->
            val el = root.ownerDocument?.getElementById(id) ?: root.querySelector("#${id}")
            assertNotNull(el, "Heading with id $id should be present in DOM")
        }
    }
}
