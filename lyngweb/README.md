### Lyng Web utilities (`:lyngweb`)

Reusable JS/Compose for Web utilities and UI pieces for Lyng-powered sites. The module is self-sufficient: adding it as a dependency is enough — no external CSS classes are required for its editor overlay to render correctly.

#### What’s inside

- `EditorWithOverlay` — a pure code editor Composable with a syntax-highlight overlay. It keeps a native `<textarea>` for input/caret while rendering highlighted HTML on top, staying in perfect glyph alignment. No built-in buttons or actions.
- HTML utilities for Markdown pipelines:
  - `ensureBootstrapCodeBlocks(html: String): String` — adds `class="code"` to `<pre>` blocks (for Bootstrap-like styling).
  - `highlightLyngHtml(html: String): String` — transforms Lyng code blocks inside HTML into highlighted spans using `hl-*` classes.
  - `htmlEscape(s: String): String` — HTML-escape utility used by the highlighter.
- Backward-compatible `net.sergeych.site.SiteHighlight.renderHtml(text)` that renders a highlighted string with the same `hl-*` classes used by the site.

All essential styles for the editor are injected inline; there is no dependency on external CSS class names (e.g., `editor-overlay`). You can still override visuals with your own CSS if desired.

---

#### Quick start

1) Add the dependency in your JS `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":lyngweb"))
}
```

2) Use the editor in a Compose HTML page:

```kotlin
@Composable
fun TryLyngSnippet() {
    var code by remember { mutableStateOf("""
        // Type Lyng code here
        import lyng.stdlib
        [1,2,3].map { it * 10 }
    """.trimIndent()) }

    fun runCode() { /* evaluate code in your Scope */ }

    Div({ classes("mb-3") }) {
        Div({ classes("form-label") }) { Text("Code") }
        EditorWithOverlay(
            code = code,
            setCode = { code = it },
            // Optionally handle keyboard shortcuts (e.g., Ctrl/Cmd+Enter to run):
            onKeyDown = { ev ->
                val ctrlOrMeta = ev.ctrlKey || ev.metaKey
                if (ctrlOrMeta && ev.key.lowercase() == "enter") {
                    ev.preventDefault()
                    runCode()
                }
            }
        )
    }

    // Your own action buttons
    // ...your own action buttons...
}
```

The editor provides:
- Tab insertion with configurable `tabSize` (default 4)
- Smart newline indentation (copies the leading spaces of the current line)
- Scroll sync and 1:1 glyph alignment between overlay and textarea
- Inline styles for overlay/textarea; no external CSS required

---

#### Highlight Lyng code inside Markdown HTML

If you render Markdown to HTML first (e.g., with `marked`), you can post-process it with `lyngweb` to highlight Lyng code blocks:

```kotlin
fun renderMarkdownLyng(mdHtml: String): String {
    // 1) ensure <pre> blocks have class="code"
    val withPre = ensureBootstrapCodeBlocks(mdHtml)
    // 2) highlight <code class="language-lyng"> blocks
    return highlightLyngHtml(withPre)
}
```

Lyng tokens are wrapped into spans with classes like `hl-kw`, `hl-id`, `hl-num`, `hl-cmt`, etc. You can style them as you wish, for example:

```css
.hl-kw { color: #6f42c1; font-weight: 600; }
.hl-id { color: #1f2328; }
.hl-num { color: #0a3069; }
.hl-str { color: #015b2f; }
.hl-cmt { color: #6a737d; font-style: italic; }
```

---

#### API Summary

- `@Composable fun EditorWithOverlay(code: String, setCode: (String) -> Unit, tabSize: Int = 4, onKeyDown: ((SyntheticKeyboardEvent) -> Unit)? = null)`
  - Pure editor, no actions. Wire your own buttons and shortcuts.
  - Self-contained styling, adjustable with your own CSS if desired.

- `fun ensureBootstrapCodeBlocks(html: String): String`
  - Adds `class="code"` to `<pre>` tags if not present.

- `fun highlightLyngHtml(html: String): String`
  - Highlights Lyng `<code class="language-lyng">...</code>` blocks inside the provided HTML.

- `fun htmlEscape(s: String): String`
  - Escapes special HTML characters.

- `object net.sergeych.site.SiteHighlight`
  - `fun renderHtml(text: String): String` — renders highlighted spans with `hl-*` classes; kept for compatibility with existing site code/tests.

---

#### Notes

- The editor does not ship default color styles for `hl-*` classes. Provide your own CSS to match your theme.
- If you want a minimal look without Bootstrap, the editor still works out of the box due to inline styles.
