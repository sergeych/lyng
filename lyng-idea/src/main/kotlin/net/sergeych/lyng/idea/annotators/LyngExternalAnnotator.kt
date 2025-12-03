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

package net.sergeych.lyng.idea.annotators

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.Source
import net.sergeych.lyng.binding.Binder
import net.sergeych.lyng.binding.SymbolKind
import net.sergeych.lyng.highlight.HighlightKind
import net.sergeych.lyng.highlight.SimpleLyngHighlighter
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.idea.highlight.LyngHighlighterColors
import net.sergeych.lyng.idea.util.IdeLenientImportProvider
import net.sergeych.lyng.miniast.*

/**
 * ExternalAnnotator that runs Lyng MiniAst on the document text in background
 * and applies semantic highlighting comparable with the web highlighter.
 */
class LyngExternalAnnotator : ExternalAnnotator<LyngExternalAnnotator.Input, LyngExternalAnnotator.Result>() {
    data class Input(val text: String, val modStamp: Long, val previousSpans: List<Span>?)

    data class Span(val start: Int, val end: Int, val key: com.intellij.openapi.editor.colors.TextAttributesKey)
    data class Error(val start: Int, val end: Int, val message: String)
    data class Result(val modStamp: Long, val spans: List<Span>, val error: Error? = null,
                      val spellIdentifiers: List<IntRange> = emptyList(),
                      val spellComments: List<IntRange> = emptyList(),
                      val spellStrings: List<IntRange> = emptyList())

    override fun collectInformation(file: PsiFile): Input? {
        val doc: Document = file.viewProvider.document ?: return null
        val prev = file.getUserData(CACHE_KEY)?.spans
        return Input(doc.text, doc.modificationStamp, prev)
    }

    override fun doAnnotate(collectedInfo: Input?): Result? {
        if (collectedInfo == null) return null
        ProgressManager.checkCanceled()
        val text = collectedInfo.text
        // Build Mini-AST using the same mechanism as web highlighter
        val sink = MiniAstBuilder()
        val source = Source("<ide>", text)
        try {
            // Call suspend API from blocking context
            val provider = IdeLenientImportProvider.create()
            runBlocking { Compiler.compileWithMini(source, provider, sink) }
        } catch (e: Throwable) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            // On script parse error: keep previous spans and report the error location
            if (e is ScriptError) {
                val off = try { source.offsetOf(e.pos) } catch (_: Throwable) { -1 }
                val start0 = off.coerceIn(0, text.length.coerceAtLeast(0))
                val (start, end) = expandErrorRange(text, start0)
                return Result(
                    collectedInfo.modStamp,
                    collectedInfo.previousSpans ?: emptyList(),
                    Error(start, end, e.errorMessage)
                )
            }
            // Other failures: keep previous spans without error
            return Result(collectedInfo.modStamp, collectedInfo.previousSpans ?: emptyList(), null)
        }
        ProgressManager.checkCanceled()
        val mini = sink.build() ?: return Result(collectedInfo.modStamp, collectedInfo.previousSpans ?: emptyList())

        val out = ArrayList<Span>(256)

        fun putRange(start: Int, end: Int, key: com.intellij.openapi.editor.colors.TextAttributesKey) {
            if (start in 0..end && end <= text.length && start < end) out += Span(start, end, key)
        }
        fun putName(startPos: net.sergeych.lyng.Pos, name: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) {
            val s = source.offsetOf(startPos)
            putRange(s, (s + name.length).coerceAtMost(text.length), key)
        }
        fun putMiniRange(r: MiniRange, key: com.intellij.openapi.editor.colors.TextAttributesKey) {
            val s = source.offsetOf(r.start)
            val e = source.offsetOf(r.end)
            putRange(s, e, key)
        }

        // Declarations
        for (d in mini.declarations) {
            when (d) {
                is MiniFunDecl -> putName(d.nameStart, d.name, LyngHighlighterColors.FUNCTION_DECLARATION)
                is MiniClassDecl -> putName(d.nameStart, d.name, LyngHighlighterColors.TYPE)
                is MiniValDecl -> putName(
                    d.nameStart,
                    d.name,
                    if (d.mutable) LyngHighlighterColors.VARIABLE else LyngHighlighterColors.VALUE
                )
            }
        }

        // Imports: each segment as namespace/path
        for (imp in mini.imports) {
            for (seg in imp.segments) putMiniRange(seg.range, LyngHighlighterColors.NAMESPACE)
        }

        // Parameters
        for (fn in mini.declarations.filterIsInstance<MiniFunDecl>()) {
            for (p in fn.params) putName(p.nameStart, p.name, LyngHighlighterColors.PARAMETER)
        }

        // Type name segments (including generics base & args)
        fun addTypeSegments(t: MiniTypeRef?) {
            when (t) {
                is MiniTypeName -> t.segments.forEach { seg ->
                    val s = source.offsetOf(seg.range.start)
                    putRange(s, (s + seg.name.length).coerceAtMost(text.length), LyngHighlighterColors.TYPE)
                }
                is MiniGenericType -> {
                    addTypeSegments(t.base)
                    t.args.forEach { addTypeSegments(it) }
                }
                is MiniFunctionType -> {
                    t.receiver?.let { addTypeSegments(it) }
                    t.params.forEach { addTypeSegments(it) }
                    addTypeSegments(t.returnType)
                }
                is MiniTypeVar -> { /* name is in range; could be highlighted as TYPE as well */
                    putMiniRange(t.range, LyngHighlighterColors.TYPE)
                }
                null -> {}
            }
        }
        for (d in mini.declarations) {
            when (d) {
                is MiniFunDecl -> {
                    addTypeSegments(d.returnType)
                    d.params.forEach { addTypeSegments(it.type) }
                }
                is MiniValDecl -> addTypeSegments(d.type)
                is MiniClassDecl -> {
                    d.ctorFields.forEach { addTypeSegments(it.type) }
                    d.classFields.forEach { addTypeSegments(it.type) }
                }
            }
        }

        ProgressManager.checkCanceled()

        // Semantic usages via Binder (best-effort)
        try {
            val binding = Binder.bind(text, mini)

            // Map declaration ranges to avoid duplicating them as usages
            val declKeys = HashSet<Pair<Int, Int>>(binding.symbols.size * 2)
            for (sym in binding.symbols) declKeys += (sym.declStart to sym.declEnd)

            fun keyForKind(k: SymbolKind) = when (k) {
                SymbolKind.Function -> LyngHighlighterColors.FUNCTION
                SymbolKind.Class, SymbolKind.Enum -> LyngHighlighterColors.TYPE
                SymbolKind.Param -> LyngHighlighterColors.PARAMETER
                SymbolKind.Val -> LyngHighlighterColors.VALUE
                SymbolKind.Var -> LyngHighlighterColors.VARIABLE
            }

            // Track covered ranges to not override later heuristics
            val covered = HashSet<Pair<Int, Int>>()

            for (ref in binding.references) {
                val key = ref.start to ref.end
                if (declKeys.contains(key)) continue
                val sym = binding.symbols.firstOrNull { it.id == ref.symbolId } ?: continue
                val color = keyForKind(sym.kind)
                putRange(ref.start, ref.end, color)
                covered += key
            }

            // Heuristics on top of binder: function call-sites and simple name-based roles
            ProgressManager.checkCanceled()

            val tokens = try { SimpleLyngHighlighter().highlight(text) } catch (_: Throwable) { emptyList() }

            fun isFollowedByParenOrBlock(rangeEnd: Int): Boolean {
                var i = rangeEnd
                while (i < text.length) {
                    val ch = text[i]
                    if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') { i++; continue }
                    return ch == '(' || ch == '{'
                }
                return false
            }

            // Build simple name -> role map for top-level vals/vars and parameters
            val nameRole = HashMap<String, com.intellij.openapi.editor.colors.TextAttributesKey>(8)
            for (d in mini.declarations) when (d) {
                is MiniValDecl -> nameRole[d.name] = if (d.mutable) LyngHighlighterColors.VARIABLE else LyngHighlighterColors.VALUE
                is MiniFunDecl -> d.params.forEach { p -> nameRole[p.name] = LyngHighlighterColors.PARAMETER }
                else -> {}
            }

            for (s in tokens) if (s.kind == HighlightKind.Identifier) {
                val start = s.range.start
                val end = s.range.endExclusive
                val key = start to end
                if (key in covered || key in declKeys) continue

                // Call-site detection first so it wins over var/param role
                if (isFollowedByParenOrBlock(end)) {
                    putRange(start, end, LyngHighlighterColors.FUNCTION)
                    covered += key
                    continue
                }

                // Simple role by known names
                val ident = try { text.substring(start, end) } catch (_: Throwable) { null }
                if (ident != null) {
                    val roleKey = nameRole[ident]
                    if (roleKey != null) {
                        putRange(start, end, roleKey)
                        covered += key
                    }
                }
            }
        } catch (e: Throwable) {
            // Must rethrow cancellation; otherwise ignore binder failures (best-effort)
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
        }

        // Build spell index payload: identifiers from symbols + references; comments/strings from simple highlighter
        val idRanges = mutableSetOf<IntRange>()
        try {
            val binding = Binder.bind(text, mini)
            for (sym in binding.symbols) {
                val s = sym.declStart; val e = sym.declEnd
                if (s in 0..e && e <= text.length && s < e) idRanges += (s until e)
            }
            for (ref in binding.references) {
                val s = ref.start; val e = ref.end
                if (s in 0..e && e <= text.length && s < e) idRanges += (s until e)
            }
        } catch (_: Throwable) {
            // Best-effort; no identifiers if binder fails
        }
        val tokens = try { SimpleLyngHighlighter().highlight(text) } catch (_: Throwable) { emptyList() }
        val commentRanges = tokens.filter { it.kind == HighlightKind.Comment }.map { it.range.start until it.range.endExclusive }
        val stringRanges = tokens.filter { it.kind == HighlightKind.String }.map { it.range.start until it.range.endExclusive }

        return Result(collectedInfo.modStamp, out, null,
            spellIdentifiers = idRanges.toList(),
            spellComments = commentRanges,
            spellStrings = stringRanges)
    }

    override fun apply(file: PsiFile, annotationResult: Result?, holder: AnnotationHolder) {
        if (annotationResult == null) return
        // Skip if cache is up-to-date
        val doc = file.viewProvider.document
        val currentStamp = doc?.modificationStamp
        val cached = file.getUserData(CACHE_KEY)
        val result = if (cached != null && currentStamp != null && cached.modStamp == currentStamp) cached else annotationResult
        file.putUserData(CACHE_KEY, result)

        // Store spell index for spell/grammar engines to consume (suspend until ready)
        val ids = result.spellIdentifiers.map { TextRange(it.first, it.last + 1) }
        val coms = result.spellComments.map { TextRange(it.first, it.last + 1) }
        val strs = result.spellStrings.map { TextRange(it.first, it.last + 1) }
        net.sergeych.lyng.idea.spell.LyngSpellIndex.store(file,
            net.sergeych.lyng.idea.spell.LyngSpellIndex.Data(
                modStamp = result.modStamp,
                identifiers = ids,
                comments = coms,
                strings = strs
            )
        )

        // Optional diagnostic overlay: visualize the ranges we will feed to spellcheckers
        val settings = net.sergeych.lyng.idea.settings.LyngFormatterSettings.getInstance(file.project)
        if (settings.debugShowSpellFeed) {
            fun paint(r: TextRange, label: String) {
                holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "spell-feed: $label")
                    .range(r)
                    .create()
            }
            ids.forEach { paint(it, "id") }
            coms.forEach { paint(it, "comment") }
            if (settings.spellCheckStringLiterals) strs.forEach { paint(it, "string") }
        }

        for (s in result.spans) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(s.start, s.end))
                .textAttributes(s.key)
                .create()
        }

        // Show syntax error if present
        val err = result.error
        if (err != null) {
            val start = err.start.coerceIn(0, (doc?.textLength ?: 0))
            val end = err.end.coerceIn(start, (doc?.textLength ?: start))
            if (end > start) {
                holder.newAnnotation(HighlightSeverity.ERROR, err.message)
                    .range(TextRange(start, end))
                    .create()
            }
        }
    }

    companion object {
        private val CACHE_KEY: Key<Result> = Key.create("LYNG_SEMANTIC_CACHE")
    }

    /**
     * Make the error highlight a bit wider than a single character so it is easier to see and click.
     * Strategy:
     *  - If the offset points inside an identifier-like token (letters/digits/underscore), expand to the full token.
     *  - Otherwise select a small range starting at the offset with a minimum width, but not crossing the line end.
     */
    private fun expandErrorRange(text: String, rawStart: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val len = text.length
        val start = rawStart.coerceIn(0, len)
        fun isWord(ch: Char) = ch == '_' || ch.isLetterOrDigit()

        if (start < len && isWord(text[start])) {
            var s = start
            var e = start
            while (s > 0 && isWord(text[s - 1])) s--
            while (e < len && isWord(text[e])) e++
            return s to e
        }

        // Not inside a word: select a short, visible range up to EOL
        val lineEnd = text.indexOf('\n', start).let { if (it == -1) len else it }
        val minWidth = 4
        val end = (start + minWidth).coerceAtMost(lineEnd).coerceAtLeast((start + 1).coerceAtMost(lineEnd))
        return start to end
    }
}
