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
import net.sergeych.lyng.Source
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.idea.highlight.LyngHighlighterColors
import net.sergeych.lyng.idea.util.IdeLenientImportProvider
import net.sergeych.lyng.miniast.*

/**
 * ExternalAnnotator that runs Lyng MiniAst on the document text in background
 * and applies semantic highlighting comparable with the web highlighter.
 */
class LyngExternalAnnotator : ExternalAnnotator<LyngExternalAnnotator.Input, LyngExternalAnnotator.Result>() {
    data class Input(val text: String, val modStamp: Long)

    data class Span(val start: Int, val end: Int, val key: com.intellij.openapi.editor.colors.TextAttributesKey)
    data class Result(val modStamp: Long, val spans: List<Span>)

    override fun collectInformation(file: PsiFile): Input? {
        val doc: Document = file.viewProvider.document ?: return null
        return Input(doc.text, doc.modificationStamp)
    }

    override fun doAnnotate(collectedInfo: Input?): Result? {
        if (collectedInfo == null) return null
        ProgressManager.checkCanceled()
        val text = collectedInfo.text
        // Build Mini-AST using the same mechanism as web highlighter
        val sink = MiniAstBuilder()
        try {
            // Call suspend API from blocking context
            val src = Source("<ide>", text)
            val provider = IdeLenientImportProvider.create()
            runBlocking { Compiler.compileWithMini(src, provider, sink) }
        } catch (_: Throwable) {
            // Fail softly: no semantic layer this pass
            return Result(collectedInfo.modStamp, emptyList())
        }
        ProgressManager.checkCanceled()
        val mini = sink.build() ?: return Result(collectedInfo.modStamp, emptyList())
        val source = Source("<ide>", text)

        val out = ArrayList<Span>(64)

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
                is MiniFunDecl -> putName(d.nameStart, d.name, LyngHighlighterColors.FUNCTION)
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
        return Result(collectedInfo.modStamp, out)
    }

    override fun apply(file: PsiFile, annotationResult: Result?, holder: AnnotationHolder) {
        if (annotationResult == null) return
        // Skip if cache is up-to-date
        val doc = file.viewProvider.document
        val currentStamp = doc?.modificationStamp
        val cached = file.getUserData(CACHE_KEY)
        val result = if (cached != null && currentStamp != null && cached.modStamp == currentStamp) cached else annotationResult
        file.putUserData(CACHE_KEY, result)

        for (s in result.spans) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(s.start, s.end))
                .textAttributes(s.key)
                .create()
        }
    }

    companion object {
        private val CACHE_KEY: Key<Result> = Key.create("LYNG_SEMANTIC_CACHE")
    }
}
