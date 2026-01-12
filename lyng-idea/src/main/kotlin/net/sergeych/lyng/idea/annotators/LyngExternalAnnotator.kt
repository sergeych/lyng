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

package net.sergeych.lyng.idea.annotators

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import net.sergeych.lyng.Source
import net.sergeych.lyng.binding.Binder
import net.sergeych.lyng.binding.SymbolKind
import net.sergeych.lyng.highlight.HighlightKind
import net.sergeych.lyng.highlight.SimpleLyngHighlighter
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.idea.highlight.LyngHighlighterColors
import net.sergeych.lyng.idea.util.LyngAstManager
import net.sergeych.lyng.miniast.*

/**
 * ExternalAnnotator that runs Lyng MiniAst on the document text in background
 * and applies semantic highlighting comparable with the web highlighter.
 */
class LyngExternalAnnotator : ExternalAnnotator<LyngExternalAnnotator.Input, LyngExternalAnnotator.Result>() {
    data class Input(val text: String, val modStamp: Long, val previousSpans: List<Span>?, val file: PsiFile)

    data class Span(val start: Int, val end: Int, val key: com.intellij.openapi.editor.colors.TextAttributesKey)
    data class Error(val start: Int, val end: Int, val message: String)
    data class Result(val modStamp: Long, val spans: List<Span>, val error: Error? = null,
                      val spellIdentifiers: List<IntRange> = emptyList(),
                      val spellComments: List<IntRange> = emptyList(),
                      val spellStrings: List<IntRange> = emptyList())

    override fun collectInformation(file: PsiFile): Input? {
        val doc: Document = file.viewProvider.document ?: return null
        val cached = file.getUserData(CACHE_KEY)
        val combinedStamp = LyngAstManager.getCombinedStamp(file)

        val prev = if (cached != null && cached.modStamp == combinedStamp) cached.spans else null
        return Input(doc.text, combinedStamp, prev, file)
    }

    override fun doAnnotate(collectedInfo: Input?): Result? {
        if (collectedInfo == null) return null
        ProgressManager.checkCanceled()
        val text = collectedInfo.text
        val tokens = try { SimpleLyngHighlighter().highlight(text) } catch (_: Throwable) { emptyList() }
        
        // Use LyngAstManager to get the (potentially merged) Mini-AST
        val mini = LyngAstManager.getMiniAst(collectedInfo.file) 
            ?: return Result(collectedInfo.modStamp, collectedInfo.previousSpans ?: emptyList())
        
        ProgressManager.checkCanceled()
        val source = Source(collectedInfo.file.name, text)
        
        val out = ArrayList<Span>(256)

        fun isFollowedByParenOrBlock(rangeEnd: Int): Boolean {
            var i = rangeEnd
            while (i < text.length) {
                val ch = text[i]
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') { i++; continue }
                return ch == '(' || ch == '{'
            }
            return false
        }

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
        mini.declarations.forEach { d ->
            if (d.nameStart.source != source) return@forEach
            when (d) {
                is MiniFunDecl -> putName(d.nameStart, d.name, LyngHighlighterColors.FUNCTION_DECLARATION)
                is MiniClassDecl -> putName(d.nameStart, d.name, LyngHighlighterColors.TYPE)
                is MiniValDecl -> putName(
                    d.nameStart,
                    d.name,
                    if (d.mutable) LyngHighlighterColors.VARIABLE else LyngHighlighterColors.VALUE
                )
                is MiniEnumDecl -> putName(d.nameStart, d.name, LyngHighlighterColors.TYPE)
            }
        }

        // Imports: each segment as namespace/path
        mini.imports.forEach { imp ->
            if (imp.range.start.source != source) return@forEach
            imp.segments.forEach { seg -> putMiniRange(seg.range, LyngHighlighterColors.NAMESPACE) }
        }

        // Parameters
        fun addParams(params: List<MiniParam>) {
            params.forEach { p ->
                if (p.nameStart.source == source)
                    putName(p.nameStart, p.name, LyngHighlighterColors.PARAMETER)
            }
        }
        mini.declarations.forEach { d ->
            when (d) {
                is MiniFunDecl -> addParams(d.params)
                is MiniClassDecl -> d.members.filterIsInstance<MiniMemberFunDecl>().forEach { addParams(it.params) }
                else -> {}
            }
        }

        // Type name segments (including generics base & args)
        fun addTypeSegments(t: MiniTypeRef?) {
            when (t) {
                is MiniTypeName -> t.segments.forEach { seg ->
                    if (seg.range.start.source != source) return@forEach
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
                    if (t.range.start.source == source)
                        putMiniRange(t.range, LyngHighlighterColors.TYPE)
                }
                null -> {}
            }
        }
        fun addDeclTypeSegments(d: MiniDecl) {
            if (d.nameStart.source != source) return
            when (d) {
                is MiniFunDecl -> {
                    addTypeSegments(d.returnType)
                    d.params.forEach { addTypeSegments(it.type) }
                    addTypeSegments(d.receiver)
                }
                is MiniValDecl -> {
                    addTypeSegments(d.type)
                    addTypeSegments(d.receiver)
                }
                is MiniClassDecl -> {
                    d.ctorFields.forEach { addTypeSegments(it.type) }
                    d.classFields.forEach { addTypeSegments(it.type) }
                    for (m in d.members) {
                        when (m) {
                            is MiniMemberFunDecl -> {
                                addTypeSegments(m.returnType)
                                m.params.forEach { addTypeSegments(it.type) }
                            }
                            is MiniMemberValDecl -> {
                                addTypeSegments(m.type)
                            }
                            else -> {}
                        }
                    }
                }
                is MiniEnumDecl -> {}
            }
        }
        mini.declarations.forEach { d -> addDeclTypeSegments(d) }

        ProgressManager.checkCanceled()

        // Semantic usages via Binder (best-effort)
        try {
            val binding = Binder.bind(text, mini)

            // Map declaration ranges to avoid duplicating them as usages
            val declKeys = HashSet<Pair<Int, Int>>(binding.symbols.size * 2)
            binding.symbols.forEach { sym -> declKeys += (sym.declStart to sym.declEnd) }

            fun keyForKind(k: SymbolKind) = when (k) {
                SymbolKind.Function -> LyngHighlighterColors.FUNCTION
                SymbolKind.Class, SymbolKind.Enum -> LyngHighlighterColors.TYPE
                SymbolKind.Parameter -> LyngHighlighterColors.PARAMETER
                SymbolKind.Value -> LyngHighlighterColors.VALUE
                SymbolKind.Variable -> LyngHighlighterColors.VARIABLE
            }

            // Track covered ranges to not override later heuristics
            val covered = HashSet<Pair<Int, Int>>()

            binding.references.forEach { ref ->
                val key = ref.start to ref.end
                if (!declKeys.contains(key)) {
                    val sym = binding.symbols.firstOrNull { it.id == ref.symbolId }
                    if (sym != null) {
                        val color = keyForKind(sym.kind)
                        putRange(ref.start, ref.end, color)
                        covered += key
                    }
                }
            }

            // Heuristics on top of binder: function call-sites and simple name-based roles
            ProgressManager.checkCanceled()

            // Build simple name -> role map for top-level vals/vars and parameters
            val nameRole = HashMap<String, com.intellij.openapi.editor.colors.TextAttributesKey>(8)
            mini.declarations.forEach { d ->
                when (d) {
                    is MiniValDecl -> nameRole[d.name] =
                        if (d.mutable) LyngHighlighterColors.VARIABLE else LyngHighlighterColors.VALUE

                    is MiniFunDecl -> d.params.forEach { p -> nameRole[p.name] = LyngHighlighterColors.PARAMETER }
                    is MiniClassDecl -> {
                        d.members.forEach { m ->
                            if (m is MiniMemberFunDecl) {
                                m.params.forEach { p -> nameRole[p.name] = LyngHighlighterColors.PARAMETER }
                            }
                        }
                    }
                    else -> {}
                }
            }

            tokens.forEach { s ->
                if (s.kind == HighlightKind.Identifier) {
                    val start = s.range.start
                    val end = s.range.endExclusive
                    val key = start to end
                    if (key !in covered && key !in declKeys) {
                        // Call-site detection first so it wins over var/param role
                        if (isFollowedByParenOrBlock(end)) {
                            putRange(start, end, LyngHighlighterColors.FUNCTION)
                            covered += key
                        } else {
                            // Simple role by known names
                            val ident = try {
                                text.substring(start, end)
                            } catch (_: Throwable) {
                                null
                            }
                            if (ident != null) {
                                val roleKey = nameRole[ident]
                                if (roleKey != null) {
                                    putRange(start, end, roleKey)
                                    covered += key
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // Must rethrow cancellation; otherwise ignore binder failures (best-effort)
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
        }

        // Add annotation/label coloring using token highlighter
        run {
            tokens.forEach { s ->
                if (s.kind == HighlightKind.Label) {
                    val start = s.range.start
                    val end = s.range.endExclusive
                    if (start in 0..end && end <= text.length && start < end) {
                        val lexeme = try {
                            text.substring(start, end)
                        } catch (_: Throwable) {
                            null
                        }
                        if (lexeme != null) {
                            // Heuristic: if it starts with @ and follows a control keyword, it's likely a label
                            // Otherwise if it starts with @ it's an annotation.
                            // If it ends with @ it's a loop label.
                            when {
                                lexeme.endsWith("@") -> putRange(start, end, LyngHighlighterColors.LABEL)
                                lexeme.startsWith("@") -> {
                                    // Try to see if it's an exit label
                                    val prevNonWs = prevNonWs(text, start)
                                    val prevWord = if (prevNonWs >= 0) {
                                        var wEnd = prevNonWs + 1
                                        var wStart = prevNonWs
                                        while (wStart > 0 && text[wStart - 1].isLetter()) wStart--
                                        text.substring(wStart, wEnd)
                                    } else null

                                    if (prevWord in setOf("return", "break", "continue") || isFollowedByParenOrBlock(end)) {
                                        putRange(start, end, LyngHighlighterColors.LABEL)
                                    } else {
                                        putRange(start, end, LyngHighlighterColors.ANNOTATION)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Map Enum constants from token highlighter to IDEA enum constant color
        run {
            tokens.forEach { s ->
                if (s.kind == HighlightKind.EnumConstant) {
                    val start = s.range.start
                    val end = s.range.endExclusive
                    if (start in 0..end && end <= text.length && start < end) {
                        putRange(start, end, LyngHighlighterColors.ENUM_CONSTANT)
                    }
                }
            }
        }

        // Build spell index payload: identifiers + comments/strings from simple highlighter.
        // We use the highlighter as the source of truth for all "words" to check, including
        // identifiers that might not be bound by the Binder.
        val idRanges = tokens.filter { it.kind == HighlightKind.Identifier }.map { it.range.start until it.range.endExclusive }
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
        val combinedStamp = LyngAstManager.getCombinedStamp(file)
        val cached = file.getUserData(CACHE_KEY)
        val result = if (cached != null && cached.modStamp == combinedStamp) cached else annotationResult
        file.putUserData(CACHE_KEY, result)

        val doc = file.viewProvider.document

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

    private fun prevNonWs(text: String, idxExclusive: Int): Int {
        var i = idxExclusive - 1
        while (i >= 0) {
            val ch = text[i]
            if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') return i
            i--
        }
        return -1
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
