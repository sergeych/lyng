/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.util.LyngAstManager
import net.sergeych.lyng.idea.util.TextCtx
import net.sergeych.lyng.miniast.*

/**
 * Quick Docs backed by MiniAst: when caret is on an identifier that corresponds
 * to a declaration name or parameter, render a simple HTML with kind, signature,
 * and doc summary if present.
 */
class LyngDocumentationProvider : AbstractDocumentationProvider() {
    private val log = Logger.getInstance(LyngDocumentationProvider::class.java)
    // Toggle to trace inheritance-based resolutions in Quick Docs. Keep false for normal use.
    private val DEBUG_INHERITANCE = false
    // Global Quick Doc debug toggle (OFF by default). When false, [LYNG_DEBUG] logs are suppressed.
    private val DEBUG_LOG = false
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        // Try load external docs registrars (e.g., lyngio) if present on classpath
        ensureExternalDocsRegistered()
        // Ensure stdlib Obj*-defined docs (e.g., String methods via ObjString.addFnDoc) are initialized
        try {
            net.sergeych.lyng.miniast.StdlibDocsBootstrap.ensure()
        } catch (_: Throwable) {
            // best-effort; absence must not break Quick Doc
        }
        if (element == null) return null
        val file: PsiFile = element.containingFile ?: return null
        val document: Document = file.viewProvider.document ?: return null
        val text = document.text

        // Determine caret/lookup offset from the element range
        val offset = originalElement?.textRange?.startOffset ?: element.textRange.startOffset
        val idRange = TextCtx.wordRangeAt(text, offset) ?: run {
            if (DEBUG_LOG) log.info("[LYNG_DEBUG] QuickDoc: no word at offset=$offset in ${file.name}")
            return null
        }
        if (idRange.isEmpty) return null
        val ident = text.substring(idRange.startOffset, idRange.endOffset)
        if (DEBUG_LOG) log.info("[LYNG_DEBUG] QuickDoc: ident='$ident' at ${idRange.startOffset}..${idRange.endOffset} in ${file.name}")

        // 1. Get merged mini-AST from Manager (handles local + .lyng.d merged declarations)
        val mini = LyngAstManager.getMiniAst(file) ?: return null
        val miniSource = mini.range.start.source

        // Try resolve to: function param at position, function/class/val declaration at position
        // 1) Use unified declaration detection
        DocLookupUtils.findDeclarationAt(mini, offset, ident)?.let { (name, kind) ->
            if (DEBUG_LOG) log.info("[LYNG_DEBUG] QuickDoc: matched declaration '$name' kind=$kind")
            // Find the actual declaration object to render
            mini.declarations.forEach { d ->
                if (d.name == name) {
                    val s: Int = miniSource.offsetOf(d.nameStart)
                    if (s <= offset && s + d.name.length > offset) {
                        return renderDeclDoc(d)
                    }
                }
                // Handle members if it was a member
                if (d is MiniClassDecl) {
                    d.members.forEach { m ->
                        if (m.name == name) {
                            val s: Int = miniSource.offsetOf(m.nameStart)
                            if (s <= offset && s + m.name.length > offset) {
                                return when (m) {
                                    is MiniMemberFunDecl -> renderMemberFunDoc(d.name, m)
                                    is MiniMemberValDecl -> renderMemberValDoc(d.name, m)
                                    else -> null
                                }
                            }
                        }
                    }
                    d.ctorFields.forEach { cf ->
                        if (cf.name == name) {
                            val s: Int = miniSource.offsetOf(cf.nameStart)
                            if (s <= offset && s + cf.name.length > offset) {
                                // Render as a member val
                                val mv = MiniMemberValDecl(
                                    range = MiniRange(cf.nameStart, cf.nameStart), // dummy
                                    name = cf.name,
                                    mutable = cf.mutable,
                                    type = cf.type,
                                    doc = null,
                                    nameStart = cf.nameStart
                                )
                                return renderMemberValDoc(d.name, mv)
                            }
                        }
                    }
                    d.classFields.forEach { cf ->
                        if (cf.name == name) {
                            val s: Int = miniSource.offsetOf(cf.nameStart)
                            if (s <= offset && s + cf.name.length > offset) {
                                // Render as a member val
                                val mv = MiniMemberValDecl(
                                    range = MiniRange(cf.nameStart, cf.nameStart), // dummy
                                    name = cf.name,
                                    mutable = cf.mutable,
                                    type = cf.type,
                                    doc = null,
                                    nameStart = cf.nameStart
                                )
                                return renderMemberValDoc(d.name, mv)
                            }
                        }
                    }
                }
                if (d is MiniEnumDecl) {
                    if (d.entries.contains(name)) {
                        val s: Int = miniSource.offsetOf(d.range.start)
                        val e: Int = miniSource.offsetOf(d.range.end)
                        if (offset >= s && offset <= e) {
                            // For enum constant, we don't have detailed docs in MiniAst yet, but we can render a title
                            return "<div class='doc-title'>enum constant ${d.name}.${name}</div>"
                        }
                    }
                }
            }
            // Check parameters
            mini.declarations.filterIsInstance<MiniFunDecl>().forEach { fn ->
                fn.params.forEach { p ->
                    if (p.name == name) {
                        val s: Int = miniSource.offsetOf(p.nameStart)
                        if (s <= offset && s + p.name.length > offset) {
                            return renderParamDoc(fn, p)
                        }
                    }
                }
            }
        }

        // 3) usages in current file via Binder (resolves local variables, parameters, and classes)
        try {
            val binding = net.sergeych.lyng.binding.Binder.bind(text, mini)
            val ref = binding.references.firstOrNull { offset in it.start until it.end }
            if (ref != null) {
                val sym = binding.symbols.firstOrNull { it.id == ref.symbolId }
                if (sym != null) {
                    // Find local declaration that matches this symbol
                    var dsFound: MiniDecl? = null
                    mini.declarations.forEach { decl ->
                        if (decl.name == sym.name) {
                            val sOffset: Int = miniSource.offsetOf(decl.nameStart)
                            if (sOffset == sym.declStart) {
                                dsFound = decl
                            }
                        }
                    }
                    if (dsFound != null) return renderDeclDoc(dsFound)

                    // Check parameters
                    mini.declarations.filterIsInstance<MiniFunDecl>().forEach { fn ->
                        fn.params.forEach { p ->
                            if (p.name == sym.name) {
                                val sOffset: Int = miniSource.offsetOf(p.nameStart)
                                if (sOffset == sym.declStart) {
                                    return renderParamDoc(fn, p)
                                }
                            }
                        }
                    }

                    // Check class members (fields/functions)
                    mini.declarations.filterIsInstance<MiniClassDecl>().forEach { cls ->
                        cls.members.forEach { m ->
                            if (m.name == sym.name) {
                                val sOffset: Int = miniSource.offsetOf(m.nameStart)
                                if (sOffset == sym.declStart) {
                                    return when (m) {
                                        is MiniMemberFunDecl -> renderMemberFunDoc(cls.name, m)
                                        is MiniMemberValDecl -> renderMemberValDoc(cls.name, m)
                                        else -> null
                                    }
                                }
                            }
                        }
                        cls.ctorFields.forEach { cf ->
                            if (cf.name == sym.name) {
                                val sOffset: Int = miniSource.offsetOf(cf.nameStart)
                                if (sOffset == sym.declStart) {
                                    // Render as a member val
                                    val mv = MiniMemberValDecl(
                                        range = MiniRange(cf.nameStart, cf.nameStart), // dummy
                                        name = cf.name,
                                        mutable = cf.mutable,
                                        type = cf.type,
                                        doc = null,
                                        nameStart = cf.nameStart
                                    )
                                    return renderMemberValDoc(cls.name, mv)
                                }
                            }
                        }
                        cls.classFields.forEach { cf ->
                            if (cf.name == sym.name) {
                                val sOffset: Int = miniSource.offsetOf(cf.nameStart)
                                if (sOffset == sym.declStart) {
                                    // Render as a member val
                                    val mv = MiniMemberValDecl(
                                        range = MiniRange(cf.nameStart, cf.nameStart), // dummy
                                        name = cf.name,
                                        mutable = cf.mutable,
                                        type = cf.type,
                                        doc = null,
                                        nameStart = cf.nameStart
                                    )
                                    return renderMemberValDoc(cls.name, mv)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            if (DEBUG_LOG) log.warn("[LYNG_DEBUG] QuickDoc: local binder resolution failed: ${e.message}")
        }
        // 4) Member-context resolution first (dot immediately before identifier): handle literals and calls
        run {
            val dotPos = TextCtx.findDotLeft(text, idRange.startOffset)
                ?: TextCtx.findDotLeft(text, offset)
            if (dotPos != null) {
                // Build imported modules (MiniAst-derived if available, else lenient from text) and ensure stdlib is present
                val importedModules = DocLookupUtils.canonicalImportedModules(mini, text)

                // Try literal and call-based receiver inference around the dot
                val i = TextCtx.prevNonWs(text, dotPos - 1)
                val className: String? = when {
                    i >= 0 && text[i] == '"' -> "String"
                    i >= 0 && text[i] == ']' -> "List"
                    i >= 0 && text[i] == '}' -> "Dict"
                    i >= 0 && text[i] == ')' -> {
                        // Parenthesized expression: walk back to matching '(' and inspect the inner expression
                        var j = i - 1
                        var depth = 0
                        while (j >= 0) {
                            when (text[j]) {
                                ')' -> depth++
                                '(' -> if (depth == 0) break else depth--
                            }
                            j--
                        }
                        if (j >= 0 && text[j] == '(') {
                            val innerS = (j + 1).coerceAtLeast(0)
                            val innerE = i.coerceAtMost(text.length)
                            if (innerS < innerE) {
                                val inner = text.substring(innerS, innerE).trim()
                                when {
                                    inner.startsWith('"') && inner.endsWith('"') -> "String"
                                    inner.startsWith('[') && inner.endsWith(']') -> "List"
                                    inner.startsWith('{') && inner.endsWith('}') -> "Dict"
                                    else -> null
                                }
                            } else null
                        } else null
                    }
                    else -> {
                        DocLookupUtils.guessReceiverClassViaMini(mini, text, dotPos, importedModules)
                            ?: DocLookupUtils.guessClassFromCallBefore(text, dotPos, importedModules, mini)
                            ?: run {
                                // handle this@Type or as Type
                                val i2 = TextCtx.prevNonWs(text, dotPos - 1)
                                if (i2 >= 0) {
                                    val identRange = TextCtx.wordRangeAt(text, i2 + 1)
                                    if (identRange != null) {
                                        val id = text.substring(identRange.startOffset, identRange.endOffset)
                                        val k = TextCtx.prevNonWs(text, identRange.startOffset - 1)
                                        if (k >= 1 && text[k] == 's' && text[k - 1] == 'a' && (k - 1 == 0 || !text[k - 2].isLetterOrDigit())) {
                                            id
                                        } else if (k >= 0 && text[k] == '@') {
                                            val k2 = TextCtx.prevNonWs(text, k - 1)
                                            if (k2 >= 3 && text.substring(k2 - 3, k2 + 1) == "this") id else null
                                        } else null
                                    } else null
                                } else null
                            }
                    }
                }
                if (DEBUG_LOG) log.info("[LYNG_DEBUG] QuickDoc: memberCtx dotPos=${dotPos} chBeforeDot='${if (dotPos > 0) text[dotPos - 1] else ' '}' classGuess=${className} imports=${importedModules}")
                if (className != null) {
                    DocLookupUtils.resolveMemberWithInheritance(importedModules, className, ident, mini)?.let { (owner, member) ->
                        if (DEBUG_INHERITANCE) log.info("[LYNG_DEBUG] QuickDoc: literal/call '$ident' resolved to $owner.${member.name}")
                        return when (member) {
                            is MiniMemberFunDecl -> renderMemberFunDoc(owner, member)
                            is MiniMemberValDecl -> renderMemberValDoc(owner, member)
                            is MiniInitDecl -> null
                        }
                    }
                    log.info("[LYNG_DEBUG] QuickDoc: resolve failed for ${className}.${ident}")
                }
            }
        }

        // 4) As a fallback, if the caret is on an identifier text that matches any declaration name, show that
        mini.declarations.firstOrNull { it.name == ident }?.let {
            log.info("[LYNG_DEBUG] QuickDoc: fallback by name '${it.name}' kind=${it::class.simpleName}")
            return renderDeclDoc(it)
        }

        // 4) Consult BuiltinDocRegistry for imported modules (top-level and class members)
        // Canonicalize import names using ImportManager, as users may write shortened names (e.g., "io.fs")
        var importedModules = DocLookupUtils.canonicalImportedModules(mini, text)
        // Always include stdlib as a fallback context
        if (!importedModules.contains("lyng.stdlib")) importedModules = importedModules + "lyng.stdlib"
        // 4a) try top-level decls
        importedModules.forEach { mod ->
            val docs = BuiltinDocRegistry.docsForModule(mod)
            val matches = docs.filterIsInstance<MiniFunDecl>().filter { it.name == ident }
            if (matches.isNotEmpty()) {
                // Prefer overload by arity when caret is in a call position; otherwise show first
                val arity = callArity(text, idRange.endOffset)
                val chosen = arity?.let { a -> matches.firstOrNull { it.params.size == a } } ?: matches.first()
                // If multiple and none matched arity, consider showing an overloads list
                if (arity != null && chosen.params.size != arity && matches.size > 1) {
                    return renderOverloads(ident, matches)
                }
                return renderDeclDoc(chosen)
            }
            // Also allow values/consts
            docs.filterIsInstance<MiniValDecl>().firstOrNull { it.name == ident }?.let { return renderDeclDoc(it) }
            // And classes/enums
            docs.filterIsInstance<MiniClassDecl>().firstOrNull { it.name == ident }?.let { return renderDeclDoc(it) }
            docs.filterIsInstance<MiniEnumDecl>().firstOrNull { it.name == ident }?.let { return renderDeclDoc(it) }
        }
        // Defensive fallback: if nothing found and it's a well-known stdlib function, render minimal inline docs
        if (ident == "println" || ident == "print") {
            val fallback = if (ident == "println")
                "Print values to the standard output and append a newline. Accepts any number of arguments." else
                "Print values to the standard output without a trailing newline. Accepts any number of arguments."
            val title = "function $ident(values)"
            return "<div class='doc-title'>${htmlEscape(title)}</div>" + styledMarkdown(htmlEscape(fallback))
        }
        // 4b) try class members like ClassName.member with inheritance fallback
        val lhs = previousWordBefore(text, idRange.startOffset)
        if (lhs != null && hasDotBetween(text, lhs.endOffset, idRange.startOffset)) {
            val className = text.substring(lhs.startOffset, lhs.endOffset)
            DocLookupUtils.resolveMemberWithInheritance(importedModules, className, ident, mini)?.let { (owner, member) ->
                if (DEBUG_INHERITANCE) log.info("[LYNG_DEBUG] Inheritance resolved $className.$ident to $owner.${member.name}")
                return when (member) {
                    is MiniMemberFunDecl -> renderMemberFunDoc(owner, member)
                    is MiniMemberValDecl -> renderMemberValDoc(owner, member)
                    is MiniInitDecl -> null
                }
            }
        } else {
            // Heuristics when LHS is not an identifier (literals or call results):
            //  - List literal like [..].member → assume class List
            //  - Otherwise, try to find a unique class across imported modules that defines this member
            val dotPos = findDotLeft(text, idRange.startOffset)
            if (dotPos != null) {
                val guessed = when {
                    looksLikeListLiteralBefore(text, dotPos) -> "List"
                    else -> DocLookupUtils.guessClassFromCallBefore(text, dotPos, importedModules, mini)
                }
                if (guessed != null) {
                    DocLookupUtils.resolveMemberWithInheritance(importedModules, guessed, ident, mini)?.let { (owner, member) ->
                        if (DEBUG_INHERITANCE) log.info("[LYNG_DEBUG] Heuristic '$guessed.$ident' resolved via inheritance to $owner.${member.name}")
                        return when (member) {
                            is MiniMemberFunDecl -> renderMemberFunDoc(owner, member)
                            is MiniMemberValDecl -> renderMemberValDoc(owner, member)
                            is MiniInitDecl -> null
                        }
                    }
                } else {
                    // Extra fallback: try a small set of known receiver classes (covers literals when guess failed)
                    run {
                        val candidates = listOf("String", "Iterable", "Iterator", "List", "Collection", "Array", "Dict", "Regex")
                        for (c in candidates) {
                            DocLookupUtils.resolveMemberWithInheritance(importedModules, c, ident, mini)?.let { (owner, member) ->
                                if (DEBUG_INHERITANCE) log.info("[LYNG_DEBUG] Candidate '$c.$ident' resolved via inheritance to $owner.${member.name}")
                                return when (member) {
                                    is MiniMemberFunDecl -> renderMemberFunDoc(owner, member)
                                    is MiniMemberValDecl -> renderMemberValDoc(owner, member)
                                    is MiniInitDecl -> null
                                }
                            }
                        }
                    }
                    // As a last resort try aggregated String members (extensions from stdlib text)
                    run {
                        val classes = DocLookupUtils.aggregateClasses(importedModules, mini)
                        val stringCls = classes["String"]
                        val m = stringCls?.members?.firstOrNull { it.name == ident }
                        if (m != null) {
                            if (DEBUG_INHERITANCE) log.info("[LYNG_DEBUG] Aggregated fallback resolved String.$ident")
                            return when (m) {
                                is MiniMemberFunDecl -> renderMemberFunDoc("String", m)
                                is MiniMemberValDecl -> renderMemberValDoc("String", m)
                                is MiniInitDecl -> null
                            }
                        }
                    }
                    // Search across classes; prefer Iterable, then Iterator, then List for common ops
                    DocLookupUtils.findMemberAcrossClasses(importedModules, ident, mini)?.let { (owner, member) ->
                        if (DEBUG_INHERITANCE) log.info("[LYNG_DEBUG] Cross-class '$ident' resolved to $owner.${member.name}")
                        return when (member) {
                            is MiniMemberFunDecl -> renderMemberFunDoc(owner, member)
                            is MiniMemberValDecl -> renderMemberValDoc(owner, member)
                            is MiniInitDecl -> null
                        }
                    }
                }
            }
        }

        if (DEBUG_LOG) log.info("[LYNG_DEBUG] QuickDoc: nothing found for ident='$ident'")
        return null
    }

    private val externalDocsLoaded: Boolean by lazy { tryLoadExternalDocs() }

    private fun ensureExternalDocsRegistered() { @Suppress("UNUSED_EXPRESSION") externalDocsLoaded }

    private fun tryLoadExternalDocs(): Boolean {
        return try {
            // Try known registrars; ignore failures if module is absent
            val cls = Class.forName("net.sergeych.lyngio.docs.FsBuiltinDocs")
            val m = cls.getMethod("ensure")
            m.invoke(null)
            log.info("[LYNG_DEBUG] QuickDoc: external docs loaded: net.sergeych.lyngio.docs.FsBuiltinDocs.ensure() OK")
            true
        } catch (_: Throwable) {
            // Seed a minimal plugin-local fallback so Path docs still work without lyngio
            val seeded = try {
                FsDocsFallback.ensureOnce()
            } catch (_: Throwable) { false }
            if (seeded) {
                log.info("[LYNG_DEBUG] QuickDoc: external docs NOT found; seeded plugin fallback for lyng.io.fs")
            } else {
                log.info("[LYNG_DEBUG] QuickDoc: external docs NOT found (lyngio absent on classpath)")
            }
            seeded
        }
    }

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int
    ): PsiElement? {
        // Ensure our provider gets a chance for Lyng files regardless of PSI sophistication
        if (file.language != LyngLanguage) return null
        return contextElement ?: file.findElementAt(targetOffset)
    }

    private fun renderDeclDoc(d: MiniDecl): String {
        val title = when (d) {
            is MiniFunDecl -> "function ${d.name}${signatureOf(d)}"
            is MiniClassDecl -> "class ${d.name}"
            is MiniEnumDecl -> "enum ${d.name} { ${d.entries.joinToString(", ")} }"
            is MiniValDecl -> if (d.mutable) "var ${d.name}${typeOf(d.type)}" else "val ${d.name}${typeOf(d.type)}"
        }
        // Show full detailed documentation, not just the summary
        val raw = d.doc?.raw
        val doc: String? = if (raw.isNullOrBlank()) null else MarkdownRenderer.render(raw)
        val sb = StringBuilder()
        sb.append("<div class='doc-title'>").append(htmlEscape(title)).append("</div>")
        if (!doc.isNullOrBlank()) sb.append(styledMarkdown(doc))
        return sb.toString()
    }

    private fun renderParamDoc(fn: MiniFunDecl, p: MiniParam): String {
        val title = "parameter ${p.name}${typeOf(p.type)} in ${fn.name}${signatureOf(fn)}"
        return "<div class='doc-title'>${htmlEscape(title)}</div>"
    }

    private fun renderMemberFunDoc(className: String, m: MiniMemberFunDecl): String {
        val params = m.params.joinToString(", ") { p ->
            val ts = typeOf(p.type)
            if (ts.isNotBlank()) "${p.name}${ts}" else p.name
        }
        val ret = typeOf(m.returnType)
        val staticStr = if (m.isStatic) "static " else ""
        val title = "${staticStr}method $className.${m.name}(${params})${ret}"
        val raw = m.doc?.raw
        val doc: String? = if (raw.isNullOrBlank()) null else MarkdownRenderer.render(raw)
        val sb = StringBuilder()
        sb.append("<div class='doc-title'>").append(htmlEscape(title)).append("</div>")
        if (!doc.isNullOrBlank()) sb.append(styledMarkdown(doc))
        return sb.toString()
    }

    private fun renderMemberValDoc(className: String, m: MiniMemberValDecl): String {
        val ts = typeOf(m.type)
        val kind = if (m.mutable) "var" else "val"
        val staticStr = if (m.isStatic) "static " else ""
        val title = "${staticStr}${kind} $className.${m.name}${ts}"
        val raw = m.doc?.raw
        val doc: String? = if (raw.isNullOrBlank()) null else MarkdownRenderer.render(raw)
        val sb = StringBuilder()
        sb.append("<div class='doc-title'>").append(htmlEscape(title)).append("</div>")
        if (!doc.isNullOrBlank()) sb.append(styledMarkdown(doc))
        return sb.toString()
    }

    private fun typeOf(t: MiniTypeRef?): String = when (t) {
        is MiniTypeName -> ": ${t.segments.joinToString(".") { it.name }}${if (t.nullable) "?" else ""}"
        is MiniGenericType -> {
            val base = typeOf(t.base).removePrefix(": ")
            val args = t.args.joinToString(", ") { typeOf(it).removePrefix(": ") }
            ": ${base}<${args}>${if (t.nullable) "?" else ""}"
        }
        is MiniFunctionType -> ": (..) -> ..${if (t.nullable) "?" else ""}"
        is MiniTypeVar -> ": ${t.name}${if (t.nullable) "?" else ""}"
        null -> ""
    }

    private fun signatureOf(fn: MiniFunDecl): String {
        val params = fn.params.joinToString(", ") { p ->
            val ts = typeOf(p.type)
            if (ts.isNotBlank()) "${p.name}${ts}" else p.name
        }
        val ret = typeOf(fn.returnType)
        return "(${params})${ret}"
    }

    private fun htmlEscape(s: String): String = buildString(s.length) {
        for (ch in s) append(
            when (ch) {
                '<' -> "&lt;"
                '>' -> "&gt;"
                '&' -> "&amp;"
                '"' -> "&quot;"
                else -> ch
            }
        )
    }

    private fun styledMarkdown(html: String): String {
        // IntelliJ doc renderer sanitizes and may surface <style> content as text.
        // Strip any style tags defensively and keep markup lean; rely on platform defaults.
        val safe = stripStyleTags(html)
        return """
            <div class="lyng-doc-md" style="max-width:72ch; line-height:1.4; font-size:0.96em;">
              $safe
            </div>
        """.trimIndent()
    }

    private fun stripStyleTags(src: String): String {
        // Remove any <style>...</style> blocks (case-insensitive, dotall)
        val styleRegex = Regex("(?is)<style[^>]*>.*?</style>")
        return src.replace(styleRegex, "")
    }

    // --- Simple helpers to support overload selection and heuristics ---
    /**
     * If identifier at [rightAfterIdent] is followed by a call like `(a, b)`,
     * return the argument count; otherwise return null. Nested parentheses are
     * handled conservatively to skip commas inside lambdas/parentheses.
     */
    private fun callArity(text: String, rightAfterIdent: Int): Int? {
        var i = rightAfterIdent
        // Skip whitespace
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '(') return null
        i++
        var depth = 0
        var commas = 0
        var hasToken = false
        while (i < text.length) {
            val ch = text[i]
            when (ch) {
                '(' -> { depth++; hasToken = true }
                ')' -> {
                    if (depth == 0) {
                        // Empty parentheses => arity 0 if no token and no commas
                        if (!hasToken && commas == 0) return 0
                        return commas + 1
                    } else depth--
                }
                ',' -> if (depth == 0) { commas++; hasToken = false }
                '\n' -> {}
                else -> if (!ch.isWhitespace()) hasToken = true
            }
            i++
        }
        return null
    }

    private fun renderOverloads(name: String, overloads: List<MiniFunDecl>): String {
        val sb = StringBuilder()
        sb.append("<div class='doc-title'>Overloads for ").append(htmlEscape(name)).append("</div>")
        sb.append("<ul>")
        overloads.forEach { fn ->
            sb.append("<li><code>")
                .append(htmlEscape("fun ${fn.name}${signatureOf(fn)}"))
                .append("</code>")
            fn.doc?.summary?.let { sum -> sb.append(" — ").append(htmlEscape(sum)) }
            sb.append("</li>")
        }
        sb.append("</ul>")
        return sb.toString()
    }

    private fun wordRangeAt(text: String, offset: Int): TextRange? {
        if (text.isEmpty()) return null
        var s = offset.coerceIn(0, text.length)
        var e = s
        while (s > 0 && isIdentChar(text[s - 1])) s--
        while (e < text.length && isIdentChar(text[e])) e++
        return if (e > s) TextRange(s, e) else null
    }

    private fun previousWordBefore(text: String, offset: Int): TextRange? {
        // skip spaces and the dot to the left, but stop after hitting a non-identifier boundary
        var i = (offset - 1).coerceAtLeast(0)
        // skip trailing spaces
        while (i >= 0 && text[i].isWhitespace()) i--
        // skip the dot if present
        if (i >= 0 && text[i] == '.') i--
        // skip spaces before the dot
        while (i >= 0 && text[i].isWhitespace()) i--
        // remember position to check for dot between words
        val end = i + 1
        // now find the start of the identifier
        while (i >= 0 && isIdentChar(text[i])) i--
        val start = (i + 1)
        return if (start < end && start >= 0) TextRange(start, end) else null
    }

    private fun hasDotBetween(text: String, leftEnd: Int, rightStart: Int): Boolean {
        val s = leftEnd.coerceAtLeast(0)
        val e = rightStart.coerceAtMost(text.length)
        if (e <= s) return false
        for (i in s until e) if (text[i] == '.') return true
        return false
    }

    private fun isIdentChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    // --- Helpers for inheritance-aware and heuristic member lookup ---

    // Removed: member/class resolution helpers moved to lynglib DocLookupUtils for reuse

    private fun findDotLeft(text: String, rightStart: Int): Int? {
        var i = (rightStart - 1).coerceAtLeast(0)
        while (i >= 0 && text[i].isWhitespace()) i--
        while (i >= 0) {
            val ch = text[i]
            if (ch == '.') return i
            if (ch == '\n') return null
            i--
        }
        return null
    }

    private fun looksLikeListLiteralBefore(text: String, dotPos: Int): Boolean {
        // Look left for a closing ']' possibly with spaces, then a matching '[' before a comma or assignment
        var i = (dotPos - 1).coerceAtLeast(0)
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0 || text[i] != ']') return false
        var depth = 0
        i--
        while (i >= 0) {
            val ch = text[i]
            when (ch) {
                ']' -> depth++
                '[' -> if (depth == 0) return true else depth--
                '\n' -> return false
            }
            i--
        }
        return false
    }

    // Removed: guessClassFromCallBefore moved to DocLookupUtils
}
