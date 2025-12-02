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

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Source
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.util.IdeLenientImportProvider
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
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element == null) return null
        val file: PsiFile = element.containingFile ?: return null
        val document: Document = file.viewProvider.document ?: return null
        val text = document.text

        // Determine caret/lookup offset from the element range
        val offset = originalElement?.textRange?.startOffset ?: element.textRange.startOffset
        val idRange = wordRangeAt(text, offset) ?: run {
            log.info("[LYNG_DEBUG] QuickDoc: no word at offset=$offset in ${file.name}")
            return null
        }
        if (idRange.isEmpty) return null
        val ident = text.substring(idRange.startOffset, idRange.endOffset)
        log.info("[LYNG_DEBUG] QuickDoc: ident='$ident' at ${idRange.startOffset}..${idRange.endOffset} in ${file.name}")

        // Build MiniAst for this file (fast and resilient). Best-effort; on failure return null.
        val sink = MiniAstBuilder()
        // Use lenient import provider so unresolved imports (e.g., lyng.io.fs) don't break docs
        val provider = IdeLenientImportProvider.create()
        try {
            val src = Source("<ide>", text)
            runBlocking { Compiler.compileWithMini(src, provider, sink) }
        } catch (t: Throwable) {
            log.warn("[LYNG_DEBUG] QuickDoc: compileWithMini failed: ${t.message}")
            return null
        }
        val mini = sink.build() ?: return null
        val source = Source("<ide>", text)

        // Try resolve to: function param at position, function/class/val declaration at position
        // 1) Check declarations whose name range contains offset
        for (d in mini.declarations) {
            val s = source.offsetOf(d.nameStart)
            val e = (s + d.name.length).coerceAtMost(text.length)
            if (offset in s until e) {
                log.info("[LYNG_DEBUG] QuickDoc: matched decl '${d.name}' kind=${d::class.simpleName}")
                return renderDeclDoc(d)
            }
        }
        // 2) Check parameters of functions
        for (fn in mini.declarations.filterIsInstance<MiniFunDecl>()) {
            for (p in fn.params) {
                val s = source.offsetOf(p.nameStart)
                val e = (s + p.name.length).coerceAtMost(text.length)
                if (offset in s until e) {
                    log.info("[LYNG_DEBUG] QuickDoc: matched param '${p.name}' in fun '${fn.name}'")
                    return renderParamDoc(fn, p)
                }
            }
        }
        // 3) As a fallback, if the caret is on an identifier text that matches any declaration name, show that
        mini.declarations.firstOrNull { it.name == ident }?.let {
            log.info("[LYNG_DEBUG] QuickDoc: fallback by name '${it.name}' kind=${it::class.simpleName}")
            return renderDeclDoc(it)
        }

        // 4) Consult BuiltinDocRegistry for imported modules (top-level and class members)
        var importedModules = mini.imports.map { it.segments.joinToString(".") { s -> s.name } }
        // Core-module fallback: in scratch/repl-like files without imports, consult stdlib by default
        if (importedModules.isEmpty()) importedModules = listOf("lyng.stdlib")
        // 4a) try top-level decls
        for (mod in importedModules) {
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
            // And classes
            docs.filterIsInstance<MiniClassDecl>().firstOrNull { it.name == ident }?.let { return renderDeclDoc(it) }
        }
        // 4b) try class members like ClassName.member with inheritance fallback
        val lhs = previousWordBefore(text, idRange.startOffset)
        if (lhs != null && hasDotBetween(text, lhs.endOffset, idRange.startOffset)) {
            val className = text.substring(lhs.startOffset, lhs.endOffset)
            resolveMemberWithInheritance(importedModules, className, ident)?.let { (owner, member) ->
                if (DEBUG_INHERITANCE) log.info("[LYNG_DEBUG] Inheritance resolved $className.$ident to $owner.${member.name}")
                return when (member) {
                    is MiniMemberFunDecl -> renderMemberFunDoc(owner, member)
                    is MiniMemberValDecl -> renderMemberValDoc(owner, member)
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
                    else -> null
                }
                if (guessed != null) {
                    resolveMemberWithInheritance(importedModules, guessed, ident)?.let { (owner, member) ->
                        if (DEBUG_INHERITANCE) log.info("[LYNG_DEBUG] Heuristic '$guessed.$ident' resolved via inheritance to $owner.${member.name}")
                        return when (member) {
                            is MiniMemberFunDecl -> renderMemberFunDoc(owner, member)
                            is MiniMemberValDecl -> renderMemberValDoc(owner, member)
                        }
                    }
                } else {
                    // Search across classes; prefer Iterable, then Iterator, then List for common ops
                    findMemberAcrossClasses(importedModules, ident)?.let { (owner, member) ->
                        if (DEBUG_INHERITANCE) log.info("[LYNG_DEBUG] Cross-class '$ident' resolved to $owner.${member.name}")
                        return when (member) {
                            is MiniMemberFunDecl -> renderMemberFunDoc(owner, member)
                            is MiniMemberValDecl -> renderMemberValDoc(owner, member)
                        }
                    }
                }
            }
        }

        log.info("[LYNG_DEBUG] QuickDoc: nothing found for ident='$ident'")
        return null
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
            is MiniValDecl -> if (d.mutable) "var ${d.name}${typeOf(d.type)}" else "val ${d.name}${typeOf(d.type)}"
            else -> d.name
        }
        // Show full detailed documentation, not just the summary
        val raw = d.doc?.raw
        val doc: String? = if (raw.isNullOrBlank()) null else MarkdownRenderer.render(raw)
        val sb = StringBuilder()
        sb.append("<div class='doc-title'>").append(htmlEscape(title)).append("</div>")
        if (!doc.isNullOrBlank()) sb.append(styledMarkdown(doc!!))
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
        if (!doc.isNullOrBlank()) sb.append(styledMarkdown(doc!!))
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
        if (!doc.isNullOrBlank()) sb.append(styledMarkdown(doc!!))
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
        // skip spaces and dots to the left, but stop after hitting a non-identifier or dot boundary
        var i = (offset - 1).coerceAtLeast(0)
        // first, move left past spaces
        while (i > 0 && text[i].isWhitespace()) i--
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

    private fun aggregateClasses(importedModules: List<String>): Map<String, MiniClassDecl> {
        val map = LinkedHashMap<String, MiniClassDecl>()
        for (mod in importedModules) {
            val docs = BuiltinDocRegistry.docsForModule(mod)
            docs.filterIsInstance<MiniClassDecl>().forEach { cls ->
                // Prefer the first occurrence; allow later duplicates to be ignored
                map.putIfAbsent(cls.name, cls)
            }
        }
        return map
    }

    private fun resolveMemberWithInheritance(importedModules: List<String>, className: String, member: String): Pair<String, MiniMemberDecl>? {
        val classes = aggregateClasses(importedModules)
        fun dfs(name: String, visited: MutableSet<String>): Pair<String, MiniMemberDecl>? {
            val cls = classes[name] ?: return null
            cls.members.firstOrNull { it.name == member }?.let { return name to it }
            if (!visited.add(name)) return null
            for (baseName in cls.bases) {
                dfs(baseName, visited)?.let { return it }
            }
            return null
        }
        return dfs(className, mutableSetOf())
    }

    private fun findMemberAcrossClasses(importedModules: List<String>, member: String): Pair<String, MiniMemberDecl>? {
        val classes = aggregateClasses(importedModules)
        // Preferred order for ambiguous common ops
        val preference = listOf("Iterable", "Iterator", "List")
        // First, try preference order
        for (name in preference) {
            resolveMemberWithInheritance(importedModules, name, member)?.let { return it }
        }
        // Then, scan all
        for ((name, cls) in classes) {
            cls.members.firstOrNull { it.name == member }?.let { return name to it }
        }
        return null
    }

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
}
