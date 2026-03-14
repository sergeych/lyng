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
import net.sergeych.lyng.highlight.HighlightKind
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.idea.highlight.LyngHighlighterColors
import net.sergeych.lyng.idea.util.LyngAstManager
import net.sergeych.lyng.tools.LyngDiagnosticSeverity
import net.sergeych.lyng.tools.LyngLanguageTools
import net.sergeych.lyng.tools.LyngSemanticKind

/**
 * ExternalAnnotator that runs Lyng MiniAst on the document text in background
 * and applies semantic highlighting comparable with the web highlighter.
 */
class LyngExternalAnnotator : ExternalAnnotator<LyngExternalAnnotator.Input, LyngExternalAnnotator.Result>() {
    data class Input(val text: String, val modStamp: Long, val previousSpans: List<Span>?, val file: PsiFile)

    data class Span(val start: Int, val end: Int, val key: com.intellij.openapi.editor.colors.TextAttributesKey)
    data class Diag(val start: Int, val end: Int, val message: String, val severity: HighlightSeverity)
    data class Result(val modStamp: Long, val spans: List<Span>, val diagnostics: List<Diag> = emptyList())

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
        val analysis = LyngAstManager.getAnalysis(collectedInfo.file)
            ?: return Result(collectedInfo.modStamp, collectedInfo.previousSpans ?: emptyList())
        val mini = analysis.mini

        ProgressManager.checkCanceled()

        val out = ArrayList<Span>(256)
        val diags = ArrayList<Diag>()

        fun putRange(start: Int, end: Int, key: com.intellij.openapi.editor.colors.TextAttributesKey) {
            if (start in 0..end && end <= text.length && start < end) out += Span(start, end, key)
        }

        fun keyForKind(kind: LyngSemanticKind): com.intellij.openapi.editor.colors.TextAttributesKey? = when (kind) {
            LyngSemanticKind.Function -> LyngHighlighterColors.FUNCTION
            LyngSemanticKind.Class, LyngSemanticKind.Enum, LyngSemanticKind.TypeAlias -> LyngHighlighterColors.TYPE
            LyngSemanticKind.Value -> LyngHighlighterColors.VALUE
            LyngSemanticKind.Variable -> LyngHighlighterColors.VARIABLE
            LyngSemanticKind.Parameter -> LyngHighlighterColors.PARAMETER
            LyngSemanticKind.TypeRef -> LyngHighlighterColors.TYPE
            LyngSemanticKind.EnumConstant -> LyngHighlighterColors.ENUM_CONSTANT
        }

        // Semantic highlights from shared tooling
        LyngLanguageTools.semanticHighlights(analysis).forEach { span ->
            keyForKind(span.kind)?.let { putRange(span.range.start, span.range.endExclusive, it) }
        }

        // Imports: each segment as namespace/path
        mini?.imports?.forEach { imp ->
            imp.segments.forEach { seg ->
                if (seg.range.start.source === analysis.source && seg.range.end.source === analysis.source) {
                    val start = analysis.source.offsetOf(seg.range.start)
                    val end = analysis.source.offsetOf(seg.range.end)
                    putRange(start, end, LyngHighlighterColors.NAMESPACE)
                }
            }
        }

        // Add annotation/label coloring using token highlighter
        run {
            analysis.lexicalHighlights.forEach { s ->
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

                                    if (prevWord in setOf("return", "break", "continue")) {
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

        analysis.diagnostics.forEach { d ->
            val range = d.range ?: return@forEach
            val severity = if (d.severity == LyngDiagnosticSeverity.Warning) HighlightSeverity.WARNING else HighlightSeverity.ERROR
            diags += Diag(range.start, range.endExclusive, d.message, severity)
        }

        return Result(collectedInfo.modStamp, out, diags)
    }


    override fun apply(file: PsiFile, annotationResult: Result?, holder: AnnotationHolder) {
        if (annotationResult == null) return
        // Skip if cache is up-to-date
        val combinedStamp = LyngAstManager.getCombinedStamp(file)
        val cached = file.getUserData(CACHE_KEY)
        val result = if (cached != null && cached.modStamp == combinedStamp) cached else annotationResult
        file.putUserData(CACHE_KEY, result)

        val doc = file.viewProvider.document

        for (s in result.spans) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(s.start, s.end))
                .textAttributes(s.key)
                .create()
        }

        // Show errors and warnings
        result.diagnostics.forEach { d ->
            val start = d.start.coerceIn(0, (doc?.textLength ?: 0))
            val end = d.end.coerceIn(start, (doc?.textLength ?: start))
            if (end > start) {
                holder.newAnnotation(d.severity, d.message)
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

    
}
