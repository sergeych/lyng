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
 * Shared QuickDoc lookup helpers reusable outside the IDEA plugin.
 */
package net.sergeych.lyng.miniast

import net.sergeych.lyng.binding.BindingSnapshot
import net.sergeych.lyng.highlight.offsetOf

object DocLookupUtils {
    fun findDeclarationAt(mini: MiniScript, offset: Int, name: String): Pair<String, String>? {
        val src = mini.range.start.source
        fun matches(p: net.sergeych.lyng.Pos, len: Int) = src.offsetOf(p).let { s -> offset >= s && offset < s + len }

        for (d in mini.declarations) {
            if (matches(d.nameStart, d.name.length)) {
                val kind = when (d) {
                    is MiniFunDecl -> "Function"
                    is MiniClassDecl -> "Class"
                    is MiniEnumDecl -> "Enum"
                    is MiniValDecl -> if (d.mutable) "Variable" else "Value"
                }
                return d.name to kind
            }

            val members = when (d) {
                is MiniFunDecl -> {
                    for (p in d.params) {
                        if (matches(p.nameStart, p.name.length)) return p.name to "Parameter"
                    }
                    emptyList()
                }

                is MiniEnumDecl -> {
                    // Enum entries don't have explicit nameStart yet, but we can check their text range if we had it.
                    // For now, heuristic: if offset is within enum range and matches an entry name.
                    // To be more precise, we should check that we are NOT at the 'enum' or enum name.
                    if (offset >= src.offsetOf(d.range.start) && offset <= src.offsetOf(d.range.end)) {
                        if (d.entries.contains(name) && !matches(d.nameStart, d.name.length)) {
                            // verify we are actually at the entry word in text
                            val off = src.offsetOf(d.range.start)
                            val end = src.offsetOf(d.range.end)
                            val text = src.text.substring(off, end)
                            // This is still a bit loose but better
                            return name to "EnumConstant"
                        }
                    }
                    emptyList()
                }

                is MiniClassDecl -> {
                    for (cf in d.ctorFields) {
                        if (matches(cf.nameStart, cf.name.length)) return cf.name to (if (cf.mutable) "Variable" else "Value")
                    }
                    for (cf in d.classFields) {
                        if (matches(cf.nameStart, cf.name.length)) return cf.name to (if (cf.mutable) "Variable" else "Value")
                    }
                    d.members
                }

                else -> emptyList()
            }

            for (m in members) {
                if (m is MiniMemberFunDecl) {
                    for (p in m.params) {
                        if (matches(p.nameStart, p.name.length)) return p.name to "Parameter"
                    }
                }
                if (matches(m.nameStart, m.name.length)) {
                    val kind = when (m) {
                        is MiniMemberFunDecl -> "Function"
                        is MiniMemberValDecl -> if (m.isStatic) "Value" else (if (m.mutable) "Variable" else "Value")
                        is MiniInitDecl -> "Initializer"
                    }
                    return m.name to kind
                }
            }
        }
        return null
    }

    fun findEnclosingClass(mini: MiniScript, offset: Int): MiniClassDecl? {
        val src = mini.range.start.source
        return mini.declarations.filterIsInstance<MiniClassDecl>()
            .filter {
                val start = src.offsetOf(it.range.start)
                val end = src.offsetOf(it.range.end)
                offset in start..end
            }
            .minByOrNull {
                src.offsetOf(it.range.end) - src.offsetOf(it.range.start)
            }
    }

    fun findTypeByRange(mini: MiniScript?, name: String, startOffset: Int, text: String? = null, imported: List<String>? = null): MiniTypeRef? {
        if (mini == null) return null
        val src = mini.range.start.source
        fun matches(p: net.sergeych.lyng.Pos, len: Int) = src.offsetOf(p).let { s -> startOffset >= s && startOffset < s + len }

        for (d in mini.declarations) {
            if (d.name == name && matches(d.nameStart, d.name.length)) {
                return when (d) {
                    is MiniValDecl -> d.type ?: if (text != null && imported != null) inferTypeRefForVal(d, text, imported, mini) else null
                    is MiniFunDecl -> d.returnType
                    else -> null
                }
            }

            if (d is MiniFunDecl) {
                for (p in d.params) {
                    if (p.name == name && matches(p.nameStart, p.name.length)) return p.type
                }
            }

            if (d is MiniClassDecl) {
                for (m in d.members) {
                    if (m is MiniMemberFunDecl) {
                        for (p in m.params) {
                            if (p.name == name && matches(p.nameStart, p.name.length)) return p.type
                        }
                    }
                    if (m.name == name && matches(m.nameStart, m.name.length)) {
                        return when (m) {
                            is MiniMemberFunDecl -> m.returnType
                            is MiniMemberValDecl -> m.type ?: if (text != null && imported != null) {
                                inferTypeRefFromInitRange(m.initRange, m.nameStart, text, imported, mini)
                            } else null

                            else -> null
                        }
                    }
                }
                for (cf in d.ctorFields) {
                    if (cf.name == name && matches(cf.nameStart, cf.name.length)) return cf.type
                }
                for (cf in d.classFields) {
                    if (cf.name == name && matches(cf.nameStart, cf.name.length)) return cf.type
                }
            }
        }
        return null
    }

    /**
     * Convert MiniAst imports to fully-qualified module names expected by BuiltinDocRegistry.
     * Heuristics:
     *  - If an import does not start with "lyng.", prefix it with "lyng." (e.g., "io.fs" -> "lyng.io.fs").
     *  - Keep original if it already starts with "lyng.".
     *  - Always include "lyng.stdlib" to make builtins available for docs.
     *  - If [sourceText] is provided, uses textual extraction as a fallback if MiniAst has no imports.
     */
    fun canonicalImportedModules(mini: MiniScript, sourceText: String? = null): List<String> {
        val raw = mini.imports.map { it.segments.joinToString(".") { s -> s.name } }
        val result = LinkedHashSet<String>()
        for (name in raw) {
            val canon = if (name.startsWith("lyng.")) name else "lyng.$name"
            result.add(canon)
        }
        
        if (result.isEmpty() && sourceText != null) {
            result.addAll(extractImportsFromText(sourceText))
        }
        
        // Always make stdlib available as a fallback context for common types,
        // even when there are no explicit imports in the file
        result.add("lyng.stdlib")
        return result.toList()
    }

    fun extractLocalsAt(text: String, offset: Int): Set<String> {
        val res = mutableSetOf<String>()
        // 1) find val/var declarations
        val re = Regex("(?:^|[\\n;])\\s*(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)")
        re.findAll(text).forEach { m ->
            if (m.range.first < offset) res.add(m.groupValues[1])
        }
        // 2) find implicit assignments
        val re2 = Regex("(?:^|[\\n;])\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=[^=]")
        re2.findAll(text).forEach { m ->
            if (m.range.first < offset) res.add(m.groupValues[1])
        }
        return res
    }

    fun extractImportsFromText(text: String): List<String> {
        val result = LinkedHashSet<String>()
        val re = Regex("^\\s*import\\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)", RegexOption.MULTILINE)
        re.findAll(text).forEach { m ->
            val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                val canon = if (raw.startsWith("lyng.")) raw else "lyng.$raw"
                result.add(canon)
            }
        }
        return result.toList()
    }

    fun aggregateClasses(importedModules: List<String>, localMini: MiniScript? = null): Map<String, MiniClassDecl> {
        // Collect all class decls by name across modules, then merge duplicates by unioning members and bases.
        val buckets = LinkedHashMap<String, MutableList<MiniClassDecl>>()
        for (mod in importedModules) {
            val docs = BuiltinDocRegistry.docsForModule(mod)
            for (cls in docs.filterIsInstance<MiniClassDecl>()) {
                buckets.getOrPut(cls.name) { mutableListOf() }.add(cls)
            }
            for (en in docs.filterIsInstance<MiniEnumDecl>()) {
                buckets.getOrPut(en.name) { mutableListOf() }.add(enumToSyntheticClass(en))
            }
        }
        
        // Add local declarations
        localMini?.declarations?.forEach { d ->
            when (d) {
                is MiniClassDecl -> {
                    buckets.getOrPut(d.name) { mutableListOf() }.add(d)
                }
                is MiniEnumDecl -> {
                    val syn = enumToSyntheticClass(d)
                    buckets.getOrPut(d.name) { mutableListOf() }.add(syn)
                }
                else -> {}
            }
        }

        fun mergeClassDecls(name: String, list: List<MiniClassDecl>): MiniClassDecl {
            if (list.isEmpty()) throw IllegalArgumentException("empty class list for $name")
            if (list.size == 1) return list.first()
            // Choose a representative for non-merge fields (range/nameStart/bodyRange): take the first
            val rep = list.first()
            val bases = LinkedHashSet<String>()
            val members = LinkedHashMap<String, MutableList<MiniMemberDecl>>()
            val ctorFields = LinkedHashMap<String, MiniCtorField>()
            val classFields = LinkedHashMap<String, MiniCtorField>()
            var doc: MiniDoc? = null
            for (c in list) {
                bases.addAll(c.bases)
                if (doc == null && c.doc != null && c.doc.raw.isNotBlank()) doc = c.doc
                for (cf in c.ctorFields) ctorFields[cf.name] = cf
                for (cf in c.classFields) classFields[cf.name] = cf
                for (m in c.members) {
                    // Group by name to keep overloads together
                    members.getOrPut(m.name) { mutableListOf() }.add(m)
                }
            }
            // Flatten grouped members back to a list; keep stable name order
            val mergedMembers = members.keys.sortedBy { it.lowercase() }.flatMap { members[it] ?: emptyList() }
            return MiniClassDecl(
                range = rep.range,
                name = rep.name,
                bases = bases.toList(),
                bodyRange = rep.bodyRange,
                ctorFields = ctorFields.values.toList(),
                classFields = classFields.values.toList(),
                doc = doc,
                nameStart = rep.nameStart,
                members = mergedMembers
            )
        }

        val result = LinkedHashMap<String, MiniClassDecl>()
        for ((name, list) in buckets) {
            result[name] = mergeClassDecls(name, list)
        }
        // Root object alias
        if (result.containsKey("Obj") && !result.containsKey("Any")) {
            result["Any"] = result["Obj"]!!
        }
        return result
    }

    internal fun toMemberVal(cf: MiniCtorField): MiniMemberValDecl = MiniMemberValDecl(
        range = MiniRange(cf.nameStart, cf.nameStart),
        name = cf.name,
        mutable = cf.mutable,
        type = cf.type,
        initRange = null,
        doc = null,
        nameStart = cf.nameStart,
        isStatic = false,
        isExtern = false
    )

    fun resolveMemberWithInheritance(importedModules: List<String>, className: String, member: String, localMini: MiniScript? = null): Pair<String, MiniNamedDecl>? {
        val classes = aggregateClasses(importedModules, localMini)
        fun dfs(name: String, visited: MutableSet<String>): Pair<String, MiniNamedDecl>? {
            if (!visited.add(name)) return null
            val cls = classes[name]
            if (cls != null) {
                cls.members.firstOrNull { it.name == member }?.let { return name to it }
                cls.ctorFields.firstOrNull { it.name == member }?.let { return name to toMemberVal(it) }
                cls.classFields.firstOrNull { it.name == member }?.let { return name to toMemberVal(it) }
                for (baseName in cls.bases) {
                    dfs(baseName, visited)?.let { return it }
                }
            }
            // 1) local extensions in this class or bases
            localMini?.declarations?.firstOrNull { d ->
                (d is MiniFunDecl && d.receiver != null && simpleClassNameOf(d.receiver) == name && d.name == member) ||
                        (d is MiniValDecl && d.receiver != null && simpleClassNameOf(d.receiver) == name && d.name == member)
            }?.let { return name to it as MiniNamedDecl }

            // 2) built-in extensions from BuiltinDocRegistry
            for (mod in importedModules) {
                val decls = BuiltinDocRegistry.docsForModule(mod)
                decls.firstOrNull { d ->
                    (d is MiniFunDecl && d.receiver != null && simpleClassNameOf(d.receiver) == name && d.name == member) ||
                            (d is MiniValDecl && d.receiver != null && simpleClassNameOf(d.receiver) == name && d.name == member)
                }?.let { return name to it as MiniNamedDecl }
            }

            return null
        }
        return dfs(className, mutableSetOf())
    }

    fun collectExtensionMemberNames(importedModules: List<String>, className: String, localMini: MiniScript? = null): Set<String> {
        val classes = aggregateClasses(importedModules, localMini)
        val visited = mutableSetOf<String>()
        val result = mutableSetOf<String>()

        fun dfs(name: String) {
            if (!visited.add(name)) return
            // 1) stdlib extensions from BuiltinDocRegistry
            result.addAll(BuiltinDocRegistry.extensionMemberNamesFor(name))
            // 2) local extensions from mini
            localMini?.declarations?.forEach { d ->
                if (d is MiniFunDecl && d.receiver != null && simpleClassNameOf(d.receiver) == name) result.add(d.name)
                if (d is MiniValDecl && d.receiver != null && simpleClassNameOf(d.receiver) == name) result.add(d.name)
            }
            // 3) bases
            classes[name]?.bases?.forEach { dfs(it) }
        }

        dfs(className)
        // Hardcoded supplements for common containers if not explicitly in bases
        if (className == "List" || className == "Array") {
            dfs("Collection")
            dfs("Iterable")
        }
        dfs("Any")
        dfs("Obj")
        return result
    }

    fun findMemberAcrossClasses(importedModules: List<String>, member: String, localMini: MiniScript? = null): Pair<String, MiniNamedDecl>? {
        val classes = aggregateClasses(importedModules, localMini)
        // Preferred order for ambiguous common ops
        val preference = listOf("Iterable", "Iterator", "List")
        for (name in preference) {
            resolveMemberWithInheritance(importedModules, name, member, localMini)?.let { return it }
        }
        for ((name, cls) in classes) {
            cls.members.firstOrNull { it.name == member }?.let { return name to it }
        }
        return null
    }

    /**
     * Try to guess a class name of the receiver when the receiver is a call like `ClassName(...)`.
     * We walk left from the dot, find a matching `)` and then the identifier immediately before the `(`.
     * If that identifier matches a known class in any of the imported modules, return it.
     */
    fun guessClassFromCallBefore(text: String, dotPos: Int, importedModules: List<String>, localMini: MiniScript? = null): String? {
        var i = (dotPos - 1).coerceAtLeast(0)
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0 || text[i] != ')') return null
        // Walk back to matching '(' accounting nested parentheses
        var depth = 0
        i--
        while (i >= 0) {
            val ch = text[i]
            when (ch) {
                ')' -> depth++
                '(' -> if (depth == 0) break else depth--
                '\n' -> {}
            }
            i--
        }
        if (i < 0 || text[i] != '(') return null
        // Now find identifier immediately before '('
        var j = i - 1
        while (j >= 0 && text[j].isWhitespace()) j--
        val end = j + 1
        while (j >= 0 && isIdentChar(text[j])) j--
        val start = j + 1
        if (start >= end) return null
        val name = text.substring(start, end)
        // Validate against imported classes
        val classes = aggregateClasses(importedModules, localMini)
        return if (classes.containsKey(name)) name else null
    }

    fun guessReceiverClassViaMini(mini: MiniScript?, text: String, dotPos: Int, imported: List<String>, binding: BindingSnapshot? = null): String? {
        if (mini == null) return null
        val i = prevNonWs(text, dotPos - 1)
        if (i < 0) return null
        
        // Handle indexing x[0]. or literal [1].
        if (text[i] == ']') {
            return guessReceiverClass(text, dotPos, imported, mini)
        }

        val wordRange = wordRangeAt(text, i + 1) ?: return null
        val ident = text.substring(wordRange.first, wordRange.second)

        // 0) Use binding if available for precise resolution
        if (binding != null) {
            val ref = binding.references.firstOrNull { wordRange.first >= it.start && wordRange.first < it.end }
            if (ref != null) {
                val sym = binding.symbols.firstOrNull { it.id == ref.symbolId }
                if (sym != null) {
                    val type = findTypeByRange(mini, sym.name, sym.declStart, text, imported)
                    simpleClassNameOf(type)?.let { return it }
                }
            } else {
                // Check if it's a declaration (e.g. static access to a class)
                val sym = binding.symbols.firstOrNull { it.declStart == wordRange.first && it.name == ident }
                if (sym != null) {
                    val type = findTypeByRange(mini, sym.name, sym.declStart, text, imported)
                    simpleClassNameOf(type)?.let { return it }
                    // if it's a class/enum, return its name directly
                    if (sym.kind == net.sergeych.lyng.binding.SymbolKind.Class || sym.kind == net.sergeych.lyng.binding.SymbolKind.Enum) return sym.name
                }
            }
        }

        // 1) Declarations in current file (val/var/fun/class/enum), prioritized by proximity
        val src = mini.range.start.source
        val d = mini.declarations
            .filter { it.name == ident && src.offsetOf(it.nameStart) < dotPos }
            .maxByOrNull { src.offsetOf(it.nameStart) }

        if (d != null) {
            return when (d) {
                is MiniClassDecl -> d.name
                is MiniEnumDecl -> d.name
                is MiniValDecl -> simpleClassNameOf(d.type ?: inferTypeRefForVal(d, text, imported, mini))
                is MiniFunDecl -> simpleClassNameOf(d.returnType)
            }
        }

        // 2) Parameters in any function (best-effort fallback without scope mapping)
        for (fd in mini.declarations.filterIsInstance<MiniFunDecl>()) {
            for (p in fd.params) {
                if (p.name == ident) return simpleClassNameOf(p.type)
            }
        }

        // 2a) Try to find plain assignment in text if not found in declarations: x = test()
        inferTypeFromAssignmentInText(ident, text, imported, mini, beforeOffset = dotPos)?.let { return simpleClassNameOf(it) }

        // 3) Recursive chaining: Base.ident.
        val dotBefore = findDotLeft(text, wordRange.first)
        if (dotBefore != null) {
            val receiverClass = guessReceiverClassViaMini(mini, text, dotBefore, imported, binding)
                ?: guessReceiverClass(text, dotBefore, imported, mini)
            if (receiverClass != null) {
                val resolved = resolveMemberWithInheritance(imported, receiverClass, ident, mini)
                if (resolved != null) {
                    val rt = when (val m = resolved.second) {
                        is MiniMemberFunDecl -> m.returnType
                        is MiniMemberValDecl -> m.type ?: inferTypeRefFromInitRange(m.initRange, m.nameStart, text, imported, mini)
                        else -> null
                    }
                    return simpleClassNameOf(rt)
                }
            }
        }

        // 4) Check if it's a known class (static access)
        val classes = aggregateClasses(imported, mini)
        if (classes.containsKey(ident)) return ident

        return null
    }

    private fun inferTypeFromAssignmentInText(ident: String, text: String, imported: List<String>, mini: MiniScript?, beforeOffset: Int = Int.MAX_VALUE): MiniTypeRef? {
        // Heuristic: search for "val ident =" or "ident =" in text
        val re = Regex("(?:^|[\\n;])\\s*(?:val|var)?\\s*${ident}\\s*(?::\\s*([A-Za-z_][A-Za-z0-9_]*))?\\s*(?:=|by)\\s*([^\\n;]+)")
        val match = re.findAll(text)
            .filter { it.range.first < beforeOffset }
            .lastOrNull() ?: return null
        val explicitType = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (explicitType != null) return syntheticTypeRef(explicitType)
        val expr = match.groupValues.getOrNull(2)?.let { stripComments(it) } ?: return null
        return inferTypeRefFromExpression(expr, imported, mini, contextText = text, beforeOffset = beforeOffset)
    }

    private fun stripComments(text: String): String {
        var result = ""
        var i = 0
        var inString = false
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"' && (i == 0 || text[i - 1] != '\\')) {
                inString = !inString
            }
            if (!inString && ch == '/' && i + 1 < text.length) {
                if (text[i + 1] == '/') break // single line comment
                if (text[i + 1] == '*') {
                    // Skip block comment
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2 // Skip '*/'
                    continue
                }
            }
            result += ch
            i++
        }
        return result.trim()
    }

    fun guessReturnClassFromMemberCallBeforeMini(mini: MiniScript?, text: String, dotPos: Int, imported: List<String>, binding: BindingSnapshot? = null): String? {
        if (mini == null) return null
        var i = prevNonWs(text, dotPos - 1)
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
        while (j >= 0 && isIdentChar(text[j])) j--
        val start = j + 1
        if (start >= end) return null
        val callee = text.substring(start, end)
        // Ensure member call: dot before callee
        var k = start - 1
        while (k >= 0 && text[k].isWhitespace()) k--
        if (k < 0 || text[k] != '.') return null
        val prevDot = k
        // Resolve receiver class via MiniAst (ident like `x`)
        val receiverClass = guessReceiverClassViaMini(mini, text, prevDot, imported, binding) ?: return null
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
                val sig = sigs[callee]
                if (sig != null && sig.typeText != null) return sig.typeText
            }
        }
        // Else fallback to registry-based resolution (covers imported classes)
        return resolveMemberWithInheritance(imported, receiverClass, callee, mini)?.second?.let { m ->
            val rt = when (m) {
                is MiniMemberFunDecl -> m.returnType
                is MiniMemberValDecl -> m.type
                is MiniFunDecl -> m.returnType
                is MiniValDecl -> m.type
                else -> null
            }
            simpleClassNameOf(rt)
        }
    }

    data class ScannedSig(val kind: String, val params: List<String>?, val typeText: String?)

    fun scanLocalClassMembersFromText(mini: MiniScript, text: String, cls: MiniClassDecl): Map<String, ScannedSig> {
        val src = mini.range.start.source
        if (cls.nameStart.source != src) return emptyMap()
        val start = src.offsetOf(cls.bodyRange?.start ?: cls.range.start)
        val end = src.offsetOf(cls.bodyRange?.end ?: cls.range.end).coerceAtMost(text.length)
        if (start !in 0..end) return emptyMap()
        val body = text.substring(start, end)
        val map = LinkedHashMap<String, ScannedSig>()
        // fun name(params): Type (allowing modifiers like abstract, override, closed)
        val funRe = Regex("^\\s*(?:(?:abstract|override|closed|private|protected|static|open|extern)\\s+)*fun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*(?::\\s*([A-Za-z_][A-Za-z0-9_]*))?", RegexOption.MULTILINE)
        for (m in funRe.findAll(body)) {
            val name = m.groupValues.getOrNull(1) ?: continue
            val params = m.groupValues.getOrNull(2)?.split(',')?.mapNotNull { it.trim().takeIf { it.isNotEmpty() } } ?: emptyList()
            val type = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
            map[name] = ScannedSig("fun", params, type)
        }
        // val/var name: Type (allowing modifiers)
        val valRe = Regex("^\\s*(?:(?:abstract|override|closed|private|protected|static|open|extern)\\s+)*(val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*([A-Za-z_][A-Za-z0-9_]*))?", RegexOption.MULTILINE)
        for (m in valRe.findAll(body)) {
            val kind = m.groupValues.getOrNull(1) ?: continue
            val name = m.groupValues.getOrNull(2) ?: continue
            val type = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
            if (!map.containsKey(name)) map[name] = ScannedSig(kind, null, type)
        }
        return map
    }

    fun guessReceiverClass(text: String, dotPos: Int, imported: List<String>, mini: MiniScript? = null, beforeOffset: Int = dotPos): String? {
        guessClassFromCallBefore(text, dotPos, imported, mini)?.let { return it }
        var i = prevNonWs(text, dotPos - 1)
        if (i >= 0) {
            when (text[i]) {
                '"' -> return "String"
                ']' -> {
                    // Check if literal or indexing
                    val matchingOpen = findMatchingOpenBracket(text, i)
                    if (matchingOpen != null && matchingOpen > 0) {
                        val beforeOpen = prevNonWs(text, matchingOpen - 1)
                        if (beforeOpen >= 0 && (isIdentChar(text[beforeOpen]) || text[beforeOpen] == ')' || text[beforeOpen] == ']')) {
                            // Likely indexing: infer type of full expression
                            val exprText = text.substring(0, i + 1)
                            return simpleClassNameOf(inferTypeRefFromExpression(exprText, imported, mini, beforeOffset = beforeOffset))
                        }
                    }
                    return "List"
                }
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
                if (ch.isDigit()) {
                    hasDigits = true; j--; continue
                }
                if (ch == '.') {
                    hasDot = true; j--; continue
                }
                if (ch == 'e' || ch == 'E') {
                    hasExp = true; j--; if (j >= 0 && (text[j] == '+' || text[j] == '-')) j--; continue
                }
                if (ch in listOf('x', 'X', 'a', 'b', 'c', 'd', 'f', 'A', 'B', 'C', 'D', 'F')) {
                    j--; continue
                }
                break
            }
            if (hasDigits) return if (hasDot || hasExp) "Real" else "Int"

            // 3) this@Type or as Type
            val identRange = wordRangeAt(text, i + 1)
            if (identRange != null) {
                val ident = text.substring(identRange.first, identRange.second)

                // 3a) Handle plain "this"
                if (ident == "this" && mini != null) {
                    findEnclosingClass(mini, identRange.first)?.let { return it.name }
                }

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
                val classes = aggregateClasses(imported, mini)
                if (classes.containsKey(ident)) return ident
            }
        }
        return null
    }

    fun guessReturnClassFromMemberCallBefore(text: String, dotPos: Int, imported: List<String>, mini: MiniScript? = null): String? {
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
        val resolved = resolveMemberWithInheritance(imported, receiverClass, callee, mini) ?: return null
        val member = resolved.second
        val ret = when (member) {
            is MiniMemberFunDecl -> member.returnType
            is MiniMemberValDecl -> member.type
            is MiniFunDecl -> member.returnType
            is MiniValDecl -> member.type
            else -> null
        }
        return simpleClassNameOf(ret)
    }

    fun guessReturnClassFromTopLevelCallBefore(text: String, dotPos: Int, imported: List<String>, mini: MiniScript? = null): String? {
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

    fun guessReturnClassAcrossKnownCallees(text: String, dotPos: Int, imported: List<String>, mini: MiniScript? = null): String? {
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
        val resolved = findMemberAcrossClasses(imported, callee, mini) ?: return null
        val member = resolved.second
        val ret = when (member) {
            is MiniMemberFunDecl -> member.returnType
            is MiniMemberValDecl -> member.type
            is MiniFunDecl -> member.returnType
            is MiniValDecl -> member.type
            else -> null
        }
        return simpleClassNameOf(ret)
    }

    fun inferTypeRefFromExpression(text: String, imported: List<String>, mini: MiniScript? = null, contextText: String? = null, beforeOffset: Int = Int.MAX_VALUE): MiniTypeRef? {
        val trimmed = stripComments(text)
        if (trimmed.isEmpty()) return null
        val fullText = contextText ?: text

        // 1) Literals
        if (trimmed.startsWith("\"")) return syntheticTypeRef("String")
        if (trimmed.startsWith("[")) return syntheticTypeRef("List")
        if (trimmed.startsWith("{")) return syntheticTypeRef("Dict")
        if (trimmed == "true" || trimmed == "false") return syntheticTypeRef("Boolean")
        if (trimmed.all { it.isDigit() || it == '.' || it == '_' || it == 'e' || it == 'E' }) {
            val hasDigits = trimmed.any { it.isDigit() }
            if (hasDigits)
                return if (trimmed.contains('.') || trimmed.contains('e', ignoreCase = true)) syntheticTypeRef("Real") else syntheticTypeRef("Int")
        }

        // 2) Function/Constructor calls or Indexing
        if (trimmed.endsWith(")")) {
            val openParen = findMatchingOpenParen(trimmed, trimmed.length - 1)
            if (openParen != null && openParen > 0) {
                var j = openParen - 1
                while (j >= 0 && trimmed[j].isWhitespace()) j--
                val end = j + 1
                while (j >= 0 && isIdentChar(trimmed[j])) j--
                val start = j + 1
                if (start < end) {
                    val callee = trimmed.substring(start, end)

                    // Check if it's a member call (dot before callee)
                    var k = start - 1
                    while (k >= 0 && trimmed[k].isWhitespace()) k--
                    if (k >= 0 && trimmed[k] == '.') {
                        val prevDot = k
                        // Recursive: try to infer type of what's before the dot
                        val receiverText = trimmed.substring(0, prevDot)
                        val receiverType = inferTypeRefFromExpression(receiverText, imported, mini, contextText = fullText, beforeOffset = beforeOffset)
                        val receiverClass = simpleClassNameOf(receiverType)
                        if (receiverClass != null) {
                            val resolved = resolveMemberWithInheritance(imported, receiverClass, callee, mini)
                            if (resolved != null) {
                                return when (val m = resolved.second) {
                                    is MiniMemberFunDecl -> m.returnType
                                    is MiniMemberValDecl -> m.type ?: inferTypeRefFromInitRange(m.initRange, m.nameStart, fullText, imported, mini)
                                    else -> null
                                }
                            }
                        }
                    } else {
                        // Top-level call or constructor
                        val classes = aggregateClasses(imported, mini)
                        if (classes.containsKey(callee)) return syntheticTypeRef(callee)

                        for (mod in imported) {
                            val decls = BuiltinDocRegistry.docsForModule(mod)
                            val fn = decls.asSequence().filterIsInstance<MiniFunDecl>().firstOrNull { it.name == callee }
                            if (fn != null) return fn.returnType
                        }
                        mini?.declarations?.filterIsInstance<MiniFunDecl>()?.firstOrNull { it.name == callee }?.let { return it.returnType }
                    }
                }
            }
        }

        if (trimmed.endsWith("]")) {
            val openBracket = findMatchingOpenBracket(trimmed, trimmed.length - 1)
            if (openBracket != null && openBracket > 0) {
                val receiverText = trimmed.substring(0, openBracket).trim()
                if (receiverText.isNotEmpty()) {
                    val receiverType = inferTypeRefFromExpression(receiverText, imported, mini, contextText = fullText, beforeOffset = beforeOffset)
                    if (receiverType is MiniGenericType) {
                        val baseName = simpleClassNameOf(receiverType.base)
                        if (baseName == "List" && receiverType.args.isNotEmpty()) {
                            return receiverType.args[0]
                        }
                        if (baseName == "Map" && receiverType.args.size >= 2) {
                            return receiverType.args[1]
                        }
                    }
                    // Fallback for non-generic collections or if base name matches
                    val baseName = simpleClassNameOf(receiverType)
                    if (baseName == "List" || baseName == "Array" || baseName == "String") {
                        if (baseName == "String") return syntheticTypeRef("Char") 
                        return syntheticTypeRef("Any")
                    }
                }
            }
        }

        // 3) Member field or simple identifier at the end
        val lastWord = wordRangeAt(trimmed, trimmed.length)
        if (lastWord != null && lastWord.second == trimmed.length) {
            val ident = trimmed.substring(lastWord.first, lastWord.second)
            var k = lastWord.first - 1
            while (k >= 0 && trimmed[k].isWhitespace()) k--
            if (k >= 0 && trimmed[k] == '.') {
                // Member field: receiver.ident
                val receiverText = trimmed.substring(0, k).trim()
                val receiverType = inferTypeRefFromExpression(receiverText, imported, mini, contextText = fullText, beforeOffset = beforeOffset)
                val receiverClass = simpleClassNameOf(receiverType)
                if (receiverClass != null) {
                    val resolved = resolveMemberWithInheritance(imported, receiverClass, ident, mini)
                    if (resolved != null) {
                        return when (val m = resolved.second) {
                            is MiniMemberFunDecl -> m.returnType
                            is MiniMemberValDecl -> m.type ?: inferTypeRefFromInitRange(m.initRange, m.nameStart, fullText, imported, mini)
                            else -> null
                        }
                    }
                }
            } else {
                // Simple identifier
                // 1) Declarations in current file (val/var/fun/class/enum), prioritized by proximity
                val src = mini?.range?.start?.source
                val d = if (src != null) {
                    mini.declarations
                        .filter { it.name == ident && src.offsetOf(it.nameStart) < beforeOffset }
                        .maxByOrNull { src.offsetOf(it.nameStart) }
                } else {
                    mini?.declarations?.firstOrNull { it.name == ident }
                }

                if (d != null) {
                    return when (d) {
                        is MiniClassDecl -> syntheticTypeRef(d.name)
                        is MiniEnumDecl -> syntheticTypeRef(d.name)
                        is MiniValDecl -> d.type ?: inferTypeRefForVal(d, fullText, imported, mini)
                        is MiniFunDecl -> d.returnType
                    }
                }

                // 2) Parameters in any function
                for (fd in mini?.declarations?.filterIsInstance<MiniFunDecl>() ?: emptyList()) {
                    for (p in fd.params) {
                        if (p.name == ident) return p.type
                    }
                }

                // 3) Try to find plain assignment in text: ident = expr
                inferTypeFromAssignmentInText(ident, fullText, imported, mini, beforeOffset = beforeOffset)?.let { return it }

                // 4) Check if it's a known class (static access)
                val classes = aggregateClasses(imported, mini)
                if (classes.containsKey(ident)) return syntheticTypeRef(ident)
            }
        }

        return null
    }

    private fun findMatchingOpenBracket(text: String, closeBracketPos: Int): Int? {
        if (closeBracketPos < 0 || closeBracketPos >= text.length || text[closeBracketPos] != ']') return null
        var depth = 0
        var i = closeBracketPos - 1
        while (i >= 0) {
            when (text[i]) {
                ']' -> depth++
                '[' -> if (depth == 0) return i else depth--
            }
            i--
        }
        return null
    }

    private fun findMatchingOpenParen(text: String, closeParenPos: Int): Int? {
        if (closeParenPos < 0 || closeParenPos >= text.length || text[closeParenPos] != ')') return null
        var depth = 0
        var i = closeParenPos - 1
        while (i >= 0) {
            when (text[i]) {
                ')' -> depth++
                '(' -> if (depth == 0) return i else depth--
            }
            i--
        }
        return null
    }

    private fun syntheticTypeRef(name: String): MiniTypeRef =
        MiniTypeName(MiniRange(net.sergeych.lyng.Pos.builtIn, net.sergeych.lyng.Pos.builtIn),
            listOf(MiniTypeName.Segment(name, MiniRange(net.sergeych.lyng.Pos.builtIn, net.sergeych.lyng.Pos.builtIn))), false)

    fun inferTypeRefForVal(vd: MiniValDecl, text: String, imported: List<String>, mini: MiniScript?): MiniTypeRef? {
        return inferTypeRefFromInitRange(vd.initRange, vd.nameStart, text, imported, mini)
    }

    fun inferTypeRefFromInitRange(initRange: MiniRange?, nameStart: net.sergeych.lyng.Pos, text: String, imported: List<String>, mini: MiniScript?): MiniTypeRef? {
        val range = initRange ?: return null
        val src = mini?.range?.start?.source ?: return null
        val start = src.offsetOf(range.start)
        val end = src.offsetOf(range.end)
        if (start < 0 || start >= end || end > text.length) return null

        var exprText = text.substring(start, end).trim()
        if (exprText.startsWith("=")) {
            exprText = exprText.substring(1).trim()
        }
        if (exprText.startsWith("by")) {
            exprText = exprText.substring(2).trim()
        }
        val beforeOffset = src.offsetOf(nameStart)
        return inferTypeRefFromExpression(exprText, imported, mini, contextText = text, beforeOffset = beforeOffset)
    }

    fun simpleClassNameOf(t: MiniTypeRef?): String? = when (t) {
        null -> null
        is MiniTypeName -> t.segments.lastOrNull()?.name
        is MiniGenericType -> simpleClassNameOf(t.base)
        is MiniFunctionType -> null
        is MiniTypeVar -> null
    }

    fun typeOf(t: MiniTypeRef?): String = when (t) {
        is MiniTypeName -> t.segments.joinToString(".") { it.name } + (if (t.nullable) "?" else "")
        is MiniGenericType -> typeOf(t.base) + "<" + t.args.joinToString(", ") { typeOf(it) } + ">" + (if (t.nullable) "?" else "")
        is MiniFunctionType -> {
            val r = t.receiver?.let { typeOf(it) + "." } ?: ""
            r + "(" + t.params.joinToString(", ") { typeOf(it) } + ") -> " + typeOf(t.returnType) + (if (t.nullable) "?" else "")
        }
        is MiniTypeVar -> t.name + (if (t.nullable) "?" else "")
        null -> ""
    }

    fun findDotLeft(text: String, offset: Int): Int? {
        var i = (offset - 1).coerceAtLeast(0)
        while (i >= 0 && text[i].isWhitespace()) i--
        return if (i >= 0 && text[i] == '.') i else null
    }

    fun prevNonWs(text: String, start: Int): Int {
        var i = start.coerceAtMost(text.length - 1)
        while (i >= 0 && text[i].isWhitespace()) i--
        return i
    }

    fun wordRangeAt(text: String, offset: Int): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        val off = offset.coerceIn(0, text.length)
        var s = off
        var e = off
        while (s > 0 && isIdentChar(text[s - 1])) s--
        while (e < text.length && isIdentChar(text[e])) e++
        return if (s < e) s to e else null
    }

    fun isIdentChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    fun enumToSyntheticClass(en: MiniEnumDecl): MiniClassDecl {
        val staticMembers = mutableListOf<MiniMemberDecl>()
        // entries: List
        staticMembers.add(MiniMemberValDecl(en.range, "entries", false, null, null, null, en.nameStart, isStatic = true))
        // valueOf(name: String): Enum
        staticMembers.add(MiniMemberFunDecl(en.range, "valueOf", listOf(MiniParam("name", null, en.nameStart)), null, null, en.nameStart, isStatic = true))

        // Also add each entry as a static member (const)
        for (entry in en.entries) {
            staticMembers.add(MiniMemberValDecl(en.range, entry, false, MiniTypeName(en.range, listOf(MiniTypeName.Segment(en.name, en.range)), false), null, null, en.nameStart, isStatic = true))
        }

        return MiniClassDecl(
            range = en.range,
            name = en.name,
            bases = listOf("Enum"),
            bodyRange = null,
            doc = en.doc,
            nameStart = en.nameStart,
            members = staticMembers
        )
    }
}
