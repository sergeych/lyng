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
        try {
            // Use lenient import provider so unresolved imports (e.g., lyng.io.fs) don't break docs
            val src = Source("<ide>", text)
            val provider = IdeLenientImportProvider.create()
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
        val doc = d.doc?.raw?.let { htmlEscape(it).replace("\n", "<br/>") }
        val sb = StringBuilder()
        sb.append("<div class='doc-title'>").append(htmlEscape(title)).append("</div>")
        if (!doc.isNullOrBlank()) sb.append("<div class='doc-body'>").append(doc).append("</div>")
        return sb.toString()
    }

    private fun renderParamDoc(fn: MiniFunDecl, p: MiniParam): String {
        val title = "parameter ${p.name}${typeOf(p.type)} in ${fn.name}${signatureOf(fn)}"
        return "<div class='doc-title'>${htmlEscape(title)}</div>"
    }

    private fun typeOf(t: MiniTypeRef?): String = when (t) {
        is MiniTypeName -> ": ${t.segments.joinToString(".") { it.name }}"
        is MiniGenericType -> ": ${typeOf(t.base).removePrefix(": ")}<${t.args.joinToString(", ") { typeOf(it).removePrefix(": ") }}>"
        is MiniFunctionType -> ": (..) -> .."
        is MiniTypeVar -> ": ${t.name}"
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

    private fun wordRangeAt(text: String, offset: Int): TextRange? {
        if (text.isEmpty()) return null
        var s = offset.coerceIn(0, text.length)
        var e = s
        while (s > 0 && isIdentChar(text[s - 1])) s--
        while (e < text.length && isIdentChar(text[e])) e++
        return if (e > s) TextRange(s, e) else null
    }

    private fun isIdentChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
}
