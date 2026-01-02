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

object DocLookupUtils {
    /**
     * Convert MiniAst imports to fully-qualified module names expected by BuiltinDocRegistry.
     * Heuristics:
     *  - If an import does not start with "lyng.", prefix it with "lyng." (e.g., "io.fs" -> "lyng.io.fs").
     *  - Keep original if it already starts with "lyng.".
     *  - Always include "lyng.stdlib" to make builtins available for docs.
     */
    fun canonicalImportedModules(mini: MiniScript): List<String> {
        val raw = mini.imports.map { it.segments.joinToString(".") { s -> s.name } }
        val result = LinkedHashSet<String>()
        for (name in raw) {
            val canon = if (name.startsWith("lyng.")) name else "lyng.$name"
            result.add(canon)
        }
        // Always make stdlib available as a fallback context for common types,
        // even when there are no explicit imports in the file
        result.add("lyng.stdlib")
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
                buckets.getOrPut(en.name) { mutableListOf() }.add(en.toSyntheticClass())
            }
        }
        
        // Add local declarations
        localMini?.declarations?.forEach { d ->
            when (d) {
                is MiniClassDecl -> {
                    buckets.getOrPut(d.name) { mutableListOf() }.add(d)
                }
                is MiniEnumDecl -> {
                    val syn = d.toSyntheticClass()
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
        // Skip spaces
        while (i >= 0 && text[i].isWhitespace()) i++
        // Note: the previous line is wrong direction; correct implementation below
        i = (dotPos - 1).coerceAtLeast(0)
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

    private fun isIdentChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    private fun MiniEnumDecl.toSyntheticClass(): MiniClassDecl {
        val staticMembers = mutableListOf<MiniMemberDecl>()
        // entries: List
        staticMembers.add(MiniMemberValDecl(range, "entries", false, null, null, nameStart, isStatic = true))
        // valueOf(name: String): Enum
        staticMembers.add(MiniMemberFunDecl(range, "valueOf", listOf(MiniParam("name", null, nameStart)), null, null, nameStart, isStatic = true))

        // Also add each entry as a static member (const)
        for (entry in entries) {
            staticMembers.add(MiniMemberValDecl(range, entry, false, MiniTypeName(range, listOf(MiniTypeName.Segment(name, range)), false), null, nameStart, isStatic = true))
        }

        return MiniClassDecl(
            range = range,
            name = name,
            bases = listOf("Enum"),
            bodyRange = null,
            doc = doc,
            nameStart = nameStart,
            members = staticMembers
        )
    }
}
