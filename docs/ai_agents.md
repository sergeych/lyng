# Lyng instructions for AI agents

Before writing Lyng, read the [current language reference](ai_language_reference.md)
and [compact standard-library reference](ai_stdlib_reference.md). These are the
canonical implementation-oriented references; older specifications and proposals
must not override them. Discover them from <https://lynglang.com/llms.txt>.

Use the language reference for syntax, types, generic bounds, imports, and current
limitations. Use the stdlib reference to distinguish default globals from explicit
imports and optional host modules. Follow links to individual API pages when you
need more detail. Do not guess Kotlin/Java method names or assume optional modules
are installed. Check the host runtime version and validate uncertain constructs
with a small read-only script before changing user data.

For offline embedding, copy both compact references from the same committed Lyng
revision, retain their paths and commit ID, and split long results at section
boundaries to fit the host's tool output limit. Keep host-specific capability and
permission documentation alongside them. In Tetradion, call `agent.docsList()` or
`agent.docsSearch("query")`, then `agent.docs("reference.syntax")`,
`agent.docs("reference.flow")`, `agent.docs("reference.types")`,
`agent.docs("reference.modules")`, or `agent.docs("reference.stdlib")`.

The site publishes these Markdown files directly under `/docs/`, without requiring
JavaScript or browser automation.
