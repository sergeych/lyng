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
import com.intellij.openapi.util.Key
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Source
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.idea.LyngLanguage
import net.sergeych.lyng.idea.settings.LyngFormatterSettings
import net.sergeych.lyng.idea.util.DocsBootstrap
import net.sergeych.lyng.idea.util.IdeLenientImportProvider
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
            val mini = buildMiniAstCached(file, text)

            // Delegate computation to the shared engine to keep behavior in sync with tests
            val engineItems = try {
                runBlocking { CompletionEngineLight.completeSuspend(text, caret) }
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
                val fromText = extractImportsFromText(text)
                val imported = LinkedHashSet<String>().apply {
                    fromText.forEach { add(it) }
                    add("lyng.stdlib")
                }.toList()

                // Try inferring return/receiver class around the dot
                val inferred =
                    // Prefer MiniAst-based inference (return type from member call or receiver type)
                    guessReturnClassFromMemberCallBeforeMini(mini, text, memberDotPos, imported)
                        ?: guessReceiverClassViaMini(mini, text, memberDotPos, imported)
                        ?: 
                    guessReturnClassFromMemberCallBefore(text, memberDotPos, imported)
                        ?: guessReturnClassFromTopLevelCallBefore(text, memberDotPos, imported)
                        ?: guessReturnClassAcrossKnownCallees(text, memberDotPos, imported)
                        ?: guessReceiverClass(text, memberDotPos, imported)

                if (inferred != null) {
                    if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Fallback inferred receiver/return class='$inferred' — offering its members")
                    offerMembers(emit, imported, inferred, sourceText = text, mini = mini)
                    return
                } else {
                    if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Fallback could not infer class; keeping list empty (no globals after dot)")
                    return
                }
            }

            // In global context, add params in scope first (engine does not include them)
            if (memberDotPos == null && mini != null) {
                offerParamsInScope(emit, mini, text, caret)
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
                    Kind.Value -> LookupElementBuilder.create(ci.name)
                        .withIcon(AllIcons.Nodes.Field)
                        .let { b -> if (!ci.typeText.isNullOrBlank()) b.withTypeText(ci.typeText, true) else b }
                    Kind.Field -> LookupElementBuilder.create(ci.name)
                        .withIcon(AllIcons.Nodes.Field)
                        .let { b -> if (!ci.typeText.isNullOrBlank()) b.withTypeText(ci.typeText, true) else b }
                }
                emit(builder)
            }
            // In member context, ensure stdlib extension-like methods (e.g., String.re) are present
            if (memberDotPos != null) {
                val existing = engineItems.map { it.name }.toMutableSet()
                val fromText = extractImportsFromText(text)
                val imported = LinkedHashSet<String>().apply {
                    fromText.forEach { add(it) }
                    add("lyng.stdlib")
                }.toList()
                val inferredClass =
                    guessReturnClassFromMemberCallBeforeMini(mini, text, memberDotPos, imported)
                        ?: guessReceiverClassViaMini(mini, text, memberDotPos, imported)
                        ?: guessReturnClassFromMemberCallBefore(text, memberDotPos, imported)
                        ?: guessReturnClassFromTopLevelCallBefore(text, memberDotPos, imported)
                        ?: guessReturnClassAcrossKnownCallees(text, memberDotPos, imported)
                        ?: guessReceiverClass(text, memberDotPos, imported)
                if (!inferredClass.isNullOrBlank()) {
                    val ext = BuiltinDocRegistry.extensionMethodNamesFor(inferredClass)
                    if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Post-engine extension check for $inferredClass: ${'$'}{ext}")
                    for (name in ext) {
                        if (existing.contains(name)) continue
                        val resolved = DocLookupUtils.resolveMemberWithInheritance(imported, inferredClass, name)
                        if (resolved != null) {
                            when (val member = resolved.second) {
                                is MiniMemberFunDecl -> {
                                    val params = member.params.joinToString(", ") { it.name }
                                    val ret = typeOf(member.returnType)
                                    val builder = LookupElementBuilder.create(name)
                                        .withIcon(AllIcons.Nodes.Method)
                                        .withTailText("(${ '$' }params)", true)
                                        .withTypeText(ret, true)
                                        .withInsertHandler(ParenInsertHandler)
                                    emit(builder)
                                    existing.add(name)
                                }
                                is MiniMemberValDecl -> {
                                    val builder = LookupElementBuilder.create(name)
                                        .withIcon(if (member.mutable) AllIcons.Nodes.Variable else AllIcons.Nodes.Field)
                                        .withTypeText(typeOf(member.type), true)
                                    emit(builder)
                                    existing.add(name)
                                }
                            }
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
                val fromText = extractImportsFromText(text)
                val imported = LinkedHashSet<String>().apply {
                    fromText.forEach { add(it) }
                    add("lyng.stdlib")
                }.toList()
                val inferred =
                    guessReturnClassFromMemberCallBeforeMini(mini, text, memberDotPos, imported)
                        ?: guessReceiverClassViaMini(mini, text, memberDotPos, imported)
                        ?: guessReturnClassFromMemberCallBefore(text, memberDotPos, imported)
                        ?: guessReturnClassFromTopLevelCallBefore(text, memberDotPos, imported)
                        ?: guessReturnClassAcrossKnownCallees(text, memberDotPos, imported)
                        ?: guessReceiverClass(text, memberDotPos, imported)
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
                else -> LookupElementBuilder.create(name)
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
            // Ensure modules are seeded in the registry (triggers lazy stdlib build too)
            for (m in imported) BuiltinDocRegistry.docsForModule(m)
            val classes = DocLookupUtils.aggregateClasses(imported)
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
                    val scanned = scanLocalClassMembersFromText(mini, text = sourceText, cls = localClass)
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

            fun emitGroup(map: LinkedHashMap<String, MutableList<MiniMemberDecl>>) {
                val keys = map.keys.sortedBy { it.lowercase() }
                for (name in keys) {
                    val list = map[name] ?: continue
                    // Choose a representative for display:
                    // 1) Prefer a method with a known return type
                    // 2) Else any method
                    // 3) Else the first variant
                    val rep =
                        list.asSequence()
                            .filterIsInstance<MiniMemberFunDecl>()
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
                            emit(builder)
                        }
                        is MiniMemberValDecl -> {
                            val icon = if (rep.mutable) AllIcons.Nodes.Variable else AllIcons.Nodes.Field
                            // Prefer a field variant with known type if available
                            val chosen = list.asSequence()
                                .filterIsInstance<MiniMemberValDecl>()
                                .firstOrNull { it.type != null } ?: rep
                            val builder = LookupElementBuilder.create(name)
                                .withIcon(icon)
                                .withTypeText(typeOf((chosen as MiniMemberValDecl).type), true)
                            emit(builder)
                        }
                    }
                }
            }

            // Emit what we have first
            emitGroup(directMap)
            emitGroup(inheritedMap)

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
                    val resolved = DocLookupUtils.findMemberAcrossClasses(imported, name)
                    if (resolved != null) {
                        val member = resolved.second
                        when (member) {
                            is MiniMemberFunDecl -> {
                                val params = member.params.joinToString(", ") { it.name }
                                val ret = typeOf(member.returnType)
                                val builder = LookupElementBuilder.create(name)
                                    .withIcon(AllIcons.Nodes.Method)
                                    .withTailText("(${params})", true)
                                    .withTypeText(ret, true)
                                    .withInsertHandler(ParenInsertHandler)
                                emit(builder)
                                already.add(name)
                            }
                            is MiniMemberValDecl -> {
                                val builder = LookupElementBuilder.create(name)
                                    .withIcon(AllIcons.Nodes.Field)
                                    .withTypeText(typeOf(member.type), true)
                                emit(builder)
                                already.add(name)
                            }
                        }
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
                        emit(builder)
                        already.add(name)
                    }
                }
            }

            // Supplement with stdlib extension-like methods defined in root.lyng (e.g., fun String.trim(...))
            run {
                val already = (directMap.keys + inheritedMap.keys).toMutableSet()
                val ext = BuiltinDocRegistry.extensionMethodNamesFor(className)
                if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Extensions for $className: count=${ext.size} -> ${ext}")
                for (name in ext) {
                    if (already.contains(name)) continue
                    // Try to resolve full signature via registry first to get params and return type
                    val resolved = DocLookupUtils.resolveMemberWithInheritance(imported, className, name)
                    if (resolved != null) {
                        when (val member = resolved.second) {
                            is MiniMemberFunDecl -> {
                                val params = member.params.joinToString(", ") { it.name }
                                val ret = typeOf(member.returnType)
                                val builder = LookupElementBuilder.create(name)
                                    .withIcon(AllIcons.Nodes.Method)
                                    .withTailText("(${params})", true)
                                    .withTypeText(ret, true)
                                    .withInsertHandler(ParenInsertHandler)
                                emit(builder)
                                already.add(name)
                                continue
                            }
                            is MiniMemberValDecl -> {
                                val builder = LookupElementBuilder.create(name)
                                    .withIcon(if (member.mutable) AllIcons.Nodes.Variable else AllIcons.Nodes.Field)
                                    .withTypeText(typeOf(member.type), true)
                                emit(builder)
                                already.add(name)
                                continue
                            }
                        }
                    }
                    // Fallback: emit without detailed types if we couldn't resolve
                    val builder = LookupElementBuilder.create(name)
                        .withIcon(AllIcons.Nodes.Method)
                        .withTailText("()", true)
                        .withInsertHandler(ParenInsertHandler)
                    emit(builder)
                    already.add(name)
                }
            }
        }

        // --- MiniAst-based inference helpers ---

        private fun previousIdentifierBeforeDot(text: String, dotPos: Int): String? {
            var i = dotPos - 1
            // skip whitespace
            while (i >= 0 && text[i].isWhitespace()) i--
            val end = i + 1
            while (i >= 0 && TextCtx.isIdentChar(text[i])) i--
            val start = i + 1
            return if (start < end) text.substring(start, end) else null
        }

        private fun guessReceiverClassViaMini(mini: MiniScript?, text: String, dotPos: Int, imported: List<String>): String? {
            if (mini == null) return null
            val ident = previousIdentifierBeforeDot(text, dotPos) ?: return null
            // 1) Local val/var in the file
            val valDecl = mini.declarations.filterIsInstance<MiniValDecl>().firstOrNull { it.name == ident }
            val typeFromVal = valDecl?.type?.let { simpleClassNameOf(it) }
            if (!typeFromVal.isNullOrBlank()) return typeFromVal
            // If initializer exists, try to sniff ClassName(
            val initR = valDecl?.initRange
            if (initR != null) {
                val src = mini.range.start.source
                val s = src.offsetOf(initR.start)
                val e = src.offsetOf(initR.end).coerceAtMost(text.length)
                if (s in 0..e && e <= text.length) {
                    val init = text.substring(s, e)
                    Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").find(init)?.let { m ->
                        val cls = m.groupValues[1]
                        return cls
                    }
                }
            }
            // 2) Parameters in any function (best-effort without scope mapping)
            val paramType = mini.declarations.filterIsInstance<MiniFunDecl>()
                .asSequence()
                .flatMap { it.params.asSequence() }
                .firstOrNull { it.name == ident }?.type
            val typeFromParam = simpleClassNameOf(paramType)
            if (!typeFromParam.isNullOrBlank()) return typeFromParam
            return null
        }

        private fun guessReturnClassFromMemberCallBeforeMini(mini: MiniScript?, text: String, dotPos: Int, imported: List<String>): String? {
            if (mini == null) return null
            var i = TextCtx.prevNonWs(text, dotPos - 1)
            if (i < 0 || text[i] != ')') return null
            // back to matching '('
            i--
            var depth = 0
            while (i >= 0) {
                when (text[i]) {
                    ')' -> depth++
                    '(' -> if (depth == 0) break else depth--
                }
                i--
            }
            if (i < 0 || text[i] != '(') return null
            var j = i - 1
            while (j >= 0 && text[j].isWhitespace()) j--
            val end = j + 1
            while (j >= 0 && TextCtx.isIdentChar(text[j])) j--
            val start = j + 1
            if (start >= end) return null
            val callee = text.substring(start, end)
            // Ensure member call: dot before callee
            var k = start - 1
            while (k >= 0 && text[k].isWhitespace()) k--
            if (k < 0 || text[k] != '.') return null
            val prevDot = k
            // Resolve receiver class via MiniAst (ident like `x`)
            val receiverClass = guessReceiverClassViaMini(mini, text, prevDot, imported) ?: return null
            // If receiver class is a locally declared class, resolve member on it
            val localClass = mini.declarations.filterIsInstance<MiniClassDecl>().firstOrNull { it.name == receiverClass }
            if (localClass != null) {
                val mm = localClass.members.firstOrNull { it.name == callee }
                if (mm != null) {
                    val rt = when (mm) {
                        is MiniMemberFunDecl -> mm.returnType
                        is MiniMemberValDecl -> mm.type
                        else -> null
                    }
                    return simpleClassNameOf(rt)
                } else {
                    // Try to scan class body text for method signature and extract return type
                    val sigs = scanLocalClassMembersFromText(mini, text, localClass)
                    if (DEBUG_COMPLETION) log.info("[LYNG_DEBUG] Local scan for return type in ${receiverClass}.${callee}: candidates=${sigs.keys}")
                    val sig = sigs[callee]
                    if (sig != null && sig.typeText != null) return sig.typeText
                }
            }
            // Else fallback to registry-based resolution (covers imported classes)
            return DocLookupUtils.resolveMemberWithInheritance(imported, receiverClass, callee)?.second?.let { m ->
                val rt = when (m) {
                    is MiniMemberFunDecl -> m.returnType
                    is MiniMemberValDecl -> m.type
                }
                simpleClassNameOf(rt)
            }
        }

        private data class ScannedSig(val kind: String, val params: List<String>?, val typeText: String?)

        private fun scanLocalClassMembersFromText(mini: MiniScript, text: String, cls: MiniClassDecl): Map<String, ScannedSig> {
            val src = mini.range.start.source
            val start = src.offsetOf(cls.bodyRange?.start ?: cls.range.start)
            val end = src.offsetOf(cls.bodyRange?.end ?: cls.range.end).coerceAtMost(text.length)
            if (start !in 0..end) return emptyMap()
            val body = text.substring(start, end)
            val map = LinkedHashMap<String, ScannedSig>()
            // fun name(params): Type
            val funRe = Regex("(?m)^\\s*fun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*(?::\\s*([A-Za-z_][A-Za-z0-9_]*))?")
            for (m in funRe.findAll(body)) {
                val name = m.groupValues.getOrNull(1) ?: continue
                val params = m.groupValues.getOrNull(2)?.split(',')?.mapNotNull { it.trim().takeIf { it.isNotEmpty() } } ?: emptyList()
                val type = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
                map[name] = ScannedSig("fun", params, type)
            }
            // val/var name: Type
            val valRe = Regex("(?m)^\\s*(val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*([A-Za-z_][A-Za-z0-9_]*))?")
            for (m in valRe.findAll(body)) {
                val kind = m.groupValues.getOrNull(1) ?: continue
                val name = m.groupValues.getOrNull(2) ?: continue
                val type = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
                map.putIfAbsent(name, ScannedSig(kind, null, type))
            }
            return map
        }

        private fun guessReceiverClass(text: String, dotPos: Int, imported: List<String>): String? {
            // 1) Try call-based: ClassName(...).
            DocLookupUtils.guessClassFromCallBefore(text, dotPos, imported)?.let { return it }

            // 2) Literal heuristics based on the immediate char before '.'
            var i = TextCtx.prevNonWs(text, dotPos - 1)
            if (i >= 0) {
                when (text[i]) {
                    '"' -> {
                        // Either regular or triple-quoted string; both map to String
                        return "String"
                    }
                    ']' -> return "List" // very rough heuristic
                    '}' -> return "Dict" // map/dictionary literal heuristic
                    ')' -> {
                        // Parenthesized expression: walk back to matching '(' and inspect inner expression
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
                                if (inner.startsWith('"') && inner.endsWith('"')) return "String"
                                if (inner.startsWith('[') && inner.endsWith(']')) return "List"
                                if (inner.startsWith('{') && inner.endsWith('}')) return "Dict"
                            }
                        }
                    }
                }
                // Numeric literal: support decimal, hex (0x..), and scientific notation (1e-3)
                var j = i
                var hasDigits = false
                var hasDot = false
                var hasExp = false
                // Walk over digits, letters for hex, dots, and exponent markers
                while (j >= 0) {
                    val ch = text[j]
                    if (ch.isDigit()) { hasDigits = true; j-- ; continue }
                    if (ch == '.') { hasDot = true; j-- ; continue }
                    if (ch == 'e' || ch == 'E') { hasExp = true; j-- ; // optional sign directly before digits
                        if (j >= 0 && (text[j] == '+' || text[j] == '-')) j--
                        continue
                    }
                    if (ch in listOf('x','X')) { // part of 0x prefix
                        j--
                        continue
                    }
                    if (ch == 'a' || ch == 'b' || ch == 'c' || ch == 'd' || ch == 'f' ||
                        ch == 'A' || ch == 'B' || ch == 'C' || ch == 'D' || ch == 'F') {
                        // hex digit in 0x...
                        j--
                        continue
                    }
                    break
                }
                // Now check for 0x/0X prefix
                val k = j
                val isHex = k >= 1 && text[k] == '0' && (text[k+1] == 'x' || text[k+1] == 'X')
                if (hasDigits) {
                    return if (isHex) "Int" else if (hasDot || hasExp) "Real" else "Int"
                }
            }
            return null
        }

        /**
         * Try to infer the class of the return value of the member call immediately before the dot.
         * Example: `Path(".." ).lines().<caret>` → detects `lines()` on receiver class `Path` and returns `Iterator`.
         */
        private fun guessReturnClassFromMemberCallBefore(text: String, dotPos: Int, imported: List<String>): String? {
            var i = TextCtx.prevNonWs(text, dotPos - 1)
            if (i < 0) return null
            // We expect a call just before the dot, i.e., ')' ... '.'
            if (text[i] != ')') return null
            // Walk back to matching '('
            i--
            var depth = 0
            while (i >= 0) {
                val ch = text[i]
                when (ch) {
                    ')' -> depth++
                    '(' -> if (depth == 0) break else depth--
                }
                i--
            }
            if (i < 0 || text[i] != '(') return null
            // Identify callee identifier just before '('
            var j = i - 1
            while (j >= 0 && text[j].isWhitespace()) j--
            val end = j + 1
            while (j >= 0 && TextCtx.isIdentChar(text[j])) j--
            val start = j + 1
            if (start >= end) return null
            val callee = text.substring(start, end)
            // Ensure it's a member call (there must be a dot immediately before the callee, ignoring spaces)
            var k = start - 1
            while (k >= 0 && text[k].isWhitespace()) k--
            if (k < 0 || text[k] != '.') return null
            val prevDot = k
            // Infer receiver class at the previous dot
            val receiverClass = guessReceiverClass(text, prevDot, imported) ?: return null
            // Resolve the callee as a member of receiver class, including inheritance
            val resolved = DocLookupUtils.resolveMemberWithInheritance(imported, receiverClass, callee) ?: return null
            val member = resolved.second
            val returnType = when (member) {
                is MiniMemberFunDecl -> member.returnType
                is MiniMemberValDecl -> member.type
            }
            return simpleClassNameOf(returnType)
        }

        /**
         * Infer return class of a top-level call right before the dot: e.g., `files().<caret>`.
         * We extract callee name and resolve it among imported modules' top-level functions.
         */
        private fun guessReturnClassFromTopLevelCallBefore(text: String, dotPos: Int, imported: List<String>): String? {
            var i = TextCtx.prevNonWs(text, dotPos - 1)
            if (i < 0 || text[i] != ')') return null
            // Walk back to matching '('
            i--
            var depth = 0
            while (i >= 0) {
                val ch = text[i]
                when (ch) {
                    ')' -> depth++
                    '(' -> if (depth == 0) break else depth--
                }
                i--
            }
            if (i < 0 || text[i] != '(') return null
            // Extract callee ident before '('
            var j = i - 1
            while (j >= 0 && text[j].isWhitespace()) j--
            val end = j + 1
            while (j >= 0 && TextCtx.isIdentChar(text[j])) j--
            val start = j + 1
            if (start >= end) return null
            val callee = text.substring(start, end)
            // If it's a member call, bail out (handled in member-call inference)
            var k = start - 1
            while (k >= 0 && text[k].isWhitespace()) k--
            if (k >= 0 && text[k] == '.') return null

            // Resolve top-level function in imported modules
            for (mod in imported) {
                val decls = BuiltinDocRegistry.docsForModule(mod)
                val fn = decls.asSequence().filterIsInstance<MiniFunDecl>().firstOrNull { it.name == callee }
                if (fn != null) return simpleClassNameOf(fn.returnType)
            }
            return null
        }

        /**
         * Fallback: if we can at least extract a callee name before the dot and it exists across common classes,
         * derive its return type using cross-class lookup (Iterable/Iterator/List preference). This ignores the receiver.
         * Example: `something.lines().<caret>` where `something` type is unknown, but `lines()` commonly returns Iterator<String>.
         */
        private fun guessReturnClassAcrossKnownCallees(text: String, dotPos: Int, imported: List<String>): String? {
            var i = TextCtx.prevNonWs(text, dotPos - 1)
            if (i < 0 || text[i] != ')') return null
            // Walk back to matching '('
            i--
            var depth = 0
            while (i >= 0) {
                val ch = text[i]
                when (ch) {
                    ')' -> depth++
                    '(' -> if (depth == 0) break else depth--
                }
                i--
            }
            if (i < 0 || text[i] != '(') return null
            // Extract callee ident before '('
            var j = i - 1
            while (j >= 0 && text[j].isWhitespace()) j--
            val end = j + 1
            while (j >= 0 && TextCtx.isIdentChar(text[j])) j--
            val start = j + 1
            if (start >= end) return null
            val callee = text.substring(start, end)
            // Try cross-class resolution
            val resolved = DocLookupUtils.findMemberAcrossClasses(imported, callee) ?: return null
            val member = resolved.second
            val returnType = when (member) {
                is MiniMemberFunDecl -> member.returnType
                is MiniMemberValDecl -> member.type
            }
            return simpleClassNameOf(returnType)
        }

        /** Convert a MiniTypeRef to a simple class name as used by docs (e.g., Iterator from Iterator<String>). */
        private fun simpleClassNameOf(t: MiniTypeRef?): String? = when (t) {
            null -> null
            is MiniTypeName -> t.segments.lastOrNull()?.name
            is MiniGenericType -> simpleClassNameOf(t.base)
            is MiniFunctionType -> null
            is MiniTypeVar -> null
        }

        private fun buildMiniAst(text: String): MiniScript? {
            return try {
                val sink = MiniAstBuilder()
                val provider = IdeLenientImportProvider.create()
                val src = Source("<ide>", text)
                runBlocking { Compiler.compileWithMini(src, provider, sink) }
                sink.build()
            } catch (_: Throwable) {
                null
            }
        }

        // Cached per PsiFile by document modification stamp
        private val MINI_KEY = Key.create<MiniScript>("lyng.mini.cache")
        private val STAMP_KEY = Key.create<Long>("lyng.mini.cache.stamp")

        private fun buildMiniAstCached(file: PsiFile, text: String): MiniScript? {
            val doc = file.viewProvider.document ?: return null
            val stamp = doc.modificationStamp
            val prevStamp = file.getUserData(STAMP_KEY)
            val cached = file.getUserData(MINI_KEY)
            if (cached != null && prevStamp != null && prevStamp == stamp) return cached
            val built = buildMiniAst(text)
            // Cache even null? avoid caching failures; only cache non-null
            if (built != null) {
                file.putUserData(MINI_KEY, built)
                file.putUserData(STAMP_KEY, stamp)
            }
            return built
        }

        private fun offerParamsInScope(emit: (com.intellij.codeInsight.lookup.LookupElement) -> Unit, mini: MiniScript, text: String, caret: Int) {
            val src = mini.range.start.source
            // Find function whose body contains caret or whose whole range contains caret
            val fns = mini.declarations.filterIsInstance<MiniFunDecl>()
            for (fn in fns) {
                val start = src.offsetOf(fn.range.start)
                val end = src.offsetOf(fn.range.end).coerceAtMost(text.length)
                if (caret in start..end) {
                    for (p in fn.params) {
                        val builder = LookupElementBuilder.create(p.name)
                            .withIcon(AllIcons.Nodes.Variable)
                            .withTypeText(typeOf(p.type), true)
                        emit(builder)
                    }
                    return
                }
            }
        }

        // Lenient textual import extractor (duplicated from QuickDoc privately)
        private fun extractImportsFromText(text: String): List<String> {
            val result = LinkedHashSet<String>()
            val re = Regex("(?m)^\\s*import\\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)")
            re.findAll(text).forEach { m ->
                val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
                if (raw.isNotEmpty()) {
                    val canon = if (raw.startsWith("lyng.")) raw else "lyng.$raw"
                    result.add(canon)
                }
            }
            return result.toList()
        }

        private fun typeOf(t: MiniTypeRef?): String {
            return when (t) {
                null -> ""
                is MiniTypeName -> t.segments.lastOrNull()?.name?.let { ": $it" } ?: ""
                is MiniGenericType -> {
                    val base = typeOf(t.base).removePrefix(": ")
                    val args = t.args.joinToString(",") { typeOf(it).removePrefix(": ") }
                    ": ${base}<${args}>"
                }
                is MiniFunctionType -> ": (fn)"
                is MiniTypeVar -> ": ${t.name}"
            }
        }
    }
}
