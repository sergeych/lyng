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

/*
 * Grazie-backed annotator for Lyng files.
 *
 * It consumes the MiniAst-driven LyngSpellIndex and, when Grazie is present,
 * tries to run Grazie checks on the extracted TextContent. Results are painted
 * as warnings in the editor. If the Grazie API changes, we use reflection and
 * fail softly with INFO logs (no errors shown to users).
 */
package net.sergeych.lyng.idea.grazie

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.grazie.text.TextContent
import com.intellij.grazie.text.TextContent.TextDomain
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import net.sergeych.lyng.idea.settings.LyngFormatterSettings
import net.sergeych.lyng.idea.spell.LyngSpellIndex

class LyngGrazieAnnotator : ExternalAnnotator<LyngGrazieAnnotator.Input, LyngGrazieAnnotator.Result>(), DumbAware {
    private val log = Logger.getInstance(LyngGrazieAnnotator::class.java)

    companion object {
        // Cache GrammarChecker availability to avoid repeated reflection + noisy logs
        @Volatile
        private var grammarCheckerAvailable: Boolean? = null

        @Volatile
        private var grammarCheckerMissingLogged: Boolean = false

        private fun isGrammarCheckerKnownMissing(): Boolean = (grammarCheckerAvailable == false)

        private fun markGrammarCheckerMissingOnce(log: Logger, message: String) {
            if (!grammarCheckerMissingLogged) {
                // Downgrade to debug to reduce log noise across projects/sessions
                log.debug(message)
                grammarCheckerMissingLogged = true
            }
        }

        private val RETRY_KEY: Key<Long> = Key.create("LYNG_GRAZIE_ANN_RETRY_STAMP")
    }

    data class Input(val modStamp: Long)
    data class Finding(val range: TextRange, val message: String)
    data class Result(val modStamp: Long, val findings: List<Finding>)

    override fun collectInformation(file: PsiFile): Input? {
        val doc: Document = file.viewProvider.document ?: return null
        // Only require Grazie presence; index readiness is checked in apply with a retry.
        val grazie = isGrazieInstalled()
        if (!grazie) {
            log.info("LyngGrazieAnnotator.collectInformation: skip (grazie=false) file='${file.name}'")
            return null
        }
        log.info("LyngGrazieAnnotator.collectInformation: file='${file.name}', modStamp=${doc.modificationStamp}")
        return Input(doc.modificationStamp)
    }

    override fun doAnnotate(collectedInfo: Input?): Result? {
        // All heavy lifting is done in apply where we have the file context
        return collectedInfo?.let { Result(it.modStamp, emptyList()) }
    }

    override fun apply(file: PsiFile, annotationResult: Result?, holder: AnnotationHolder) {
        if (annotationResult == null || !isGrazieInstalled()) return
        val doc = file.viewProvider.document ?: return
        val idx = LyngSpellIndex.getUpToDate(file) ?: run {
            log.info("LyngGrazieAnnotator.apply: index not ready for '${file.name}', scheduling one-shot restart")
            scheduleOneShotRestart(file, annotationResult.modStamp)
            return
        }

        val settings = LyngFormatterSettings.getInstance(file.project)

        // Build TextContent fragments for comments/strings/identifiers according to settings
        val fragments = mutableListOf<Pair<TextContent, TextRange>>()
        try {
            fun addFragments(ranges: List<TextRange>, domain: TextDomain) {
                for (r in ranges) {
                    val local = rangeToTextContent(file, domain, r) ?: continue
                    fragments += local to r
                }
            }
            // Comments always via COMMENTS
            addFragments(idx.comments, TextDomain.COMMENTS)
            // Strings: LITERALS if requested, else COMMENTS if fallback enabled
            if (settings.spellCheckStringLiterals) {
                val domain = if (settings.grazieTreatLiteralsAsComments) TextDomain.COMMENTS else TextDomain.LITERALS
                addFragments(idx.strings, domain)
            }
            // Identifiers via COMMENTS to force painting in 243 unless user disables fallback
            val idsDomain = if (settings.grazieTreatIdentifiersAsComments) TextDomain.COMMENTS else TextDomain.DOCUMENTATION
            addFragments(idx.identifiers, idsDomain)
            log.info(
                "LyngGrazieAnnotator.apply: file='${file.name}', idxCounts ids=${idx.identifiers.size}, comments=${idx.comments.size}, strings=${idx.strings.size}, builtFragments=${fragments.size}"
            )
        } catch (e: Throwable) {
            log.info("LyngGrazieAnnotator: failed to build TextContent fragments: ${e.javaClass.simpleName}: ${e.message}")
            return
        }

        if (fragments.isEmpty()) return

        val findings = mutableListOf<Finding>()
        var totalReturned = 0
        var chosenEntry: String? = null
        for ((content, hostRange) in fragments) {
            try {
                val (typos, entryNote) = runGrazieChecksWithTracing(file, content)
                if (chosenEntry == null) chosenEntry = entryNote
                if (typos != null) {
                    totalReturned += typos.size
                    for (t in typos) {
                        val rel = extractRangeFromTypo(t) ?: continue
                        // Map relative range inside fragment to host file range
                        val abs = TextRange(hostRange.startOffset + rel.startOffset, hostRange.startOffset + rel.endOffset)
                        findings += Finding(abs, extractMessageFromTypo(t) ?: "Spelling/Grammar")
                    }
                }
            } catch (e: Throwable) {
                log.info("LyngGrazieAnnotator: Grazie check failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        log.info("LyngGrazieAnnotator.apply: used=${chosenEntry ?: "<none>"}, totalFindings=$totalReturned, painting=${findings.size}")

        // IMPORTANT: Do NOT fallback to the tiny bundled vocabulary on modern IDEs.
        // If Grazie/Natural Languages processing returned nothing, we simply exit here
        // to avoid low‑quality results from the legacy dictionary.
        if (findings.isEmpty()) return

        for (f in findings) {
            val ab = holder.newAnnotation(HighlightSeverity.INFORMATION, f.message).range(f.range)
            applyTypoStyleIfRequested(file, ab)
            ab.create()
        }
    }

    private fun scheduleOneShotRestart(file: PsiFile, modStamp: Long) {
        try {
            val last = file.getUserData(RETRY_KEY)
            if (last == modStamp) {
                log.info("LyngGrazieAnnotator.restart: already retried for modStamp=$modStamp, skip")
                return
            }
            file.putUserData(RETRY_KEY, modStamp)
            ApplicationManager.getApplication().invokeLater({
                try {
                    DaemonCodeAnalyzer.getInstance(file.project).restart(file)
                    log.info("LyngGrazieAnnotator.restart: daemon restarted for '${file.name}'")
                } catch (e: Throwable) {
                    log.info("LyngGrazieAnnotator.restart failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            })
        } catch (e: Throwable) {
            log.info("LyngGrazieAnnotator.scheduleOneShotRestart failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun isGrazieInstalled(): Boolean {
        return PluginManagerCore.isPluginInstalled(com.intellij.openapi.extensions.PluginId.getId("com.intellij.grazie")) ||
                PluginManagerCore.isPluginInstalled(com.intellij.openapi.extensions.PluginId.getId("tanvd.grazi"))
    }

    private fun rangeToTextContent(file: PsiFile, domain: TextDomain, range: TextRange): TextContent? {
        // Build TextContent via reflection: prefer psiFragment(domain, element)
        return try {
            // Try to find an element that fully covers the target range
            var element = file.findElementAt(range.startOffset) ?: return null
            val start = range.startOffset
            val end = range.endOffset
            while (element.parent != null && (element.textRange.startOffset > start || element.textRange.endOffset < end)) {
                element = element.parent
            }
            if (element.textRange.startOffset > start || element.textRange.endOffset < end) return null
            // In many cases, the element may not span the whole range; use file + range via suitable factory
            val methods = TextContent::class.java.methods.filter { it.name == "psiFragment" }
            val byElementDomain = methods.firstOrNull { it.parameterCount == 2 && it.parameterTypes[0].name.endsWith("PsiElement") }
            if (byElementDomain != null) {
                @Suppress("UNCHECKED_CAST")
                return (byElementDomain.invoke(null, element, domain) as? TextContent)?.let { tc ->
                    val relStart = start - element.textRange.startOffset
                    val relEnd = end - element.textRange.startOffset
                    if (relStart < 0 || relEnd > tc.length || relStart >= relEnd) return null
                    tc.subText(TextRange(relStart, relEnd))
                }
            }
            val byDomainElement = methods.firstOrNull { it.parameterCount == 2 && it.parameterTypes[0].name.endsWith("TextDomain") }
            if (byDomainElement != null) {
                @Suppress("UNCHECKED_CAST")
                return (byDomainElement.invoke(null, domain, element) as? TextContent)?.let { tc ->
                    val relStart = start - element.textRange.startOffset
                    val relEnd = end - element.textRange.startOffset
                    if (relStart < 0 || relEnd > tc.length || relStart >= relEnd) return null
                    tc.subText(TextRange(relStart, relEnd))
                }
            }
            null
        } catch (e: Throwable) {
            log.info("LyngGrazieAnnotator: rangeToTextContent failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun runGrazieChecksWithTracing(file: PsiFile, content: TextContent): Pair<Collection<Any>?, String?> {
        // Try known entry points via reflection to avoid hard dependencies on Grazie internals
        if (isGrammarCheckerKnownMissing()) return null to null
        try {
            // 1) Static GrammarChecker.check(TextContent)
            val checkerCls = try {
                Class.forName("com.intellij.grazie.grammar.GrammarChecker").also { grammarCheckerAvailable = true }
            } catch (t: Throwable) {
                grammarCheckerAvailable = false
                markGrammarCheckerMissingOnce(log, "LyngGrazieAnnotator: GrammarChecker class not found: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            if (checkerCls != null) {
                // Diagnostic: list available 'check' methods once
                runCatching {
                    val checks = checkerCls.methods.filter { it.name == "check" }
                    val sig = checks.joinToString { m ->
                        val params = m.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }
                        "${m.name}$params static=${java.lang.reflect.Modifier.isStatic(m.modifiers)}"
                    }
                    log.info("LyngGrazieAnnotator: GrammarChecker.check candidates: ${if (sig.isEmpty()) "<none>" else sig}")
                }
                checkerCls.methods.firstOrNull { it.name == "check" && it.parameterCount == 1 && it.parameterTypes[0].name.endsWith("TextContent") }?.let { m ->
                    @Suppress("UNCHECKED_CAST")
                    val res = m.invoke(null, content) as? Collection<Any>
                    return res to "GrammarChecker.check(TextContent) static"
                }
                // 2) GrammarChecker.getInstance().check(TextContent)
                val getInstance = checkerCls.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }
                val inst = getInstance?.invoke(null)
                if (inst != null) {
                    val m = checkerCls.methods.firstOrNull { it.name == "check" && it.parameterCount == 1 && it.parameterTypes[0].name.endsWith("TextContent") }
                    if (m != null) {
                        @Suppress("UNCHECKED_CAST")
                        val res = m.invoke(inst, content) as? Collection<Any>
                        return res to "GrammarChecker.getInstance().check(TextContent)"
                    }
                }
                // 3) GrammarChecker.getDefault().check(TextContent)
                val getDefault = checkerCls.methods.firstOrNull { it.name == "getDefault" && it.parameterCount == 0 }
                val def = getDefault?.invoke(null)
                if (def != null) {
                    val m = checkerCls.methods.firstOrNull { it.name == "check" && it.parameterCount == 1 && it.parameterTypes[0].name.endsWith("TextContent") }
                    if (m != null) {
                        @Suppress("UNCHECKED_CAST")
                        val res = m.invoke(def, content) as? Collection<Any>
                        return res to "GrammarChecker.getDefault().check(TextContent)"
                    }
                }
                // 4) Service from project/application: GrammarChecker as a service
                runCatching {
                    val app = com.intellij.openapi.application.ApplicationManager.getApplication()
                    val getService = app::class.java.methods.firstOrNull { it.name == "getService" && it.parameterCount == 1 }
                    val svc = getService?.invoke(app, checkerCls)
                    if (svc != null) {
                        val m = checkerCls.methods.firstOrNull { it.name == "check" && it.parameterCount == 1 && it.parameterTypes[0].name.endsWith("TextContent") }
                        if (m != null) {
                            @Suppress("UNCHECKED_CAST")
                            val res = m.invoke(svc, content) as? Collection<Any>
                            if (res != null) return res to "Application.getService(GrammarChecker).check(TextContent)"
                        }
                    }
                }
                runCatching {
                    val getService = file.project::class.java.methods.firstOrNull { it.name == "getService" && it.parameterCount == 1 }
                    val svc = getService?.invoke(file.project, checkerCls)
                    if (svc != null) {
                        val m = checkerCls.methods.firstOrNull { it.name == "check" && it.parameterCount == 1 && it.parameterTypes[0].name.endsWith("TextContent") }
                        if (m != null) {
                            @Suppress("UNCHECKED_CAST")
                            val res = m.invoke(svc, content) as? Collection<Any>
                            if (res != null) return res to "Project.getService(GrammarChecker).check(TextContent)"
                        }
                    }
                }
            }
            // 5) Fallback: search any public method named check that accepts TextContent in any Grazie class (static)
            val candidateClasses = listOf(
                "com.intellij.grazie.grammar.GrammarChecker",
                "com.intellij.grazie.grammar.GrammarRunner",
                "com.intellij.grazie.grammar.Grammar" // historical names
            )
            for (cn in candidateClasses) {
                val cls = try { Class.forName(cn) } catch (_: Throwable) { continue }
                val m = cls.methods.firstOrNull { it.name == "check" && it.parameterTypes.any { p -> p.name.endsWith("TextContent") } }
                if (m != null) {
                    val args = arrayOfNulls<Any>(m.parameterCount)
                    // place content to the first TextContent parameter; others left null (common defaults)
                    for (i in 0 until m.parameterCount) if (m.parameterTypes[i].name.endsWith("TextContent")) { args[i] = content; break }
                    @Suppress("UNCHECKED_CAST")
                    val res = m.invoke(null, *args) as? Collection<Any>
                    if (res != null) return res to "$cn.${m.name}(TextContent)"
                }
            }
            // 6) Kotlin top-level function: GrammarCheckerKt.check(TextContent)
            runCatching {
                val kt = Class.forName("com.intellij.grazie.grammar.GrammarCheckerKt")
                val m = kt.methods.firstOrNull { it.name == "check" && it.parameterTypes.any { p -> p.name.endsWith("TextContent") } }
                if (m != null) {
                    val args = arrayOfNulls<Any>(m.parameterCount)
                    for (i in 0 until m.parameterCount) if (m.parameterTypes[i].name.endsWith("TextContent")) { args[i] = content; break }
                    @Suppress("UNCHECKED_CAST")
                    val res = m.invoke(null, *args) as? Collection<Any>
                    if (res != null) return res to "GrammarCheckerKt.check(TextContent)"
                }
            }
        } catch (e: Throwable) {
            log.info("LyngGrazieAnnotator: runGrazieChecks reflection failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return null to null
    }

    private fun extractRangeFromTypo(typo: Any): TextRange? {
        // Try to get a relative range from returned Grazie issue/typo via common accessors
        return try {
            // Common getters
            val m1 = typo.javaClass.methods.firstOrNull { it.name == "getRange" && it.parameterCount == 0 }
            val r1 = if (m1 != null) m1.invoke(typo) else null
            when (r1) {
                is TextRange -> return r1
                is IntRange -> return TextRange(r1.first, r1.last + 1)
            }
            val m2 = typo.javaClass.methods.firstOrNull { it.name == "getHighlightRange" && it.parameterCount == 0 }
            val r2 = if (m2 != null) m2.invoke(typo) else null
            when (r2) {
                is TextRange -> return r2
                is IntRange -> return TextRange(r2.first, r2.last + 1)
            }
            // Separate from/to ints
            val fromM = typo.javaClass.methods.firstOrNull { it.name == "getFrom" && it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType }
            val toM = typo.javaClass.methods.firstOrNull { it.name == "getTo" && it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType }
            if (fromM != null && toM != null) {
                val s = (fromM.invoke(typo) as? Int) ?: return null
                val e = (toM.invoke(typo) as? Int) ?: return null
                if (e > s) return TextRange(s, e)
            }
            null
        } catch (_: Throwable) { null }
    }

    private fun extractMessageFromTypo(typo: Any): String? {
        return try {
            val m = typo.javaClass.methods.firstOrNull { it.name == "getMessage" && it.parameterCount == 0 }
            (m?.invoke(typo) as? String)
        } catch (_: Throwable) { null }
    }


    // Fallback that uses legacy SpellCheckerManager (if present) via reflection to validate words in fragments.
    // Returns number of warnings painted.
    private fun fallbackWithLegacySpellcheckerIfAvailable(
        file: PsiFile,
        fragments: List<Pair<TextContent, TextRange>>,
        holder: AnnotationHolder
    ): Int {
        return try {
            val mgrCls = Class.forName("com.intellij.spellchecker.SpellCheckerManager")
            val getInstance = mgrCls.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 1 }
            val isCorrect = mgrCls.methods.firstOrNull { it.name == "isCorrect" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
            if (getInstance == null || isCorrect == null) {
                // No legacy spellchecker API available — fall back to naive painter
                return naiveFallbackPaint(file, fragments, holder)
            }
            val mgr = getInstance.invoke(null, file.project)
            if (mgr == null) {
                // Legacy manager not present for this project — use naive fallback
                return naiveFallbackPaint(file, fragments, holder)
            }
            var painted = 0
            val docText = file.viewProvider.document?.text ?: return 0
            val tokenRegex = Regex("[A-Za-z][A-Za-z0-9_']{2,}")
            for ((content, hostRange) in fragments) {
                val text = try { docText.substring(hostRange.startOffset, hostRange.endOffset) } catch (_: Throwable) { null } ?: continue
                var seen = 0
                var flagged = 0
                for (m in tokenRegex.findAll(text)) {
                    val token = m.value
                    if ('%' in token) continue // skip printf fragments defensively
                    // Split snake_case and camelCase within the token
                    val parts = splitIdentifier(token)
                    for (part in parts) {
                        if (part.length <= 2) continue
                        if (isAllowedWord(part)) continue
                        // Quick allowlist for very common words to reduce noise if dictionaries differ
                        val ok = try { isCorrect.invoke(mgr, part) as? Boolean } catch (_: Throwable) { null }
                        if (ok == false) {
                            // Map part back to original token occurrence within this hostRange
                            val localStart = m.range.first + token.indexOf(part)
                            val localEnd = localStart + part.length
                            val abs = TextRange(hostRange.startOffset + localStart, hostRange.startOffset + localEnd)
                            paintTypoAnnotation(file, holder, abs, part)
                            painted++
                            flagged++
                        }
                        seen++
                    }
                }
                log.info("LyngGrazieAnnotator.fallback: fragment words=$seen, flagged=$flagged")
            }
            painted
        } catch (_: Throwable) {
            // If legacy manager is not available, fall back to a very naive heuristic (no external deps)
            return naiveFallbackPaint(file, fragments, holder)
        }
    }

    private fun naiveFallbackPaint(
        file: PsiFile,
        fragments: List<Pair<TextContent, TextRange>>,
        holder: AnnotationHolder
    ): Int {
        var painted = 0
        val docText = file.viewProvider.document?.text
        val tokenRegex = Regex("[A-Za-z][A-Za-z0-9_']{2,}")
        val baseWords = setOf(
            // small, common vocabulary to catch near-miss typos in typical code/comments
            "comment","comments","error","errors","found","file","not","word","words","count","value","name","class","function","string"
        )
        for ((content, hostRange) in fragments) {
            val text: String? = docText?.let { dt ->
                try { dt.substring(hostRange.startOffset, hostRange.endOffset) } catch (_: Throwable) { null }
            }
            if (text.isNullOrBlank()) continue
            var seen = 0
            var flagged = 0
            for (m in tokenRegex.findAll(text)) {
                val token = m.value
                if ('%' in token) continue
                val parts = splitIdentifier(token)
                for (part in parts) {
                    seen++
                    val lower = part.lowercase()
                    if (lower.length <= 2 || isAllowedWord(part)) continue
                    // Heuristic: no vowels OR 3 repeated chars OR ends with unlikely double consonants
                    val noVowel = lower.none { it in "aeiouy" }
                    val triple = Regex("(.)\\1\\1").containsMatchIn(lower)
                    val dblCons = Regex("[bcdfghjklmnpqrstvwxyz]{2}$").containsMatchIn(lower)
                    var looksWrong = noVowel || triple || dblCons
                    // Additional: low vowel ratio for length>=4
                    if (!looksWrong && lower.length >= 4) {
                        val vowels = lower.count { it in "aeiouy" }
                        val ratio = if (lower.isNotEmpty()) vowels.toDouble() / lower.length else 1.0
                        if (ratio < 0.25) looksWrong = true
                    }
                    // Additional: near-miss to a small base vocabulary (edit distance 1, or 2 for words >=6)
                    if (!looksWrong) {
                        for (bw in baseWords) {
                            val d = editDistance(lower, bw)
                            if (d == 1 || (d == 2 && lower.length >= 6)) { looksWrong = true; break }
                        }
                    }
                    if (looksWrong) {
                        val localStart = m.range.first + token.indexOf(part)
                        val localEnd = localStart + part.length
                        val abs = TextRange(hostRange.startOffset + localStart, hostRange.startOffset + localEnd)
                        paintTypoAnnotation(file, holder, abs, part)
                        painted++
                        flagged++
                    }
                }
            }
            log.info("LyngGrazieAnnotator.fallback(naive): fragment words=$seen, flagged=$flagged")
        }
        return painted
    }

    private fun paintTypoAnnotation(file: PsiFile, holder: AnnotationHolder, range: TextRange, word: String) {
        val settings = LyngFormatterSettings.getInstance(file.project)
        val ab = holder.newAnnotation(HighlightSeverity.INFORMATION, "Possible typo")
            .range(range)
        applyTypoStyleIfRequested(file, ab)
        if (settings.offerLyngTypoQuickFixes) {
            // Offer lightweight fixes; for 243 provide Add-to-dictionary always
            ab.withFix(net.sergeych.lyng.idea.grazie.AddToLyngDictionaryFix(word))
            // Offer "Replace with…" candidates (top 7)
            val cands = suggestReplacements(file, word).take(7)
            for (c in cands) {
                ab.withFix(net.sergeych.lyng.idea.grazie.ReplaceWordFix(range, word, c))
            }
        }
        ab.create()
    }

    private fun applyTypoStyleIfRequested(file: PsiFile, ab: com.intellij.lang.annotation.AnnotationBuilder) {
        val settings = LyngFormatterSettings.getInstance(file.project)
        if (!settings.showTyposWithGreenUnderline) return
        // Use the standard TYPO text attributes key used by the platform
        val TYPO: TextAttributesKey = TextAttributesKey.createTextAttributesKey("TYPO")
        try {
            ab.textAttributes(TYPO)
        } catch (_: Throwable) {
            // some IDEs may not allow setting attributes on INFORMATION; ignore gracefully
        }
    }

    private fun suggestReplacements(file: PsiFile, word: String): List<String> {
        val lower = word.lowercase()
        val fromProject = collectProjectWords(file)
        val fromTech = TechDictionary.allWords()
        val fromEnglish = EnglishDictionary.allWords()
        // Merge with priority: project (p=0), tech (p=1), english (p=2)
        val all = LinkedHashSet<String>()
        all.addAll(fromProject)
        all.addAll(fromTech)
        all.addAll(fromEnglish)
        data class Cand(val w: String, val d: Int, val p: Int)
        val cands = ArrayList<Cand>(32)
        for (w in all) {
            if (w == lower) continue
            if (kotlin.math.abs(w.length - lower.length) > 2) continue
            val d = editDistance(lower, w)
            val p = when {
                w in fromProject -> 0
                w in fromTech -> 1
                else -> 2
            }
            cands += Cand(w, d, p)
        }
        cands.sortWith(compareBy<Cand> { it.d }.thenBy { it.p }.thenBy { it.w })
        // Return a larger pool so callers can choose desired display count
        return cands.take(16).map { it.w }
    }

    private fun collectProjectWords(file: PsiFile): Set<String> {
        // Simple approach: use current file text; can be extended to project scanning later
        val text = file.viewProvider.document?.text ?: return emptySet()
        val out = LinkedHashSet<String>()
        val tokenRegex = Regex("[A-Za-z][A-Za-z0-9_']{2,}")
        for (m in tokenRegex.findAll(text)) {
            val parts = splitIdentifier(m.value)
            parts.forEach { out += it.lowercase() }
        }
        // Include learned words
        val settings = LyngFormatterSettings.getInstance(file.project)
        out.addAll(settings.learnedWords.map { it.lowercase() })
        return out
    }

    private fun splitIdentifier(token: String): List<String> {
        // Split on underscores and camelCase boundaries
        val unders = token.split('_').filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        val camelBoundary = Regex("(?<=[a-z])(?=[A-Z])")
        for (u in unders) out += u.split(camelBoundary).filter { it.isNotBlank() }
        return out
    }

    private fun isAllowedWord(w: String): Boolean {
        val s = w.lowercase()
        return s in setOf(
            // common code words / language keywords to avoid noise
            "val","var","fun","class","interface","enum","type","import","package","return","if","else","when","while","for","try","catch","finally","true","false","null",
            "abstract","closed","override",
            // very common English words
            "the","and","or","not","with","from","into","this","that","file","found","count","name","value","object"
        )
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,                // deletion
                    dp[j - 1] + 1,            // insertion
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1 // substitution
                )
                prev = temp
            }
        }
        return dp[b.length]
    }
}
