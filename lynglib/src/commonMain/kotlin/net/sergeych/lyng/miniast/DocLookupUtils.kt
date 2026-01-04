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

    fun findTypeByRange(mini: MiniScript?, name: String, startOffset: Int): MiniTypeRef? {
        if (mini == null) return null
        val src = mini.range.start.source

        for (d in mini.declarations) {
            if (d.name == name && src.offsetOf(d.nameStart) == startOffset) {
                return when (d) {
                    is MiniValDecl -> d.type
                    is MiniFunDecl -> d.returnType
                    else -> null
                }
            }

            if (d is MiniFunDecl) {
                for (p in d.params) {
                    if (p.name == name && src.offsetOf(p.nameStart) == startOffset) return p.type
                }
            }

            if (d is MiniClassDecl) {
                for (m in d.members) {
                    if (m.name == name && src.offsetOf(m.nameStart) == startOffset) {
                        return when (m) {
                            is MiniMemberFunDecl -> m.returnType
                            is MiniMemberValDecl -> m.type
                            else -> null
                        }
                    }
                }
                for (cf in d.ctorFields) {
                    if (cf.name == name && src.offsetOf(cf.nameStart) == startOffset) return cf.type
                }
                for (cf in d.classFields) {
                    if (cf.name == name && src.offsetOf(cf.nameStart) == startOffset) return cf.type
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
            var doc: MiniDoc? = null
            for (c in list) {
                bases.addAll(c.bases)
                if (doc == null && c.doc != null && c.doc.raw.isNotBlank()) doc = c.doc
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
                ctorFields = rep.ctorFields,
                classFields = rep.classFields,
                doc = doc,
                nameStart = rep.nameStart,
                members = mergedMembers
            )
        }

        val result = LinkedHashMap<String, MiniClassDecl>()
        for ((name, list) in buckets) {
            result[name] = mergeClassDecls(name, list)
        }
        return result
    }

    fun resolveMemberWithInheritance(importedModules: List<String>, className: String, member: String, localMini: MiniScript? = null): Pair<String, MiniMemberDecl>? {
        val classes = aggregateClasses(importedModules, localMini)
        fun dfs(name: String, visited: MutableSet<String>): Pair<String, MiniMemberDecl>? {
            val cls = classes[name] ?: return null
            cls.members.firstOrNull { it.name == member }?.let { return name to it }
            if (!visited.add(name)) return null
            for (baseName in cls.bases) {
                dfs(baseName, visited)?.let { return it }
            }
            return null
        }
        return dfs(className, mutableSetOf())
    }

    fun findMemberAcrossClasses(importedModules: List<String>, member: String, localMini: MiniScript? = null): Pair<String, MiniMemberDecl>? {
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
        val wordRange = wordRangeAt(text, i + 1) ?: return null
        val ident = text.substring(wordRange.first, wordRange.second)

        // 0) Use binding if available for precise resolution
        if (binding != null) {
            val ref = binding.references.firstOrNull { wordRange.first >= it.start && wordRange.first < it.end }
            if (ref != null) {
                val sym = binding.symbols.firstOrNull { it.id == ref.symbolId }
                if (sym != null) {
                    val type = findTypeByRange(mini, sym.name, sym.declStart)
                    simpleClassNameOf(type)?.let { return it }
                }
            } else {
                // Check if it's a declaration (e.g. static access to a class)
                val sym = binding.symbols.firstOrNull { it.declStart == wordRange.first && it.name == ident }
                if (sym != null) {
                    val type = findTypeByRange(mini, sym.name, sym.declStart)
                    simpleClassNameOf(type)?.let { return it }
                    // if it's a class/enum, return its name directly
                    if (sym.kind == net.sergeych.lyng.binding.SymbolKind.Class || sym.kind == net.sergeych.lyng.binding.SymbolKind.Enum) return sym.name
                }
            }
        }

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

        // 2) Parameters in any function (best-effort fallback without scope mapping)
        for (fd in mini.declarations.filterIsInstance<MiniFunDecl>()) {
            for (p in fd.params) {
                if (p.name == ident) return simpleClassNameOf(p.type)
            }
        }

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
                        is MiniMemberValDecl -> m.type
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
                is MiniInitDecl -> null
            }
            simpleClassNameOf(rt)
        }
    }

    data class ScannedSig(val kind: String, val params: List<String>?, val typeText: String?)

    fun scanLocalClassMembersFromText(mini: MiniScript, text: String, cls: MiniClassDecl): Map<String, ScannedSig> {
        val src = mini.range.start.source
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

    fun guessReceiverClass(text: String, dotPos: Int, imported: List<String>, mini: MiniScript? = null): String? {
        guessClassFromCallBefore(text, dotPos, imported, mini)?.let { return it }
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
            is MiniInitDecl -> null
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
            is MiniInitDecl -> null
        }
        return simpleClassNameOf(ret)
    }

    fun simpleClassNameOf(t: MiniTypeRef?): String? = when (t) {
        null -> null
        is MiniTypeName -> t.segments.lastOrNull()?.name
        is MiniGenericType -> simpleClassNameOf(t.base)
        is MiniFunctionType -> null
        is MiniTypeVar -> null
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
        staticMembers.add(MiniMemberValDecl(en.range, "entries", false, null, null, en.nameStart, isStatic = true))
        // valueOf(name: String): Enum
        staticMembers.add(MiniMemberFunDecl(en.range, "valueOf", listOf(MiniParam("name", null, en.nameStart)), null, null, en.nameStart, isStatic = true))

        // Also add each entry as a static member (const)
        for (entry in en.entries) {
            staticMembers.add(MiniMemberValDecl(en.range, entry, false, MiniTypeName(en.range, listOf(MiniTypeName.Segment(en.name, en.range)), false), null, en.nameStart, isStatic = true))
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
