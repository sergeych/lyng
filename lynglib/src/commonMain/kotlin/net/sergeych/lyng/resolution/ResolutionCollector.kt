/*
 * Copyright 2026 Sergey S. Chernov
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

package net.sergeych.lyng.resolution

import net.sergeych.lyng.Pos

class ResolutionCollector(private val moduleName: String) : ResolutionSink {

    private data class Decl(
        val name: String,
        val kind: SymbolKind,
        val isMutable: Boolean,
        val pos: Pos,
        val isOverride: Boolean
    )

    private data class Ref(
        val name: String,
        val pos: Pos,
        val qualifier: String? = null
    )

    private data class ReflectRef(
        val name: String,
        val pos: Pos
    )

    private data class MemberInfo(
        val name: String,
        val isOverride: Boolean,
        val pos: Pos
    )

    private data class ClassInfo(
        val name: String,
        val bases: List<String>,
        val pos: Pos,
        val members: MutableMap<String, MemberInfo> = LinkedHashMap()
    )

    private class ScopeNode(
        val kind: ScopeKind,
        val pos: Pos,
        val parent: ScopeNode?,
        val className: String? = null,
        val bases: List<String> = emptyList()
    ) {
        val decls: LinkedHashMap<String, Decl> = LinkedHashMap()
        val refs: MutableList<Ref> = ArrayList()
        val memberRefs: MutableList<Ref> = ArrayList()
        val reflectRefs: MutableList<ReflectRef> = ArrayList()
        val captures: LinkedHashMap<String, CaptureInfo> = LinkedHashMap()
        val children: MutableList<ScopeNode> = ArrayList()
    }

    private var root: ScopeNode? = null
    private var current: ScopeNode? = null

    private val symbols = ArrayList<ResolvedSymbol>()
    private val captures = LinkedHashMap<String, CaptureInfo>()
    private val errors = ArrayList<ResolutionError>()
    private val warnings = ArrayList<ResolutionWarning>()
    private val classes = LinkedHashMap<String, ClassInfo>()

    override fun enterScope(kind: ScopeKind, pos: Pos, className: String?, bases: List<String>) {
        val parent = current
        val node = ScopeNode(kind, pos, parent, className, bases)
        if (root == null) {
            root = node
        }
        parent?.children?.add(node)
        current = node
        if (kind == ScopeKind.CLASS && className != null) {
            classes.getOrPut(className) { ClassInfo(className, bases.toList(), pos) }
        }
    }

    override fun exitScope(pos: Pos) {
        current = current?.parent
    }

    override fun declareClass(name: String, bases: List<String>, pos: Pos) {
        val existing = classes[name]
        if (existing == null) {
            classes[name] = ClassInfo(name, bases.toList(), pos)
        } else if (existing.bases.isEmpty() && bases.isNotEmpty()) {
            classes[name] = existing.copy(bases = bases.toList())
        }
    }

    override fun declareSymbol(
        name: String,
        kind: SymbolKind,
        isMutable: Boolean,
        pos: Pos,
        isOverride: Boolean
    ) {
        val node = current ?: return
        node.decls[name] = Decl(name, kind, isMutable, pos, isOverride)
        if (kind == SymbolKind.LOCAL || kind == SymbolKind.PARAM) {
            val classScope = findNearestClassScope(node)
            if (classScope != null && classScope.decls.containsKey(name)) {
                warnings += ResolutionWarning("shadowing member: $name", pos)
            }
        }
        if (kind == SymbolKind.MEMBER) {
            val classScope = findNearestClassScope(node)
            val className = classScope?.className
            if (className != null) {
                val info = classes.getOrPut(className) { ClassInfo(className, classScope.bases, classScope.pos) }
                info.members[name] = MemberInfo(name, isOverride, pos)
            }
        }
        symbols += ResolvedSymbol(
            name = name,
            origin = originForDecl(node, kind),
            slotIndex = -1,
            pos = pos
        )
    }

    override fun reference(name: String, pos: Pos) {
        val node = current ?: return
        node.refs += Ref(name, pos)
    }

    override fun referenceMember(name: String, pos: Pos, qualifier: String?) {
        val node = current ?: return
        node.memberRefs += Ref(name, pos, qualifier)
    }

    override fun referenceReflection(name: String, pos: Pos) {
        val node = current ?: return
        node.reflectRefs += ReflectRef(name, pos)
    }

    fun buildReport(): ResolutionReport {
        root?.let { resolveScope(it) }
        checkMiConflicts()
        return ResolutionReport(
            moduleName = moduleName,
            symbols = symbols.toList(),
            captures = captures.values.toList(),
            errors = errors.toList(),
            warnings = warnings.toList()
        )
    }

    private fun resolveScope(node: ScopeNode) {
        for (ref in node.refs) {
            if (ref.name == "this") continue
            if (ref.name == "scope") continue
            val resolved = resolveName(node, ref)
            if (!resolved) {
                errors += ResolutionError("unresolved name: ${ref.name}", ref.pos)
            }
        }
        for (ref in node.memberRefs) {
            val resolved = resolveMemberName(node, ref)
            if (!resolved) {
                errors += ResolutionError("unresolved member: ${ref.name}", ref.pos)
            }
        }
        for (ref in node.reflectRefs) {
            val resolved = resolveName(node, Ref(ref.name, ref.pos)) ||
                resolveMemberName(node, Ref(ref.name, ref.pos))
            if (!resolved) {
                errors += ResolutionError("unresolved reflected name: ${ref.name}", ref.pos)
            }
        }
        for (child in node.children) {
            resolveScope(child)
        }
    }

    private fun resolveName(node: ScopeNode, ref: Ref): Boolean {
        if (ref.name.contains('.')) return true
        var scope: ScopeNode? = node
        while (scope != null) {
            val decl = scope.decls[ref.name]
            if (decl != null) {
                if (scope !== node) {
                    recordCapture(node, decl, scope)
                }
                return true
            }
            scope = scope.parent
        }
        return false
    }

    private fun recordCapture(owner: ScopeNode, decl: Decl, targetScope: ScopeNode) {
        if (owner.captures.containsKey(decl.name)) return
        val origin = when (targetScope.kind) {
            ScopeKind.MODULE -> SymbolOrigin.MODULE
            else -> SymbolOrigin.OUTER
        }
        val capture = CaptureInfo(
            name = decl.name,
            origin = origin,
            slotIndex = -1,
            isMutable = decl.isMutable,
            pos = decl.pos
        )
        owner.captures[decl.name] = capture
        captures[decl.name] = capture
    }

    private fun resolveMemberName(node: ScopeNode, ref: Ref): Boolean {
        val classScope = findNearestClassScope(node) ?: return false
        val className = classScope.className ?: return false
        val qualifier = ref.qualifier
        return if (qualifier != null) {
            resolveQualifiedMember(className, qualifier, ref.name, ref.pos)
        } else {
            resolveMemberInClass(className, ref.name, ref.pos)
        }
    }

    private fun findNearestClassScope(node: ScopeNode): ScopeNode? {
        var scope: ScopeNode? = node
        while (scope != null) {
            if (scope.kind == ScopeKind.CLASS) return scope
            scope = scope.parent
        }
        return null
    }

    private fun originForDecl(scope: ScopeNode, kind: SymbolKind): SymbolOrigin {
        return when (kind) {
            SymbolKind.PARAM -> SymbolOrigin.PARAM
            SymbolKind.MEMBER -> SymbolOrigin.MEMBER
            else -> when (scope.kind) {
                ScopeKind.MODULE -> SymbolOrigin.MODULE
                ScopeKind.CLASS -> SymbolOrigin.MEMBER
                else -> SymbolOrigin.LOCAL
            }
        }
    }

    private fun resolveMemberInClass(className: String, member: String, pos: Pos): Boolean {
        val info = classes[className] ?: return false
        val currentMember = info.members[member]
        val definers = findDefiningClasses(className, member)
        if (currentMember != null) {
            if (definers.size > 1 && !currentMember.isOverride) {
                errors += ResolutionError("override required for $member in $className", pos)
            }
            return true
        }
        if (definers.size > 1) {
            errors += ResolutionError("ambiguous member '$member' in $className", pos)
            return true
        }
        return definers.isNotEmpty()
    }

    private fun resolveQualifiedMember(className: String, qualifier: String, member: String, pos: Pos): Boolean {
        val mro = linearize(className)
        val idx = mro.indexOf(qualifier)
        if (idx < 0) return false
        for (name in mro.drop(idx)) {
            val info = classes[name]
            if (info?.members?.containsKey(member) == true) return true
        }
        errors += ResolutionError("member '$member' not found in $qualifier", pos)
        return true
    }

    private fun findDefiningClasses(className: String, member: String): List<String> {
        val parents = linearize(className).drop(1)
        val raw = parents.filter { classes[it]?.members?.containsKey(member) == true }
        if (raw.size <= 1) return raw
        val filtered = raw.toMutableList()
        val iterator = raw.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            for (other in raw) {
                if (candidate == other) continue
                if (linearize(other).contains(candidate)) {
                    filtered.remove(candidate)
                    break
                }
            }
        }
        return filtered
    }

    private fun linearize(className: String, visited: MutableMap<String, List<String>> = mutableMapOf()): List<String> {
        visited[className]?.let { return it }
        val info = classes[className]
        val parents = info?.bases ?: emptyList()
        if (parents.isEmpty()) {
            val single = listOf(className)
            visited[className] = single
            return single
        }
        val parentLinearizations = parents.map { linearize(it, visited).toMutableList() }
        val merge = mutableListOf<MutableList<String>>()
        merge.addAll(parentLinearizations)
        merge.add(parents.toMutableList())
        val merged = c3Merge(merge)
        val result = listOf(className) + merged
        visited[className] = result
        return result
    }

    private fun c3Merge(seqs: MutableList<MutableList<String>>): List<String> {
        val result = mutableListOf<String>()
        while (seqs.isNotEmpty()) {
            seqs.removeAll { it.isEmpty() }
            if (seqs.isEmpty()) break
            var candidate: String? = null
            outer@ for (seq in seqs) {
                val head = seq.first()
                var inTail = false
                for (other in seqs) {
                    if (other === seq || other.size <= 1) continue
                    if (other.drop(1).contains(head)) {
                        inTail = true
                        break
                    }
                }
                if (!inTail) {
                    candidate = head
                    break@outer
                }
            }
            val picked = candidate ?: run {
                errors += ResolutionError("C3 MRO failed for $moduleName", Pos.builtIn)
                return result
            }
            result += picked
            for (seq in seqs) {
                if (seq.isNotEmpty() && seq.first() == picked) {
                    seq.removeAt(0)
                }
            }
        }
        return result
    }

    private fun checkMiConflicts() {
        for (info in classes.values) {
            val baseNames = linearize(info.name).drop(1)
            if (baseNames.isEmpty()) continue
            val baseMemberNames = linkedSetOf<String>()
            for (base in baseNames) {
                classes[base]?.members?.keys?.let { baseMemberNames.addAll(it) }
            }
            for (member in baseMemberNames) {
                val definers = findDefiningClasses(info.name, member)
                if (definers.size <= 1) continue
                val current = info.members[member]
                if (current == null || !current.isOverride) {
                    errors += ResolutionError("ambiguous member '$member' in ${info.name}", info.pos)
                }
            }
        }
    }
}
