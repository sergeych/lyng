package net.sergeych.lyng.io.html

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.pacman.ImportManager
import kotlin.test.*

class LyngHtmlParseModuleTest {
    private suspend fun execute(source: String) {
        val scope = Script.newScope()
        createHtmlParseModule(scope)
        Compiler.compile(Source("html-parse-test", source.trimIndent()), scope.importManager).execute(scope)
    }

    @Test fun registrationIsIdempotent() {
        val manager = ImportManager()
        assertTrue(createHtmlParseModule(manager))
        assertFalse(createHtmlParseModule(manager))
    }

    @Test fun parsesMalformedHtmlAndExtractsTextWithoutChangingDom() = runTest {
        execute("""
            import lyng.io.html.parse
            val doc = Html.parse("<title>A &amp; B</title><style>bad css</style><body><h1>Heading</h1><p>Hello <b>world</b> &amp; &#65;<br>Next<p>Last<script>bad script</script><div hidden>secret</div><template>bad template</template><noscript>bad fallback</noscript>")
            assert(doc.title == "A & B")
            assert(doc.text() == "Heading\nHello world & A\nNext\nLast")
            assert(doc.select("p").size == 2)
            assert(doc.select("script").size == 1)
            assert(doc.select("#missing").size == 0)
            assert(doc.text() == doc.text())
        """)
    }

    @Test fun selectorsAttributesBaseAndNestedSelection() = runTest {
        execute("""
            import lyng.io.html.parse
            val doc = Html.parse("<base href='/docs/'><article><a class='ref' href='intro?a=1&amp;b=2'>Read &amp; learn</a></article>", "https://example.com/start")
            val articles = doc.select("article")
            val article = articles[0] as HtmlElement
            val links = article.select("a.ref[href]")
            val link = links[0] as HtmlElement
            assert(link.text() == "Read & learn")
            assert(link.attr("href") == "intro?a=1&b=2")
            assert(link.absUrl("href") == "https://example.com/docs/intro?a=1&b=2")
            assert(link.attr("missing") == "")
            assert(link.absUrl("missing") == "")
            val fragment = Html.parse("<p>One</p><p>Two</p>")
            assert(fragment.text() == "One\nTwo")
            assert(fragment.title == "")
            assert(Html.parse("").text() == "")
        """)
    }

    @Test fun badSelectorFailsClearly() = runTest {
        assertFails { execute("import lyng.io.html.parse\nHtml.parse(\"<p>Hi\").select(\"[\")") }
    }
}
