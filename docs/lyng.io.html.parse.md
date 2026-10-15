# lyng.io.html.parse

Offline HTML5 parsing backed by the core [Ksoup](https://github.com/fleeksoft/ksoup)
parser (no network extensions). Installed from `lyngio`:

```kotlin
createHtmlParseModule(scope.importManager)
```

```lyng
import lyng.io.html.parse

val doc = Html.parse("<title>News</title><main><p>Hello &amp; welcome</p><a href='/next'>Next</a></main>", "https://example.com/")
println(doc.title)
println(doc.text())
for (raw in doc.select("a[href]")) {
    val link = raw as HtmlElement
    println(link.text() + " => " + link.absUrl("href"))
}
```

`Html.parse(source, baseUrl = "")` accepts documents, fragments and malformed HTML.
It returns `HtmlDocument`, with `title: String`, `text(): String` and
`select(css): List<HtmlElement>`. Elements have `text()`, `attr(name)`,
`absUrl(name)` and `select(css)`. Missing selections return an empty list;
missing attributes or unresolvable absolute URLs return an empty string.
Invalid CSS selectors raise an error. `<base href>` participates in URL resolution.
Use the final fetched URL as the base when available.

Text extraction decodes entities, normalizes whitespace, preserves block and
`br` boundaries as newlines, and excludes `script`, `style`, `template`, `noscript`
and `[hidden]` subtrees. Selection retains the original DOM. This is readable
text extraction, not CSS rendering or article detection; navigation and cookie
banners may remain. It does not preserve preformatted whitespace.

Parsing never fetches resources or executes JavaScript. `absUrl` only resolves
URLs; it does not filter schemes. Fetch through the host's authorized HTTP API,
check status and content type, and choose bounded excerpts to return to an AI.
This API parses HTML only; XML parsing is separate future work.

Canonical declarations: `lyngio/stdlib/lyng/io/html_parse.lyng`.
