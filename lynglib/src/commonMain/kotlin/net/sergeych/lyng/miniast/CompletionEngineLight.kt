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
 * Pure-Kotlin, PSI-free completion engine used for isolated tests and non-IDE harnesses.
 * Mirrors the IntelliJ MVP logic: MiniAst + BuiltinDocRegistry + lenient imports.
 */
package net.sergeych.lyng.miniast
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.binding.BindingSnapshot
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.pacman.ImportProvider

/** Minimal completion item description (IDE-agnostic). */
data class CompletionItem(
    val name: String,
    val kind: Kind,
    val tailText: String? = null,
    val typeText: String? = null,
    val priority: Double = 0.0,
)

enum class Kind { Function, Class_, Enum, Value, Method, Field }

/**
 * Platform-free, lenient import provider that never fails on unknown packages.
 * Used to allow MiniAst parsing even when external modules are not present at runtime.
 */
class LenientImportProvider private constructor(root: net.sergeych.lyng.Scope) : ImportProvider(root) {
    override suspend fun createModuleScope(pos: net.sergeych.lyng.Pos, packageName: String): net.sergeych.lyng.ModuleScope =
        net.sergeych.lyng.ModuleScope(this, pos, packageName)

    companion object {
        fun create(): LenientImportProvider = LenientImportProvider(Script.defaultImportManager.rootScope)
    }
}

object CompletionEngineLight {

    suspend fun completeAtMarkerSuspend(textWithCaret: String, marker: String = "<caret>"): List<CompletionItem> {
        val idx = textWithCaret.indexOf(marker)
        require(idx >= 0) { "Caret marker '$marker' not found" }
        val text = textWithCaret.replace(marker, "")
        return completeSuspend(text, idx)
    }

    suspend fun completeSuspend(text: String, caret: Int, providedMini: MiniScript? = null, binding: BindingSnapshot? = null): List<CompletionItem> {
        // Ensure stdlib Obj*-defined docs (e.g., String methods) are initialized before registry lookup
        StdlibDocsBootstrap.ensure()
        val prefix = prefixAt(text, caret)
        val mini = providedMini ?: buildMiniAst(text)
        val imported: List<String> = DocLookupUtils.canonicalImportedModules(mini ?: return emptyList(), text)

        val cap = 200
        val out = ArrayList<CompletionItem>(64)

        // Member context detection: dot immediately before caret or before current word start
        val word = DocLookupUtils.wordRangeAt(text, caret)
        val memberDot = DocLookupUtils.findDotLeft(text, word?.first ?: caret)
        if (memberDot != null) {
            val inferredCls = (DocLookupUtils.guessReturnClassFromMemberCallBeforeMini(mini, text, memberDot, imported, binding) ?: DocLookupUtils.guessReceiverClass(text, memberDot, imported, mini))
            // 0) Try chained member call return type inference
            DocLookupUtils.guessReturnClassFromMemberCallBeforeMini(mini, text, memberDot, imported, binding)?.let { cls ->
                offerMembersAdd(out, prefix, imported, cls, mini)
                return out
            }
            DocLookupUtils.guessReturnClassFromMemberCallBefore(text, memberDot, imported, mini)?.let { cls ->
                offerMembersAdd(out, prefix, imported, cls, mini)
                return out
            }
            // 0a) Top-level call before dot
            DocLookupUtils.guessReturnClassFromTopLevelCallBefore(text, memberDot, imported, mini)?.let { cls ->
                offerMembersAdd(out, prefix, imported, cls, mini)
                return out
            }
            // 0b) Across-known-callees (Iterable/Iterator/List preference)
            DocLookupUtils.guessReturnClassAcrossKnownCallees(text, memberDot, imported, mini)?.let { cls ->
                offerMembersAdd(out, prefix, imported, cls, mini)
                return out
            }
            // 1) Receiver inference fallback
            (DocLookupUtils.guessReceiverClassViaMini(mini, text, memberDot, imported, binding) ?: DocLookupUtils.guessReceiverClass(text, memberDot, imported, mini))?.let { cls ->
                offerMembersAdd(out, prefix, imported, cls, mini)
                return out
            }
            // In member context and unknown receiver/return type: show nothing (no globals after dot)
            return out
        }

        // Global identifiers: params > local decls > imported > stdlib; Functions > Classes > Values; alphabetical
        offerParamsInScope(out, prefix, mini, text, caret)

        val locals = DocLookupUtils.extractLocalsAt(text, caret)
        for (name in locals) {
            if (name.startsWith(prefix, true)) {
                out.add(CompletionItem(name, Kind.Value, priority = 150.0))
            }
        }

        val decls = mini.declarations
        val funs = decls.filterIsInstance<MiniFunDecl>().sortedBy { it.name.lowercase() }
        val classes = decls.filterIsInstance<MiniClassDecl>().sortedBy { it.name.lowercase() }
        val enums = decls.filterIsInstance<MiniEnumDecl>().sortedBy { it.name.lowercase() }
        val vals = decls.filterIsInstance<MiniValDecl>().sortedBy { it.name.lowercase() }
        funs.forEach { offerDeclAdd(out, prefix, it) }
        classes.forEach { offerDeclAdd(out, prefix, it) }
        enums.forEach { offerDeclAdd(out, prefix, it) }
        vals.forEach { offerDeclAdd(out, prefix, it) }

        // Imported and builtin
        val (nonStd, std) = imported.partition { it != "lyng.stdlib" }
        val order = nonStd + std
        val emptyPrefixThrottle = prefix.isBlank()
        var externalAdded = 0
        val budget = if (emptyPrefixThrottle) 100 else Int.MAX_VALUE
        for (mod in order) {
            val decls = BuiltinDocRegistry.docsForModule(mod)
            val funs = decls.filterIsInstance<MiniFunDecl>().sortedBy { it.name.lowercase() }
            val classes = decls.filterIsInstance<MiniClassDecl>().sortedBy { it.name.lowercase() }
            val enums = decls.filterIsInstance<MiniEnumDecl>().sortedBy { it.name.lowercase() }
            val vals = decls.filterIsInstance<MiniValDecl>().sortedBy { it.name.lowercase() }
            funs.forEach { if (externalAdded < budget) { offerDeclAdd(out, prefix, it); externalAdded++ } }
            classes.forEach { if (externalAdded < budget) { offerDeclAdd(out, prefix, it); externalAdded++ } }
            enums.forEach { if (externalAdded < budget) { offerDeclAdd(out, prefix, it); externalAdded++ } }
            vals.forEach { if (externalAdded < budget) { offerDeclAdd(out, prefix, it); externalAdded++ } }
            if (out.size >= cap || externalAdded >= budget) break
        }

        return out
    }

    // --- Emission helpers ---

    private fun offerParamsInScope(out: MutableList<CompletionItem>, prefix: String, mini: MiniScript, text: String, caret: Int) {
        val src = mini.range.start.source
        val already = mutableSetOf<String>()

        fun add(ci: CompletionItem) {
            if (ci.name.startsWith(prefix, true) && already.add(ci.name)) {
                out.add(ci)
            }
        }

        fun checkNode(node: Any) {
            val range: MiniRange = when (node) {
                is MiniDecl -> node.range
                is MiniMemberDecl -> node.range
                else -> return
            }
            val start = src.offsetOf(range.start)
            val end = src.offsetOf(range.end).coerceAtMost(text.length)

            if (caret in start..end) {
                when (node) {
                    is MiniFunDecl -> {
                        for (p in node.params) {
                            add(CompletionItem(p.name, Kind.Value, typeText = typeOf(p.type), priority = 200.0))
                        }
                    }
                    is MiniClassDecl -> {
                        // Propose constructor parameters (ctorFields)
                        for (p in node.ctorFields) {
                            add(CompletionItem(p.name, if (p.mutable) Kind.Value else Kind.Field, typeText = typeOf(p.type), priority = 200.0))
                        }
                        // Propose class-level fields
                        for (p in node.classFields) {
                            add(CompletionItem(p.name, if (p.mutable) Kind.Value else Kind.Field, typeText = typeOf(p.type), priority = 100.0))
                        }
                        // Process members (methods/fields)
                        for (m in node.members) {
                            // If the member itself contains the caret (like a method), recurse
                            checkNode(m)

                            // Also offer the member itself for the class scope
                            when (m) {
                                is MiniMemberFunDecl -> {
                                    val params = m.params.joinToString(", ") { it.name }
                                    add(CompletionItem(m.name, Kind.Method, tailText = "(${params})", typeText = typeOf(m.returnType), priority = 100.0))
                                }
                                is MiniMemberValDecl -> {
                                    add(CompletionItem(m.name, if (m.mutable) Kind.Value else Kind.Field, typeText = typeOf(m.type), priority = 100.0))
                                }
                                is MiniInitDecl -> {}
                            }
                        }
                    }
                    is MiniMemberFunDecl -> {
                        for (p in node.params) {
                            add(CompletionItem(p.name, Kind.Value, typeText = typeOf(p.type), priority = 200.0))
                        }
                    }
                    else -> {}
                }
            }
        }

        for (decl in mini.declarations) {
            checkNode(decl)
        }
    }

    private fun offerDeclAdd(out: MutableList<CompletionItem>, prefix: String, d: MiniDecl) {
        fun add(ci: CompletionItem) { if (ci.name.startsWith(prefix, true)) out += ci }
        when (d) {
            is MiniFunDecl -> {
                val params = d.params.joinToString(", ") { it.name }
                val tail = "(${params})"
                add(CompletionItem(d.name, Kind.Function, tailText = tail, typeText = typeOf(d.returnType)))
            }
            is MiniClassDecl -> add(CompletionItem(d.name, Kind.Class_))
            is MiniEnumDecl -> add(CompletionItem(d.name, Kind.Enum))
            is MiniValDecl -> add(CompletionItem(d.name, Kind.Value, typeText = typeOf(d.type)))
//            else -> add(CompletionItem(d.name, Kind.Value))
        }
    }

    private fun offerMembersAdd(out: MutableList<CompletionItem>, prefix: String, imported: List<String>, className: String, mini: MiniScript? = null) {
        val classes = DocLookupUtils.aggregateClasses(imported, mini)
        val visited = mutableSetOf<String>()
        val directMap = LinkedHashMap<String, MutableList<MiniMemberDecl>>()
        val inheritedMap = LinkedHashMap<String, MutableList<MiniMemberDecl>>()

        fun addMembersOf(name: String, direct: Boolean) {
            val cls = classes[name] ?: return
            val target = if (direct) directMap else inheritedMap
            for (cf in cls.ctorFields + cls.classFields) {
                target.getOrPut(cf.name) { mutableListOf() }.add(DocLookupUtils.toMemberVal(cf))
            }
            for (m in cls.members) target.getOrPut(m.name) { mutableListOf() }.add(m)
            for (b in cls.bases) if (visited.add(b)) addMembersOf(b, false)
        }

        visited.add(className)
        addMembersOf(className, true)

        // Conservative supplements for containers
        when (className) {
            "List" -> listOf("Collection", "Iterable").forEach { if (visited.add(it)) addMembersOf(it, false) }
            "Array" -> listOf("Collection", "Iterable").forEach { if (visited.add(it)) addMembersOf(it, false) }
        }

        fun emitGroup(map: LinkedHashMap<String, MutableList<MiniMemberDecl>>, groupPriority: Double) {
            for (name in map.keys.sortedBy { it.lowercase() }) {
                val variants = map[name] ?: continue
                // Choose a representative for display:
                // 1) Prefer a method with return type AND parameters
                // 2) Prefer a method with parameters
                // 3) Prefer a method with return type
                // 4) Else any method
                // 5) Else the first variant
                val rep =
                    variants.asSequence().filterIsInstance<MiniMemberFunDecl>()
                        .firstOrNull { it.returnType != null && it.params.isNotEmpty() }
                        ?: variants.asSequence().filterIsInstance<MiniMemberFunDecl>()
                            .firstOrNull { it.params.isNotEmpty() }
                        ?: variants.asSequence().filterIsInstance<MiniMemberFunDecl>()
                            .firstOrNull { it.returnType != null }
                        ?: variants.firstOrNull { it is MiniMemberFunDecl }
                        ?: variants.first()
                when (rep) {
                    is MiniMemberFunDecl -> {
                        val params = rep.params.joinToString(", ") { it.name }
                        val extra = variants.count { it is MiniMemberFunDecl } - 1
                        val ov = if (extra > 0) " (+$extra overloads)" else ""
                        val ci = CompletionItem(name, Kind.Method, tailText = "(${params})$ov", typeText = typeOf(rep.returnType), priority = groupPriority)
                        if (ci.name.startsWith(prefix, true)) out += ci
                    }
                    is MiniMemberValDecl -> {
                        // Prefer a field variant with known type if available
                        val chosen = variants.asSequence()
                            .filterIsInstance<MiniMemberValDecl>()
                            .firstOrNull { it.type != null } ?: rep
                        val ci = CompletionItem(name, Kind.Field, typeText = typeOf(chosen.type), priority = groupPriority)
                        if (ci.name.startsWith(prefix, true)) out += ci
                    }
                    is MiniInitDecl -> {}
                }
            }
        }

        emitGroup(directMap, 100.0)
        emitGroup(inheritedMap, 0.0)

        // Supplement with extension members (both stdlib and local)
        run {
            val already = (directMap.keys + inheritedMap.keys).toMutableSet()
            val extensions = DocLookupUtils.collectExtensionMemberNames(imported, className, mini)
            for (name in extensions) {
                if (already.contains(name)) continue
                val resolved = DocLookupUtils.resolveMemberWithInheritance(imported, className, name, mini)
                if (resolved != null) {
                    val m = resolved.second
                    val ci = when (m) {
                        is MiniMemberFunDecl -> {
                            val params = m.params.joinToString(", ") { it.name }
                            CompletionItem(name, Kind.Method, tailText = "(${params})", typeText = typeOf(m.returnType), priority = 50.0)
                        }
                        is MiniFunDecl -> {
                            val params = m.params.joinToString(", ") { it.name }
                            CompletionItem(name, Kind.Method, tailText = "(${params})", typeText = typeOf(m.returnType), priority = 50.0)
                        }
                        is MiniMemberValDecl -> CompletionItem(name, Kind.Field, typeText = typeOf(m.type), priority = 50.0)
                        is MiniValDecl -> CompletionItem(name, Kind.Field, typeText = typeOf(m.type), priority = 50.0)
                        else -> CompletionItem(name, Kind.Method, tailText = "()", typeText = null, priority = 50.0)
                    }
                    if (ci.name.startsWith(prefix, true)) {
                        out += ci
                        already.add(name)
                    }
                } else {
                    val ci = CompletionItem(name, Kind.Method, tailText = "()", typeText = null, priority = 50.0)
                    if (ci.name.startsWith(prefix, true)) {
                        out += ci
                        already.add(name)
                    }
                }
            }
        }
    }

    // --- Inference helpers (text-only, PSI-free) ---

    private suspend fun buildMiniAst(text: String): MiniScript? {
        val sink = MiniAstBuilder()
        return try {
            val src = Source("<engine>", text)
            val provider = LenientImportProvider.create()
            Compiler.compileWithMini(src, provider, sink)
            sink.build()
        } catch (_: Throwable) {
            sink.build()
        }
    }

    private fun typeOf(t: MiniTypeRef?): String {
        val s = DocLookupUtils.typeOf(t)
        return if (s.isEmpty()) "" else ": $s"
    }

    // Note: we intentionally skip "params in scope" in the isolated engine to avoid PSI/offset mapping.

    // Text helpers
    private fun prefixAt(text: String, offset: Int): String {
        val off = offset.coerceIn(0, text.length)
        var i = (off - 1).coerceAtLeast(0)
        while (i >= 0 && DocLookupUtils.isIdentChar(text[i])) i--
        val start = i + 1
        return if (start in 0..text.length && start <= off) text.substring(start, off) else ""
    }
}
