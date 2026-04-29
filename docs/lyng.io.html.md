# lyng.io.html

`lyng.io.html` provides a pure Lyng HTML builder DSL. It uses Lyng context
receiver extensions, so text can be appended with `+"text"` inside tag blocks
without global builder state.

Host code installs the package from `lyngio` with `createHtmlModule(...)`:

```kotlin
val scope = Script.newScope()
createHtmlModule(scope.importManager)
```

Lyng code can then import it:

```lyng
import lyng.io.html

val page = html {
    head {
        title { +"Demo" }
    }
    body {
        nav {
            a(href: "/") { +"Home" }
        }
        h3 { +"Heading 3" }
        p {
            attr("data-id", 123)
            +"Text is escaped: <safe>"
        }
        img(src: "/logo.png", alt: "Logo")
    }
}
```

`html { ... }` returns a `String` beginning with `<!doctype html>`.

## Escaping

Text appended with unary `+` is HTML-escaped:

```lyng
html {
    body {
        p { +"Text & <more>" }
    }
}
```

produces:

```html
<!doctype html><html><body><p>Text &amp; &lt;more&gt;</p></body></html>
```

Attribute values are escaped with HTML attribute rules:

```lyng
p {
    attr("data-x", "\"quoted\" & <tag>")
    +"content"
}
```

Use `raw(...)` only for trusted markup:

```lyng
div {
    raw("<span>already escaped or trusted</span>")
}
```

## Tag Helpers

Current tag helpers cover common structural tags (`head`, `body`, `main`,
`section`, `article`, `header`, `footer`, `nav`, `div`, `span`, `p`), headings
(`h1` through `h6`), lists (`ul`, `ol`, `li`), and text/code tags (`strong`,
`em`, `code`, `pre`, `script`, `style`).

```lyng
body {
    main {
        section {
            h2 { +"News" }
            p { +"First item" }
        }
    }
}
```

Common void tags are also available: `meta`, `link`, `img`, `br`, and `input`.

```lyng
head {
    meta { attr("charset", "utf-8") }
    link {
        attr("rel", "stylesheet")
        attr("href", "/site.css")
    }
}
```

## Attributes

Use `attr(name, value)` inside a tag block to set an escaped attribute value.
`id(...)` and `classes(...)` are small aliases:

```lyng
div {
    id("root")
    classes("app shell")
}
```

Use `flag(name)` for boolean attributes:

```lyng
input {
    attr("type", "checkbox")
    flag("checked")
}
```

## Convenience Helpers

Convenience helpers include `metaCharset()`, `stylesheet(href)`,
`a(href) { ... }`, `img(src, alt)`, and `input(type, name, value)`.

```lyng
head {
    metaCharset()
    stylesheet("/site.css")
}

body {
    nav {
        a(href: "/home") { +"Home" }
    }
    img(src: "/logo.png", alt: "Logo & mark")
    input(type: "hidden", name: "token", value: "abc")
}
```

## Generic Elements

Use `tag(name) { ... }` and `voidTag(name) { ... }` for elements that do not
have dedicated helpers yet:

```lyng
body {
    tag("custom-element") {
        flag("hidden")
        +"Secret"
    }
    voidTag("source") {
        attr("srcset", "/image.webp")
        attr("type", "image/webp")
    }
}
```

These helpers are intentionally simple escape hatches. Prefer a dedicated helper
when one exists because it can encode safer defaults and clearer parameter names.
