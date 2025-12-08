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

    fun aggregateClasses(importedModules: List<String>): Map<String, MiniClassDecl> {
        // Collect all class decls by name across modules, then merge duplicates by unioning members and bases.
        val buckets = LinkedHashMap<String, MutableList<MiniClassDecl>>()
        for (mod in importedModules) {
            val docs = BuiltinDocRegistry.docsForModule(mod)
            for (cls in docs.filterIsInstance<MiniClassDecl>()) {
                buckets.getOrPut(cls.name) { mutableListOf() }.add(cls)
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

    fun resolveMemberWithInheritance(importedModules: List<String>, className: String, member: String): Pair<String, MiniMemberDecl>? {
        val classes = aggregateClasses(importedModules)
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

    fun findMemberAcrossClasses(importedModules: List<String>, member: String): Pair<String, MiniMemberDecl>? {
        val classes = aggregateClasses(importedModules)
        // Preferred order for ambiguous common ops
        val preference = listOf("Iterable", "Iterator", "List")
        for (name in preference) {
            resolveMemberWithInheritance(importedModules, name, member)?.let { return it }
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
    fun guessClassFromCallBefore(text: String, dotPos: Int, importedModules: List<String>): String? {
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
        val classes = aggregateClasses(importedModules)
        return if (classes.containsKey(name)) name else null
    }

    private fun isIdentChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
}
