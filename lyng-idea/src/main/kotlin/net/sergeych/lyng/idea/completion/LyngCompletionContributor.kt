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

/*
 * Lightweight BASIC completion for Lyng, MVP version.
 * Uses MiniAst (best-effort) + BuiltinDocRegistry to suggest symbols.
 */
package net.sergeych.lyng.idea.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.highlight.LyngTokenTypes
import net.sergeych.lyng.idea.settings.LyngFormatterSettings
import net.sergeych.lyng.idea.util.DocsBootstrap
import net.sergeych.lyng.idea.util.LyngAstManager
import net.sergeych.lyng.idea.util.TextCtx
import net.sergeych.lyng.miniast.*

class LyngCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(LyngLanguage),
            Provider
        )
    }

    private object Provider : CompletionProvider<CompletionParameters>() {
        private val log = Logger.getInstance(LyngCompletionContributor::class.java)
        private const val DEBUG_COMPLETION = false

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            // Ensure external/bundled docs are registered (e.g., lyng.io.fs with Path)
            DocsBootstrap.ensure()
            // Ensure stdlib Obj*-defined docs (e.g., String methods via ObjString.addFnDoc) are initialized
            StdlibDocsBootstrap.ensure()
            val file: PsiFile = parameters.originalFile
            if (file.language != LyngLanguage) return

            // Disable completion inside comments
            val pos = parameters.position
            val et = pos.node.elementType
            if (et == LyngTokenTypes.LINE_COMMENT || et == LyngTokenTypes.BLOCK_COMMENT) return

            // Feature toggle: allow turning completion off from settings
            val settings = LyngFormatterSettings.getInstance(file.project)
            if (!settings.enableLyngCompletionExperimental) return
            val document: Document = file.viewProvider.document ?: return
            val text = document.text
            val caret = parameters.offset.coerceIn(0, text.length)

            val prefix = TextCtx.prefixAt(text, caret)
            val withPrefix = result.withPrefixMatcher(prefix).caseInsensitive()

            // Emission with cap
            val cap = 200
            var added = 0
            val emit: (com.intellij.codeInsight.lookup.LookupElement) -> Unit = { le ->
                if (added < cap) {
                    withPrefix.addElement(le)
                    added++
                }
            }

            // Determine if we are in member context (dot before caret or before word start)
            val wordRange = TextCtx.wordRangeAt(text, caret)
            val memberDotPos = (wordRange?.let { TextCtx.findDotLeft(text, it.startOffset) })
                ?: TextCtx.findDotLeft(text, caret)
            if (DEBUG_COMPLETION) {
                log.info("[LYNG_DEBUG] Completion: caret=$caret prefix='${prefix}' memberDotPos=${memberDotPos} file='${file.name}'")
            }

            // Build MiniAst (cached) for both global and member contexts to enable local class/val inference
            val mini = LyngAstManager.getMiniAst(file)
            val binding = LyngAstManager.getBinding(file)

            // Delegate computation to the shared engine to keep behavior in sync with tests
            val engineItems = try {
                runBlocking { CompletionEngineLight.completeSuspend(text, caret, mini, binding) }
            } catch (t: Throwable) {
                if (DEBUG_COMPLETION) log.warn("[LYNG_DEBUG] Engine completion failed: ${t.message}")
                emptyList()
            }
            if (DEBUG_COMPLETION) {
                val preview = engineItems.take(10).joinToString { it.name }
                log.info("[LYNG_DEBUG] Engine items: count=${engineItems.size} preview=[${preview}]")
            }

            // If we are in member context and the engine produced nothing, try a guarded local fallback
            if (memberDotPos != null && engineItems.isEmpty()) {
                if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Fallback: engine returned 0 in member context; trying local inference")
                // Build imported modules from text (lenient) + stdlib; avoid heavy MiniAst here
                val fromText = DocLookupUtils.extractImportsFromText(text)
                val imported = LinkedHashSet<String>().apply {
                    fromText.forEach { add(it) }
                    add("lyng.stdlib")
                }.toList()

                // Try inferring return/receiver class around the dot
                val inferred =
                    // Prefer MiniAst-based inference (return type from member call or receiver type)
                    DocLookupUtils.guessReturnClassFromMemberCallBeforeMini(mini, text, memberDotPos, imported, binding)
                        ?: DocLookupUtils.guessReceiverClassViaMini(mini, text, memberDotPos, imported, binding)
                        ?: 
                    DocLookupUtils.guessReturnClassFromMemberCallBefore(text, memberDotPos, imported, mini)
                        ?: DocLookupUtils.guessReturnClassFromTopLevelCallBefore(text, memberDotPos, imported, mini)
                        ?: DocLookupUtils.guessReturnClassAcrossKnownCallees(text, memberDotPos, imported, mini)
                        ?: DocLookupUtils.guessReceiverClass(text, memberDotPos, imported, mini)

                if (inferred != null) {
                    if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Fallback inferred receiver/return class='$inferred' — offering its members")
                    offerMembers(emit, imported, inferred, sourceText = text, mini = mini)
                    return
                } else {
                    if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Fallback could not infer class; keeping list empty (no globals after dot)")
                    return
                }
            }

            // Render engine items
            for (ci in engineItems) {
                val builder = when (ci.kind) {
                    Kind.Function -> LookupElementBuilder.create(ci.name)
                        .withIcon(AllIcons.Nodes.Function)
                        .let { b -> if (!ci.tailText.isNullOrBlank()) b.withTailText(ci.tailText, true) else b }
                        .let { b -> if (!ci.typeText.isNullOrBlank()) b.withTypeText(ci.typeText, true) else b }
                        .withInsertHandler(ParenInsertHandler)
                    Kind.Method -> LookupElementBuilder.create(ci.name)
                        .withIcon(AllIcons.Nodes.Method)
                        .let { b -> if (!ci.tailText.isNullOrBlank()) b.withTailText(ci.tailText, true) else b }
                        .let { b -> if (!ci.typeText.isNullOrBlank()) b.withTypeText(ci.typeText, true) else b }
                        .withInsertHandler(ParenInsertHandler)
                    Kind.Class_ -> LookupElementBuilder.create(ci.name)
                        .withIcon(AllIcons.Nodes.Class)
                    Kind.Enum -> LookupElementBuilder.create(ci.name)
                        .withIcon(AllIcons.Nodes.Enum)
                    Kind.Value -> LookupElementBuilder.create(ci.name)
                        .withIcon(AllIcons.Nodes.Variable)
                        .let { b -> if (!ci.typeText.isNullOrBlank()) b.withTypeText(ci.typeText, true) else b }
                    Kind.Field -> LookupElementBuilder.create(ci.name)
                        .withIcon(AllIcons.Nodes.Field)
                        .let { b -> if (!ci.typeText.isNullOrBlank()) b.withTypeText(ci.typeText, true) else b }
                }
                if (ci.priority != 0.0) {
                    emit(PrioritizedLookupElement.withPriority(builder, ci.priority))
                } else {
                    emit(builder)
                }
            }
            // In member context, ensure stdlib extension-like methods (e.g., String.re) are present
            if (memberDotPos != null) {
                val existing = engineItems.map { it.name }.toMutableSet()
                val fromText = DocLookupUtils.extractImportsFromText(text)
                val imported = LinkedHashSet<String>().apply {
                    fromText.forEach { add(it) }
                    add("lyng.stdlib")
                }.toList()
                val inferredClass =
                    DocLookupUtils.guessReturnClassFromMemberCallBeforeMini(mini, text, memberDotPos, imported, binding)
                        ?: DocLookupUtils.guessReceiverClassViaMini(mini, text, memberDotPos, imported, binding)
                        ?: DocLookupUtils.guessReturnClassFromMemberCallBefore(text, memberDotPos, imported, mini)
                        ?: DocLookupUtils.guessReturnClassFromTopLevelCallBefore(text, memberDotPos, imported, mini)
                        ?: DocLookupUtils.guessReturnClassAcrossKnownCallees(text, memberDotPos, imported, mini)
                        ?: DocLookupUtils.guessReceiverClass(text, memberDotPos, imported, mini)
                if (!inferredClass.isNullOrBlank()) {
                    val ext = DocLookupUtils.collectExtensionMemberNames(imported, inferredClass, mini)
                    if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Post-engine extension check for $inferredClass: ${ext}")
                    for (name in ext) {
                        if (existing.contains(name)) continue
                        val resolved = DocLookupUtils.resolveMemberWithInheritance(imported, inferredClass, name, mini)
                        if (resolved != null) {
                            val m = resolved.second
                            val builder = when (m) {
                                is MiniMemberFunDecl -> {
                                    val params = m.params.joinToString(", ") { it.name }
                                    val ret = typeOf(m.returnType)
                                    LookupElementBuilder.create(name)
                                        .withIcon(AllIcons.Nodes.Method)
                                        .withTailText("($params)", true)
                                        .withTypeText(ret, true)
                                        .withInsertHandler(ParenInsertHandler)
                                }
                                is MiniFunDecl -> {
                                    val params = m.params.joinToString(", ") { it.name }
                                    val ret = typeOf(m.returnType)
                                    LookupElementBuilder.create(name)
                                        .withIcon(AllIcons.Nodes.Method)
                                        .withTailText("($params)", true)
                                        .withTypeText(ret, true)
                                        .withInsertHandler(ParenInsertHandler)
                                }
                                is MiniMemberValDecl -> {
                                    LookupElementBuilder.create(name)
                                        .withIcon(if (m.mutable) AllIcons.Nodes.Variable else AllIcons.Nodes.Field)
                                        .withTypeText(typeOf(m.type), true)
                                }
                                is MiniValDecl -> {
                                    LookupElementBuilder.create(name)
                                        .withIcon(if (m.mutable) AllIcons.Nodes.Variable else AllIcons.Nodes.Field)
                                        .withTypeText(typeOf(m.type), true)
                                }
                                else -> {
                                    LookupElementBuilder.create(name)
                                        .withIcon(AllIcons.Nodes.Method)
                                        .withTailText("()", true)
                                        .withInsertHandler(ParenInsertHandler)
                                }
                            }
                            emit(builder)
                            existing.add(name)
                        } else {
                            // Fallback: emit simple method name without detailed types
                            val builder = LookupElementBuilder.create(name)
                                .withIcon(AllIcons.Nodes.Method)
                                .withTailText("()", true)
                                .withInsertHandler(ParenInsertHandler)
                            emit(builder)
                            existing.add(name)
                        }
                    }
                }
            }
            // If in member context and engine items are suspiciously sparse, try to enrich via local inference + offerMembers
            if (memberDotPos != null && engineItems.size < 3) {
                if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Engine produced only ${engineItems.size} items in member context — trying enrichment")
                val fromText = DocLookupUtils.extractImportsFromText(text)
                val imported = LinkedHashSet<String>().apply {
                    fromText.forEach { add(it) }
                    add("lyng.stdlib")
                }.toList()
                val inferred =
                    DocLookupUtils.guessReturnClassFromMemberCallBeforeMini(mini, text, memberDotPos, imported, binding)
                        ?: DocLookupUtils.guessReceiverClassViaMini(mini, text, memberDotPos, imported, binding)
                        ?: DocLookupUtils.guessReturnClassFromMemberCallBefore(text, memberDotPos, imported, mini)
                        ?: DocLookupUtils.guessReturnClassFromTopLevelCallBefore(text, memberDotPos, imported, mini)
                        ?: DocLookupUtils.guessReturnClassAcrossKnownCallees(text, memberDotPos, imported, mini)
                        ?: DocLookupUtils.guessReceiverClass(text, memberDotPos, imported, mini)
                if (inferred != null) {
                    if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Enrichment inferred class='$inferred' — offering its members")
                    offerMembers(emit, imported, inferred, sourceText = text, mini = mini)
                }
            }
            return
        }

        private fun offerDecl(emit: (com.intellij.codeInsight.lookup.LookupElement) -> Unit, d: MiniDecl) {
            val name = d.name
            val builder = when (d) {
                is MiniFunDecl -> {
                    val params = d.params.joinToString(", ") { it.name }
                    val ret = typeOf(d.returnType)
                    val tail = "(${params})"
                    LookupElementBuilder.create(name)
                        .withIcon(AllIcons.Nodes.Function)
                        .withTailText(tail, true)
                        .withTypeText(ret, true)
                        .withInsertHandler(ParenInsertHandler)
                }
                is MiniClassDecl -> LookupElementBuilder.create(name)
                    .withIcon(AllIcons.Nodes.Class)
                is MiniValDecl -> {
                    val kindIcon = if (d.mutable) AllIcons.Nodes.Variable else AllIcons.Nodes.Field
                    LookupElementBuilder.create(name)
                        .withIcon(kindIcon)
                        .withTypeText(typeOf(d.type), true)
                }
                is MiniEnumDecl -> LookupElementBuilder.create(name)
                    .withIcon(AllIcons.Nodes.Enum)
            }
            emit(builder)
        }

        private object ParenInsertHandler : InsertHandler<com.intellij.codeInsight.lookup.LookupElement> {
            override fun handleInsert(context: InsertionContext, item: com.intellij.codeInsight.lookup.LookupElement) {
                val doc = context.document
                val tailOffset = context.tailOffset
                val nextChar = doc.charsSequence.getOrNull(tailOffset)
                if (nextChar != '(') {
                    doc.insertString(tailOffset, "()")
                    context.editor.caretModel.moveToOffset(tailOffset + 1)
                }
            }
        }

        // --- Member completion helpers ---

        private fun offerMembers(
            emit: (com.intellij.codeInsight.lookup.LookupElement) -> Unit,
            imported: List<String>,
            className: String,
            staticOnly: Boolean = false
        , sourceText: String,
        mini: MiniScript? = null
        ) {
            val classes = DocLookupUtils.aggregateClasses(imported, mini)
            if (DEBUG_COMPLETION) {
                val keys = classes.keys.joinToString(", ")
                log.info("[LYNG_DEBUG] offerMembers: imported=${imported} classes=[${keys}] target=${className}")
            }
            val visited = mutableSetOf<String>()
            // Collect separated to keep tiers: direct first, then inherited
            val directMap = LinkedHashMap<String, MutableList<MiniMemberDecl>>()
            val inheritedMap = LinkedHashMap<String, MutableList<MiniMemberDecl>>()

            // 0) Prefer locally-declared class members (same-file) when available
            val localClass = mini?.declarations?.filterIsInstance<MiniClassDecl>()?.firstOrNull { it.name == className }
            if (localClass != null) {
                for (m in localClass.members) {
                    val list = directMap.getOrPut(m.name) { mutableListOf() }
                    list.add(m)
                }
                // If MiniAst didn't populate members (empty), try to scan class body text for member signatures
                if (localClass.members.isEmpty()) {
                    val scanned = DocLookupUtils.scanLocalClassMembersFromText(mini, text = sourceText, cls = localClass)
                    if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Local scan for class ${localClass.name}: found ${scanned.size} members -> ${scanned.keys}")
                    for ((name, sig) in scanned) {
                        when (sig.kind) {
                            "fun" -> {
                                val builder = LookupElementBuilder.create(name)
                                    .withIcon(AllIcons.Nodes.Method)
                                    .withTailText("(" + (sig.params?.joinToString(", ") ?: "") + ")", true)
                                    .let { b -> sig.typeText?.let { b.withTypeText(": $it", true) } ?: b }
                                    .withInsertHandler(ParenInsertHandler)
                                emit(builder)
                            }
                            "val", "var" -> {
                                val builder = LookupElementBuilder.create(name)
                                    .withIcon(if (sig.kind == "var") AllIcons.Nodes.Variable else AllIcons.Nodes.Field)
                                    .let { b -> sig.typeText?.let { b.withTypeText(": $it", true) } ?: b }
                                emit(builder)
                            }
                        }
                    }
                }
            }

            fun addMembersOf(clsName: String, tierDirect: Boolean) {
                val cls = classes[clsName] ?: return
                val target = if (tierDirect) directMap else inheritedMap
                for (m in cls.members) {
                    if (staticOnly) {
                        // Filter only static members in namespace/static context
                        when (m) {
                            is MiniMemberFunDecl -> if (!m.isStatic) continue
                            is MiniMemberValDecl -> if (!m.isStatic) continue
                            is MiniInitDecl -> continue
                        }
                    }
                    val list = target.getOrPut(m.name) { mutableListOf() }
                    list.add(m)
                }
                // Then inherited
                for (base in cls.bases) {
                    if (visited.add(base)) addMembersOf(base, false)
                }
            }

            visited.add(className)
            addMembersOf(className, true)
            if (DEBUG_COMPLETION) {
                log.info("[LYNG_DEBUG] offerMembers: direct=${directMap.size} inherited=${inheritedMap.size} for ${className}")
            }

            // If the docs model lacks explicit bases for some core container classes,
            // conservatively supplement with preferred parents to expose common ops.
            fun supplementPreferredBases(receiver: String) {
                // Preference/known lineage map kept tiny and safe
                val extras = when (receiver) {
                    "List" -> listOf("Collection", "Iterable")
                    "Array" -> listOf("Collection", "Iterable")
                    // In practice, many high-level ops users expect on iteration live on Iterable.
                    // For editor assistance, expose Iterable ops for Iterator receivers too.
                    "Iterator" -> listOf("Iterable")
                    else -> emptyList()
                }
                for (base in extras) {
                    if (visited.add(base)) addMembersOf(base, false)
                }
            }
            supplementPreferredBases(className)

            fun emitGroup(map: LinkedHashMap<String, MutableList<MiniMemberDecl>>, groupPriority: Double) {
                val keys = map.keys.sortedBy { it.lowercase() }
                for (name in keys) {
                    val list = map[name] ?: continue
                    // Choose a representative for display:
                    // 1) Prefer a method with return type AND parameters
                    // 2) Prefer a method with parameters
                    // 3) Prefer a method with return type
                    // 4) Else any method
                    // 5) Else the first variant
                    val rep =
                        list.asSequence().filterIsInstance<MiniMemberFunDecl>()
                            .firstOrNull { it.returnType != null && it.params.isNotEmpty() }
                            ?: list.asSequence().filterIsInstance<MiniMemberFunDecl>()
                                .firstOrNull { it.params.isNotEmpty() }
                            ?: list.asSequence().filterIsInstance<MiniMemberFunDecl>()
                                .firstOrNull { it.returnType != null }
                            ?: list.firstOrNull { it is MiniMemberFunDecl }
                            ?: list.first()
                    when (rep) {
                        is MiniMemberFunDecl -> {
                            val params = rep.params.joinToString(", ") { it.name }
                            val ret = typeOf(rep.returnType)
                            val extra = list.count { it is MiniMemberFunDecl } - 1
                            val overloads = if (extra > 0) " (+$extra overloads)" else ""
                            val tail = "(${params})$overloads"
                            val icon = AllIcons.Nodes.Method
                            val builder = LookupElementBuilder.create(name)
                                .withIcon(icon)
                                .withTailText(tail, true)
                                .withTypeText(ret, true)
                                .withInsertHandler(ParenInsertHandler)
                            if (groupPriority != 0.0) {
                                emit(PrioritizedLookupElement.withPriority(builder, groupPriority))
                            } else {
                                emit(builder)
                            }
                        }
                        is MiniMemberValDecl -> {
                            val icon = if (rep.mutable) AllIcons.Nodes.Variable else AllIcons.Nodes.Field
                            // Prefer a field variant with known type if available
                            val chosen = list.asSequence()
                                .filterIsInstance<MiniMemberValDecl>()
                                .firstOrNull { it.type != null } ?: rep
                            val builder = LookupElementBuilder.create(name)
                                .withIcon(icon)
                                .withTypeText(typeOf(chosen.type), true)
                            if (groupPriority != 0.0) {
                                emit(PrioritizedLookupElement.withPriority(builder, groupPriority))
                            } else {
                                emit(builder)
                            }
                        }
                        is MiniInitDecl -> {}
                    }
                }
            }

            // Emit what we have first
            emitGroup(directMap, 100.0)
            emitGroup(inheritedMap, 0.0)

            // If suggestions are suspiciously sparse for known container classes,
            // try to conservatively supplement using a curated list resolved via docs registry.
            val totalSuggested = directMap.size + inheritedMap.size
            val isContainer = className in setOf("Iterator", "Iterable", "Collection", "List", "Array")
            if (isContainer && totalSuggested < 3) {
                if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Supplementing members for $className; had=$totalSuggested")
                val common = when (className) {
                    "Iterator" -> listOf(
                        "hasNext", "next", "forEach", "map", "filter", "take", "drop", "toList", "count", "any", "all"
                    )
                    else -> listOf(
                        // Iterable/Collection/List/Array common ops
                        "size", "isEmpty", "map", "flatMap", "filter", "first", "last", "contains",
                        "any", "all", "count", "forEach", "toList", "toSet"
                    )
                }
                val already = (directMap.keys + inheritedMap.keys).toMutableSet()
                for (name in common) {
                    if (name in already) continue
                    // Try resolve across classes first to get types/params; if it fails, emit a synthetic safe suggestion.
                    val resolved = DocLookupUtils.findMemberAcrossClasses(imported, name, mini)
                    if (resolved != null) {
                        val member = resolved.second
                        val builder = when (member) {
                            is MiniMemberFunDecl -> {
                                val params = member.params.joinToString(", ") { it.name }
                                val ret = typeOf(member.returnType)
                                LookupElementBuilder.create(name)
                                    .withIcon(AllIcons.Nodes.Method)
                                    .withTailText("($params)", true)
                                    .withTypeText(ret, true)
                                    .withInsertHandler(ParenInsertHandler)
                            }
                            is MiniFunDecl -> {
                                val params = member.params.joinToString(", ") { it.name }
                                val ret = typeOf(member.returnType)
                                LookupElementBuilder.create(name)
                                    .withIcon(AllIcons.Nodes.Method)
                                    .withTailText("($params)", true)
                                    .withTypeText(ret, true)
                                    .withInsertHandler(ParenInsertHandler)
                            }
                            is MiniMemberValDecl -> {
                                LookupElementBuilder.create(name)
                                    .withIcon(if (member.mutable) AllIcons.Nodes.Variable else AllIcons.Nodes.Field)
                                    .withTypeText(typeOf(member.type), true)
                            }
                            is MiniValDecl -> {
                                LookupElementBuilder.create(name)
                                    .withIcon(if (member.mutable) AllIcons.Nodes.Variable else AllIcons.Nodes.Field)
                                    .withTypeText(typeOf(member.type), true)
                            }
                            else -> {
                                LookupElementBuilder.create(name)
                                    .withIcon(AllIcons.Nodes.Method)
                                    .withTailText("()", true)
                                    .withInsertHandler(ParenInsertHandler)
                            }
                        }
                        emit(PrioritizedLookupElement.withPriority(builder, 50.0))
                        already.add(name)
                    } else {
                        // Synthetic fallback: method without detailed params/types to improve UX in absence of docs
                        val isProperty = name in setOf("size", "length")
                        val builder = if (isProperty) {
                            LookupElementBuilder.create(name)
                                .withIcon(AllIcons.Nodes.Field)
                        } else {
                            LookupElementBuilder.create(name)
                                .withIcon(AllIcons.Nodes.Method)
                                .withTailText("()", true)
                                .withInsertHandler(ParenInsertHandler)
                        }
                        emit(PrioritizedLookupElement.withPriority(builder, 50.0))
                        already.add(name)
                    }
                }
            }

            // Supplement with stdlib extension members defined in root.lyng (e.g., fun String.trim(...))
            run {
                val already = (directMap.keys + inheritedMap.keys).toMutableSet()
                val ext = BuiltinDocRegistry.extensionMemberNamesFor(className)
                if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Extensions for $className: count=${ext.size} -> ${ext}")
                for (name in ext) {
                    if (already.contains(name)) continue
                    // Try to resolve full signature via registry first to get params and return type
                    val resolved = DocLookupUtils.resolveMemberWithInheritance(imported, className, name, mini)
                    if (resolved != null) {
                        val m = resolved.second
                        val builder = when (m) {
                            is MiniMemberFunDecl -> {
                                val params = m.params.joinToString(", ") { it.name }
                                val ret = typeOf(m.returnType)
                                LookupElementBuilder.create(name)
                                    .withIcon(AllIcons.Nodes.Method)
                                    .withTailText("($params)", true)
                                    .withTypeText(ret, true)
                                    .withInsertHandler(ParenInsertHandler)
                            }
                            is MiniFunDecl -> {
                                val params = m.params.joinToString(", ") { it.name }
                                val ret = typeOf(m.returnType)
                                LookupElementBuilder.create(name)
                                    .withIcon(AllIcons.Nodes.Method)
                                    .withTailText("($params)", true)
                                    .withTypeText(ret, true)
                                    .withInsertHandler(ParenInsertHandler)
                            }
                            is MiniMemberValDecl -> {
                                LookupElementBuilder.create(name)
                                    .withIcon(if (m.mutable) AllIcons.Nodes.Variable else AllIcons.Nodes.Field)
                                    .withTypeText(typeOf(m.type), true)
                            }
                            is MiniValDecl -> {
                                LookupElementBuilder.create(name)
                                    .withIcon(if (m.mutable) AllIcons.Nodes.Variable else AllIcons.Nodes.Field)
                                    .withTypeText(typeOf(m.type), true)
                            }
                            else -> {
                                LookupElementBuilder.create(name)
                                    .withIcon(AllIcons.Nodes.Method)
                                    .withTailText("()", true)
                                    .withInsertHandler(ParenInsertHandler)
                            }
                        }
                        emit(PrioritizedLookupElement.withPriority(builder, 50.0))
                        already.add(name)
                        continue
                    }
                    // Fallback: emit without detailed types if we couldn't resolve
                    val builder = LookupElementBuilder.create(name)
                        .withIcon(AllIcons.Nodes.Method)
                        .withTailText("()", true)
                        .withInsertHandler(ParenInsertHandler)
                    emit(PrioritizedLookupElement.withPriority(builder, 50.0))
                    already.add(name)
                }
            }
        }


        private fun typeOf(t: MiniTypeRef?): String {
            val s = DocLookupUtils.typeOf(t)
            return if (s.isEmpty()) "" else ": $s"
        }
    }
}
