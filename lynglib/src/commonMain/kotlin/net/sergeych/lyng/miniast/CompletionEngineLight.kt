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
import net.sergeych.lyng.pacman.ImportProvider

/** Minimal completion item description (IDE-agnostic). */
data class CompletionItem(
    val name: String,
    val kind: Kind,
    val tailText: String? = null,
    val typeText: String? = null,
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

    suspend fun completeSuspend(text: String, caret: Int): List<CompletionItem> {
        // Ensure stdlib Obj*-defined docs (e.g., String methods) are initialized before registry lookup
        StdlibDocsBootstrap.ensure()
        val prefix = prefixAt(text, caret)
        val mini = buildMiniAst(text)
        // Build imported modules as a UNION of MiniAst-derived and textual extraction, always including stdlib
        run {
            // no-op block to keep local scope tidy
        }
        val fromMini: List<String> = mini?.let { DocLookupUtils.canonicalImportedModules(it) } ?: emptyList()
        val fromText: List<String> = extractImportsFromText(text)
        val imported: List<String> = LinkedHashSet<String>().apply {
            fromMini.forEach { add(it) }
            fromText.forEach { add(it) }
            add("lyng.stdlib")
        }.toList()

        val cap = 200
        val out = ArrayList<CompletionItem>(64)

        // Member context detection: dot immediately before caret or before current word start
        val word = wordRangeAt(text, caret)
        val memberDot = findDotLeft(text, word?.first ?: caret)
        if (memberDot != null) {
            // 0) Try chained member call return type inference
            guessReturnClassFromMemberCallBefore(text, memberDot, imported, mini)?.let { cls ->
                offerMembersAdd(out, prefix, imported, cls, mini)
                return out
            }
            // 0a) Top-level call before dot
            guessReturnClassFromTopLevelCallBefore(text, memberDot, imported, mini)?.let { cls ->
                offerMembersAdd(out, prefix, imported, cls, mini)
                return out
            }
            // 0b) Across-known-callees (Iterable/Iterator/List preference)
            guessReturnClassAcrossKnownCallees(text, memberDot, imported, mini)?.let { cls ->
                offerMembersAdd(out, prefix, imported, cls, mini)
                return out
            }
            // 1) Receiver inference fallback
            (guessReceiverClassViaMini(mini, text, memberDot, imported) ?: guessReceiverClass(text, memberDot, imported, mini))?.let { cls ->
                offerMembersAdd(out, prefix, imported, cls, mini)
                return out
            }
            // In member context and unknown receiver/return type: show nothing (no globals after dot)
            return out
        }

        // Global identifiers: params > local decls > imported > stdlib; Functions > Classes > Values; alphabetical
        mini?.let { m ->
            val decls = m.declarations
            val funs = decls.filterIsInstance<MiniFunDecl>().sortedBy { it.name.lowercase() }
            val classes = decls.filterIsInstance<MiniClassDecl>().sortedBy { it.name.lowercase() }
            val enums = decls.filterIsInstance<MiniEnumDecl>().sortedBy { it.name.lowercase() }
            val vals = decls.filterIsInstance<MiniValDecl>().sortedBy { it.name.lowercase() }
            funs.forEach { offerDeclAdd(out, prefix, it) }
            classes.forEach { offerDeclAdd(out, prefix, it) }
            enums.forEach { offerDeclAdd(out, prefix, it) }
            vals.forEach { offerDeclAdd(out, prefix, it) }
        }

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

        fun emitGroup(map: LinkedHashMap<String, MutableList<MiniMemberDecl>>) {
            for (name in map.keys.sortedBy { it.lowercase() }) {
                val variants = map[name] ?: continue
                // Prefer a method with a known return type; else any method; else first variant
                val rep =
                    variants.asSequence()
                        .filterIsInstance<MiniMemberFunDecl>()
                        .firstOrNull { it.returnType != null }
                        ?: variants.firstOrNull { it is MiniMemberFunDecl }
                        ?: variants.first()
                when (rep) {
                    is MiniMemberFunDecl -> {
                        val params = rep.params.joinToString(", ") { it.name }
                        val extra = variants.count { it is MiniMemberFunDecl } - 1
                        val ov = if (extra > 0) " (+$extra overloads)" else ""
                        val ci = CompletionItem(name, Kind.Method, tailText = "(${params})$ov", typeText = typeOf(rep.returnType))
                        if (ci.name.startsWith(prefix, true)) out += ci
                    }
                    is MiniMemberValDecl -> {
                        // Prefer a field variant with known type if available
                        val chosen = variants.asSequence()
                            .filterIsInstance<MiniMemberValDecl>()
                            .firstOrNull { it.type != null } ?: rep
                        val ci = CompletionItem(name, Kind.Field, typeText = typeOf((chosen as MiniMemberValDecl).type))
                        if (ci.name.startsWith(prefix, true)) out += ci
                    }
                    is MiniInitDecl -> {}
                }
            }
        }

        emitGroup(directMap)
        emitGroup(inheritedMap)

        // Supplement with stdlib extension members defined in root.lyng (e.g., fun String.re(...))
        run {
            val already = (directMap.keys + inheritedMap.keys).toMutableSet()
            val ext = BuiltinDocRegistry.extensionMemberNamesFor(className)
            for (name in ext) {
                if (already.contains(name)) continue
                val resolved = DocLookupUtils.resolveMemberWithInheritance(imported, className, name)
                if (resolved != null) {
                    when (val member = resolved.second) {
                        is MiniMemberFunDecl -> {
                            val params = member.params.joinToString(", ") { it.name }
                            val ci = CompletionItem(name, Kind.Method, tailText = "(${params})", typeText = typeOf(member.returnType))
                            if (ci.name.startsWith(prefix, true)) out += ci
                            already.add(name)
                        }
                        is MiniMemberValDecl -> {
                            val ci = CompletionItem(name, Kind.Field, typeText = typeOf(member.type))
                            if (ci.name.startsWith(prefix, true)) out += ci
                            already.add(name)
                        }
                        is MiniInitDecl -> {}
                    }
                } else {
                    // Fallback: emit simple method name without detailed types
                    val ci = CompletionItem(name, Kind.Method, tailText = "()", typeText = null)
                    if (ci.name.startsWith(prefix, true)) out += ci
                    already.add(name)
                }
            }
        }
    }

    // --- Inference helpers (text-only, PSI-free) ---

    private fun guessReceiverClassViaMini(mini: MiniScript?, text: String, dotPos: Int, imported: List<String>): String? {
        if (mini == null) return null
        val i = prevNonWs(text, dotPos - 1)
        if (i < 0) return null
        val wordRange = wordRangeAt(text, i + 1) ?: return null
        val ident = text.substring(wordRange.first, wordRange.second)

        // 1) Global declarations in current file (val/var/fun/class/enum)
        val d = mini.declarations.firstOrNull { it.name == ident }
        if (d != null) {
            return when (d) {
                is MiniClassDecl -> d.name
                is MiniEnumDecl -> d.name
                is MiniValDecl -> simpleClassNameOf(d.type)
                is MiniFunDecl -> simpleClassNameOf(d.returnType)
            }
        }

        // 2) Recursive chaining: Base.ident.
        val dotBefore = findDotLeft(text, wordRange.first)
        if (dotBefore != null) {
            val receiverClass = guessReceiverClassViaMini(mini, text, dotBefore, imported)
                ?: guessReceiverClass(text, dotBefore, imported, mini)
            if (receiverClass != null) {
                val resolved = DocLookupUtils.resolveMemberWithInheritance(imported, receiverClass, ident, mini)
                if (resolved != null) {
                    val rt = when (val m = resolved.second) {
                        is MiniMemberFunDecl -> m.returnType
                        is MiniMemberValDecl -> m.type
                        else -> null
                    }
                    return simpleClassNameOf(rt)
                }
            }
        }

        // 3) Check if it's a known class (static access)
        val classes = DocLookupUtils.aggregateClasses(imported, mini)
        if (classes.containsKey(ident)) return ident

        return null
    }

    private fun guessReceiverClass(text: String, dotPos: Int, imported: List<String>, mini: MiniScript? = null): String? {
        DocLookupUtils.guessClassFromCallBefore(text, dotPos, imported, mini)?.let { return it }
        var i = prevNonWs(text, dotPos - 1)
        if (i >= 0) {
            when (text[i]) {
                '"' -> return "String"
                ']' -> return "List"
                '}' -> return "Dict"
                ')' -> {
                    // Parenthesized expression: walk back to matching '(' and inspect the inner expression
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
            // Numeric literal: decimal/int/hex/scientific
            var j = i
            var hasDigits = false
            var hasDot = false
            var hasExp = false
            while (j >= 0) {
                val ch = text[j]
                if (ch.isDigit()) { hasDigits = true; j--; continue }
                if (ch == '.') { hasDot = true; j--; continue }
                if (ch == 'e' || ch == 'E') { hasExp = true; j--; if (j >= 0 && (text[j] == '+' || text[j] == '-')) j--; continue }
                if (ch in listOf('x','X','a','b','c','d','f','A','B','C','D','F')) { j--; continue }
                break
            }
            if (hasDigits) return if (hasDot || hasExp) "Real" else "Int"

            // 3) this@Type or as Type
            val identRange = wordRangeAt(text, i + 1)
            if (identRange != null) {
                val ident = text.substring(identRange.first, identRange.second)
                // if it's "as Type", we want Type
                var k = prevNonWs(text, identRange.first - 1)
                if (k >= 1 && text[k] == 's' && text[k - 1] == 'a' && (k - 1 == 0 || !text[k - 2].isLetterOrDigit())) {
                    return ident
                }
                // if it's "this@Type", we want Type
                if (k >= 0 && text[k] == '@') {
                    val k2 = prevNonWs(text, k - 1)
                    if (k2 >= 3 && text.substring(k2 - 3, k2 + 1) == "this") {
                        return ident
                    }
                }

                // 4) Check if it's a known class (static access)
                val classes = DocLookupUtils.aggregateClasses(imported, mini)
                if (classes.containsKey(ident)) return ident
            }
        }
        return null
    }

    private fun guessReturnClassFromMemberCallBefore(text: String, dotPos: Int, imported: List<String>, mini: MiniScript? = null): String? {
        var i = prevNonWs(text, dotPos - 1)
        if (i < 0 || text[i] != ')') return null
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
        while (j >= 0 && isIdentChar(text[j])) j--
        val start = j + 1
        if (start >= end) return null
        val callee = text.substring(start, end)
        var k = start - 1
        while (k >= 0 && text[k].isWhitespace()) k--
        if (k < 0 || text[k] != '.') return null
        val prevDot = k
        val receiverClass = guessReceiverClass(text, prevDot, imported, mini) ?: return null
        val resolved = DocLookupUtils.resolveMemberWithInheritance(imported, receiverClass, callee, mini) ?: return null
        val member = resolved.second
        val ret = when (member) {
            is MiniMemberFunDecl -> member.returnType
            is MiniMemberValDecl -> member.type
            is MiniInitDecl -> null
        }
        return simpleClassNameOf(ret)
    }

    private fun guessReturnClassFromTopLevelCallBefore(text: String, dotPos: Int, imported: List<String>, mini: MiniScript? = null): String? {
        var i = prevNonWs(text, dotPos - 1)
        if (i < 0 || text[i] != ')') return null
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
        while (j >= 0 && isIdentChar(text[j])) j--
        val start = j + 1
        if (start >= end) return null
        val callee = text.substring(start, end)
        var k = start - 1
        while (k >= 0 && text[k].isWhitespace()) k--
        if (k >= 0 && text[k] == '.') return null // was a member call
        for (mod in imported) {
            val decls = BuiltinDocRegistry.docsForModule(mod)
            val fn = decls.asSequence().filterIsInstance<MiniFunDecl>().firstOrNull { it.name == callee }
            if (fn != null) return simpleClassNameOf(fn.returnType)
        }
        // Also check local declarations
        mini?.declarations?.filterIsInstance<MiniFunDecl>()?.firstOrNull { it.name == callee }?.let { return simpleClassNameOf(it.returnType) }
        return null
    }

    private fun guessReturnClassAcrossKnownCallees(text: String, dotPos: Int, imported: List<String>, mini: MiniScript? = null): String? {
        var i = prevNonWs(text, dotPos - 1)
        if (i < 0 || text[i] != ')') return null
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
        while (j >= 0 && isIdentChar(text[j])) j--
        val start = j + 1
        if (start >= end) return null
        val callee = text.substring(start, end)
        val resolved = DocLookupUtils.findMemberAcrossClasses(imported, callee, mini) ?: return null
        val member = resolved.second
        val ret = when (member) {
            is MiniMemberFunDecl -> member.returnType
            is MiniMemberValDecl -> member.type
            is MiniInitDecl -> null
        }
        return simpleClassNameOf(ret)
    }

    private fun simpleClassNameOf(t: MiniTypeRef?): String? = when (t) {
        null -> null
        is MiniTypeName -> t.segments.lastOrNull()?.name
        is MiniGenericType -> simpleClassNameOf(t.base)
        is MiniFunctionType -> null
        is MiniTypeVar -> null
    }

    // --- MiniAst and small utils ---

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

    private fun typeOf(t: MiniTypeRef?): String = when (t) {
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

    // Note: we intentionally skip "params in scope" in the isolated engine to avoid PSI/offset mapping.

    // Text helpers
    private fun prefixAt(text: String, offset: Int): String {
        val off = offset.coerceIn(0, text.length)
        var i = (off - 1).coerceAtLeast(0)
        while (i >= 0 && isIdentChar(text[i])) i--
        val start = i + 1
        return if (start in 0..text.length && start <= off) text.substring(start, off) else ""
    }

    private fun wordRangeAt(text: String, offset: Int): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        val off = offset.coerceIn(0, text.length)
        var s = off
        var e = off
        while (s > 0 && isIdentChar(text[s - 1])) s--
        while (e < text.length && isIdentChar(text[e])) e++
        return if (s < e) s to e else null
    }

    private fun findDotLeft(text: String, offset: Int): Int? {
        var i = (offset - 1).coerceAtLeast(0)
        while (i >= 0 && text[i].isWhitespace()) i--
        return if (i >= 0 && text[i] == '.') i else null
    }

    private fun prevNonWs(text: String, start: Int): Int {
        var i = start.coerceAtMost(text.length - 1)
        while (i >= 0 && text[i].isWhitespace()) i--
        return i
    }

    private fun isIdentChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    private fun extractImportsFromText(text: String): List<String> {
        val result = LinkedHashSet<String>()
        val re = Regex("^\\s*import\\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)", RegexOption.MULTILINE)
        re.findAll(text).forEach { m ->
            val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (raw.isNotEmpty()) result.add(if (raw.startsWith("lyng.")) raw else "lyng.$raw")
        }
        return result.toList()
    }
}
