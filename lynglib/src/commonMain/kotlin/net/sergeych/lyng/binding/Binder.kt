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
 * Minimal binding for editor/highlighter: builds a simple scope tree from Mini-AST
 * and binds identifier usages to declarations without type analysis.
 */
package net.sergeych.lyng.binding

import net.sergeych.lyng.Source
import net.sergeych.lyng.highlight.HighlightKind
import net.sergeych.lyng.highlight.SimpleLyngHighlighter
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.miniast.*

enum class SymbolKind { Class, Enum, Function, Value, Variable, Parameter }

data class Symbol(
    val id: Int,
    val name: String,
    val kind: SymbolKind,
    val declStart: Int,
    val declEnd: Int,
    val containerId: Int?,
    val type: String? = null
)

data class Reference(val symbolId: Int, val start: Int, val end: Int)

data class BindingSnapshot(
    val symbols: List<Symbol>,
    val references: List<Reference>
)

/**
 * Very small binder that:
 * - Registers symbols for top-level decls, function params, and local vals/vars inside function bodies.
 * - Binds identifier tokens to the nearest matching symbol by lexical scope (function locals/params first, then top-level).
 */
object Binder {
    fun bind(text: String, mini: MiniScript): BindingSnapshot {
        val source = Source("<snippet>", text)
        val highlighter = SimpleLyngHighlighter()
        val spans = highlighter.highlight(text)

        // 1) Collect symbols
        val symbols = ArrayList<Symbol>()
        var nextId = 1

        // Helper to convert Pos to offsets
        fun nameOffsets(startPos: net.sergeych.lyng.Pos, name: String): Pair<Int, Int> {
            val s = source.offsetOf(startPos)
            return s to (s + name.length)
        }

        // Class scopes to support fields and method resolution order
        data class ClassScope(
            val symId: Int,
            val bodyStart: Int,
            val bodyEnd: Int,
            val fields: MutableList<Int>
        )
        val classes = ArrayList<ClassScope>()

        // Map of function id to its local symbol ids and owning class (if method)
        data class FnScope(
            val id: Int,
            val rangeStart: Int,
            val rangeEnd: Int,
            val locals: MutableList<Int>,
            val classOwnerId: Int?
        )
        val functions = ArrayList<FnScope>()

        // Index top-level for quick lookups
        val topLevelByName = HashMap<String, MutableList<Int>>()

        // Helper to find class that contains an offset (by body range)
        fun classContaining(offset: Int): ClassScope? =
            classes.asSequence()
                .filter { it.bodyEnd > it.bodyStart && offset >= it.bodyStart && offset <= it.bodyEnd }
                .maxByOrNull { it.bodyEnd - it.bodyStart }

        // First pass (classes only): register classes so we can attach methods/fields
        for (d in mini.declarations) if (d is MiniClassDecl) {
            val (s, e) = nameOffsets(d.nameStart, d.name)
            val sym = Symbol(nextId++, d.name, SymbolKind.Class, s, e, containerId = null, type = d.name)
            symbols += sym
            topLevelByName.getOrPut(d.name) { mutableListOf() }.add(sym.id)
            // Prefer explicit body range; otherwise use the whole class declaration range
            val bodyStart = d.bodyRange?.start?.let { source.offsetOf(it) } ?: source.offsetOf(d.range.start)
            val bodyEnd = d.bodyRange?.end?.let { source.offsetOf(it) } ?: source.offsetOf(d.range.end)
            val classScope = ClassScope(sym.id, bodyStart, bodyEnd, mutableListOf())
            classes += classScope
            // Constructor fields (val/var in primary ctor)
            for (cf in d.ctorFields) {
                val fs = source.offsetOf(cf.nameStart)
                val fe = fs + cf.name.length
                val kind = if (cf.mutable) SymbolKind.Variable else SymbolKind.Value
                val fieldSym = Symbol(nextId++, cf.name, kind, fs, fe, containerId = sym.id, type = DocLookupUtils.typeOf(cf.type))
                symbols += fieldSym
                classScope.fields += fieldSym.id
            }
            // Class fields (val/var in class body, if any are reported here)
            for (cf in d.classFields) {
                val fs = source.offsetOf(cf.nameStart)
                val fe = fs + cf.name.length
                val kind = if (cf.mutable) SymbolKind.Variable else SymbolKind.Value
                val fieldSym = Symbol(nextId++, cf.name, kind, fs, fe, containerId = sym.id, type = DocLookupUtils.typeOf(cf.type))
                symbols += fieldSym
                classScope.fields += fieldSym.id
            }
            // Members (including fields and methods)
            for (m in d.members) {
                if (m is MiniMemberValDecl) {
                    val fs = source.offsetOf(m.nameStart)
                    val fe = fs + m.name.length
                    val kind = if (m.mutable) SymbolKind.Variable else SymbolKind.Value
                    val fieldSym = Symbol(nextId++, m.name, kind, fs, fe, containerId = sym.id, type = DocLookupUtils.typeOf(m.type))
                    symbols += fieldSym
                    classScope.fields += fieldSym.id
                }
            }
        }

        fun registerFun(name: String, nameStart: net.sergeych.lyng.Pos, params: List<MiniParam>, returnType: MiniTypeRef?, bodyRange: MiniRange?, isTopLevel: Boolean) {
            val (s, e) = nameOffsets(nameStart, name)
            val ownerClass = classContaining(s)
            val sym = Symbol(nextId++, name, SymbolKind.Function, s, e, containerId = ownerClass?.symId, type = DocLookupUtils.typeOf(returnType))
            symbols += sym
            if (isTopLevel) {
                topLevelByName.getOrPut(name) { mutableListOf() }.add(sym.id)
            }

            // Determine body range if present; otherwise, derive a conservative end at decl range end
            val bodyStart = bodyRange?.start?.let { source.offsetOf(it) } ?: e
            val bodyEnd = bodyRange?.end?.let { source.offsetOf(it) } ?: e
            val fnScope = FnScope(sym.id, bodyStart, bodyEnd, mutableListOf(), ownerClass?.symId)

            // Params
            for (p in params) {
                val ps = source.offsetOf(p.nameStart)
                val pe = ps + p.name.length
                val pk = SymbolKind.Parameter
                val paramSym = Symbol(nextId++, p.name, pk, ps, pe, containerId = sym.id, type = DocLookupUtils.typeOf(p.type))
                fnScope.locals += paramSym.id
                symbols += paramSym
            }
            functions += fnScope
        }

        // Second pass: functions and top-level/class vals/vars
        for (d in mini.declarations) {
            when (d) {
                is MiniClassDecl -> {
                    for (m in d.members) {
                        if (m is MiniMemberFunDecl) {
                            registerFun(m.name, m.nameStart, m.params, m.returnType, m.body?.range, false)
                        }
                    }
                }
                is MiniFunDecl -> {
                    registerFun(d.name, d.nameStart, d.params, d.returnType, d.body?.range, true)
                }
                is MiniValDecl -> {
                    val (s, e) = nameOffsets(d.nameStart, d.name)
                    val kind = if (d.mutable) SymbolKind.Variable else SymbolKind.Value
                    val ownerClass = classContaining(s)
                    if (ownerClass != null) {
                        // class field
                        val fieldSym = Symbol(nextId++, d.name, kind, s, e, containerId = ownerClass.symId, type = DocLookupUtils.typeOf(d.type))
                        symbols += fieldSym
                        ownerClass.fields += fieldSym.id
                    } else {
                        val sym = Symbol(nextId++, d.name, kind, s, e, containerId = null, type = DocLookupUtils.typeOf(d.type))
                        symbols += sym
                        topLevelByName.getOrPut(d.name) { mutableListOf() }.add(sym.id)
                    }
                }
                is MiniEnumDecl -> {
                    val (s, e) = nameOffsets(d.nameStart, d.name)
                    val sym = Symbol(nextId++, d.name, SymbolKind.Enum, s, e, containerId = null, type = d.name)
                    symbols += sym
                    topLevelByName.getOrPut(d.name) { mutableListOf() }.add(sym.id)
                }
            }
        }

        // Inject stdlib symbols into the top-level when imported, so calls like `filter {}` bind semantically.
        run {
            val importedModules = mini.imports.map { it.segments.joinToString(".") { s -> s.name } }
            val hasStdlib = importedModules.any { it == "lyng.stdlib" || it.startsWith("lyng.stdlib.") }
            if (hasStdlib) {
                val stdFns = listOf(
                    "filter", "map", "flatMap", "reduce", "fold", "take", "drop", "sorted",
                    "groupBy", "count", "any", "all", "first", "last", "sum", "joinToString",
                    // iterator trio often used implicitly/explicitly
                    "iterator", "hasNext", "next"
                )
                for (name in stdFns) {
                    val sym = Symbol(nextId++, name, SymbolKind.Function, 0, name.length, containerId = null, type = null)
                    symbols += sym
                    topLevelByName.getOrPut(name) { mutableListOf() }.add(sym.id)
                }
            }
        }

        // Second pass: attach local val/var declarations that fall inside any function body
        for (d in mini.declarations) if (d is MiniValDecl) {
            val (s, e) = nameOffsets(d.nameStart, d.name)
            // Find containing function by body range that includes this declaration name
            val containerFn = functions.asSequence()
                .filter { it.rangeEnd > it.rangeStart && s >= it.rangeStart && s <= it.rangeEnd }
                .maxByOrNull { it.rangeEnd - it.rangeStart }
            if (containerFn != null) {
                val fnSymId = containerFn.id
                val kind = if (d.mutable) SymbolKind.Variable else SymbolKind.Value
                val localSym = Symbol(nextId++, d.name, kind, s, e, containerId = fnSymId, type = DocLookupUtils.typeOf(d.type))
                symbols += localSym
                containerFn.locals += localSym.id
            }
        }

        // Extra pass: detect local val/var declarations that are not present in mini.declarations
        // (e.g., locals inside blocks and top-level statements). Use token stream heuristics.
        run {
            val spans = highlighter.highlight(text)
            var iTok = 0
            while (iTok < spans.size) {
                val t = spans[iTok]
                if (t.kind == HighlightKind.Keyword) {
                    val kw = try { text.substring(t.range.start, t.range.endExclusive) } catch (_: Throwable) { "" }
                    if (kw.equals("val", ignoreCase = true) || kw.equals("var", ignoreCase = true)) {
                        // Find the next Identifier span which should be the declared name
                        var j = iTok + 1
                        var nameStart = -1
                        var nameEnd = -1
                        while (j < spans.size) {
                            val s = spans[j]
                            if (s.kind == HighlightKind.Identifier) {
                                nameStart = s.range.start
                                nameEnd = s.range.endExclusive
                                break
                            }
                            // Stop if we hit newline/comment too far ahead (avoid wide scans)
                            if (s.kind == HighlightKind.Comment) break
                            j++
                        }
                        if (nameStart >= 0 && nameEnd > nameStart) {
                            // Skip if a symbol with exactly this decl range already exists
                            val exists = symbols.any { it.declStart == nameStart && it.declEnd == nameEnd }
                            if (!exists) {
                                // Determine enclosing function body (if any) to attach as local; else treat as top-level
                                val inFn = functions.asSequence()
                                    .filter { it.rangeEnd > it.rangeStart && nameStart >= it.rangeStart && nameStart <= it.rangeEnd }
                                    .maxByOrNull { it.rangeEnd - it.rangeStart }
                                val kind = if (kw.equals("var", true)) SymbolKind.Variable else SymbolKind.Value
                                if (inFn != null) {
                                    val localSym = Symbol(nextId++, text.substring(nameStart, nameEnd), kind, nameStart, nameEnd, containerId = inFn.id, type = null)
                                    symbols += localSym
                                    inFn.locals += localSym.id
                                } else {
                                    val localSym = Symbol(nextId++, text.substring(nameStart, nameEnd), kind, nameStart, nameEnd, containerId = null, type = null)
                                    symbols += localSym
                                    topLevelByName.getOrPut(localSym.name) { mutableListOf() }.add(localSym.id)
                                }
                            }
                        }
                        iTok = j // continue from the name or comment
                    }
                }
                iTok++
            }
        }

        // Build name -> symbol ids index per function (locals+params) and per class (fields) for faster resolution
        data class Idx(val byName: Map<String, List<Int>>)
        fun buildIndex(ids: List<Int>): Idx {
            val map = HashMap<String, MutableList<Int>>()
            for (id in ids) {
                val s = symbols.first { it.id == id }
                map.getOrPut(s.name) { mutableListOf() }.add(id)
            }
            return Idx(map)
        }
        val fnLocalIndex = HashMap<Int, Idx>() // key: function symbol id
        for (fn in functions) fnLocalIndex[fn.id] = buildIndex(fn.locals)
        val classFieldIndex = HashMap<Int, Idx>() // key: class symbol id
        for (cls in classes) classFieldIndex[cls.symId] = buildIndex(cls.fields)

        // Helper to find nearest match among candidates: pick the declaration with the smallest (usageStart - declStart) >= 0
        fun chooseNearest(candidates: List<Int>, usageStart: Int): Int? {
            var best: Int? = null
            var bestDist = Int.MAX_VALUE
            for (id in candidates) {
                val s = symbols.first { it.id == id }
                val dist = usageStart - s.declStart
                if (dist >= 0 && dist < bestDist) {
                    bestDist = dist; best = id
                }
            }
            return best
        }

        // 2) Bind identifier usages to symbols
        val references = ArrayList<Reference>()
        for (span in spans) {
            if (span.kind != HighlightKind.Identifier) continue
            val start = span.range.start
            val end = span.range.endExclusive
            // Skip if this range equals a known declaration identifier range; those are styled separately
            val isDecl = symbols.any { it.declStart == start && it.declEnd == end }
            if (isDecl) continue

            val name = text.substring(start, end)

            // Prefer function-local matches: find the function whose body contains the usage
            val inFn = functions.asSequence()
                .filter { it.rangeEnd > it.rangeStart && start >= it.rangeStart && start <= it.rangeEnd }
                .maxByOrNull { it.rangeEnd - it.rangeStart }
            if (inFn != null) {
                val idx = fnLocalIndex[inFn.id]
                val locIds = idx?.byName?.get(name)
                val hit = if (!locIds.isNullOrEmpty()) chooseNearest(locIds, start) else null
                if (hit != null) {
                    references += Reference(hit, start, end)
                    continue
                }

                // If function belongs to a class, check class fields next
                val ownerClassId = inFn.classOwnerId
                if (ownerClassId != null) {
                    val cidx = classFieldIndex[ownerClassId]
                    val cfIds = cidx?.byName?.get(name)
                    val cHit = if (!cfIds.isNullOrEmpty()) chooseNearest(cfIds, start) else null
                    if (cHit != null) {
                        references += Reference(cHit, start, end)
                        continue
                    }
                }
            }

            // Else try top-level by name
            val topIds = topLevelByName[name]
            val topHit = if (!topIds.isNullOrEmpty()) chooseNearest(topIds, start) else null
            if (topHit != null) {
                references += Reference(topHit, start, end)
                continue
            }

            // Fallback: choose the nearest declared symbol with this name regardless of scope (best effort)
            val anyIds = symbols.filter { it.name == name }.map { it.id }
            val anyHit = if (anyIds.isNotEmpty()) chooseNearest(anyIds, start) else null
            if (anyHit != null) references += Reference(anyHit, start, end)
        }

        return BindingSnapshot(symbols, references)
    }
}
