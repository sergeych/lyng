/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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

package net.sergeych.lyng.obj

import net.sergeych.lyng.*
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonType

// Simple id generator for class identities (not thread-safe; fine for scripts)
private object ClassIdGen { var c: Long = 1L; fun nextId(): Long = c++ }

val ObjClassType by lazy {
    ObjClass("Class").apply {
        addFnDoc(
            name = "name",
            doc = "Simple name of this class (without package).",
            returns = type("lyng.String"),
            moduleName = "lyng.stdlib"
        ) { thisAs<ObjClass>().classNameObj }

        addFnDoc(
            name = "fields",
            doc = "Declared instance fields of this class and its ancestors (C3 order), without duplicates.",
            returns = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.String"))),
            moduleName = "lyng.stdlib"
        ) {
            val cls = thisAs<ObjClass>()
            val seen = hashSetOf<String>()
            val names = mutableListOf<Obj>()
            for (c in cls.mro) {
                for ((n, rec) in c.members) {
                    if (rec.value !is Statement && seen.add(n)) names += ObjString(n)
                }
            }
            ObjList(names.toMutableList())
        }

        addFnDoc(
            name = "methods",
            doc = "Declared instance methods of this class and its ancestors (C3 order), without duplicates.",
            returns = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.String"))),
            moduleName = "lyng.stdlib"
        ) {
            val cls = thisAs<ObjClass>()
            val seen = hashSetOf<String>()
            val names = mutableListOf<Obj>()
            for (c in cls.mro) {
                for ((n, rec) in c.members) {
                    if (rec.value is Statement && seen.add(n)) names += ObjString(n)
                }
            }
            ObjList(names.toMutableList())
        }

        addFnDoc(
            name = "get",
            doc = "Lookup a member by name in this class (including ancestors) and return it, or null if absent.",
            params = listOf(ParamDoc("name", type("lyng.String"))),
            returns = type("lyng.Any", nullable = true),
            moduleName = "lyng.stdlib"
        ) {
            val cls = thisAs<ObjClass>()
            val name = requiredArg<ObjString>(0).value
            val rec = cls.getInstanceMemberOrNull(name)
            rec?.value ?: ObjNull
        }
    }
}

open class ObjClass(
    val className: String,
    vararg parents: ObjClass,
) : Obj() {

    // Stable identity and simple structural version for PICs
    val classId: Long = ClassIdGen.nextId()
    var layoutVersion: Int = 0

    val classNameObj by lazy { ObjString(className) }

    var constructorMeta: ArgsDeclaration? = null
    var instanceConstructor: Statement? = null

    /**
     * Per-instance initializers collected from class body (for instance fields). These are executed
     * during construction in the instance scope of the object, once per class in the hierarchy.
     */
    val instanceInitializers: MutableList<Statement> = mutableListOf()

    /**
     * the scope for class methods, initialize class vars, etc.
     *
     * Important notice. When create a user class, e.g. from Lyng source, it should
     * be set to a scope by compiler, so it could access local closure, etc. Otherwise,
     * it will be initialized to default scope on first necessity, e.g. when used in
     * external, kotlin classes with [addClassConst] and [addClassFn], etc.
     */
    var classScope: Scope? = null

    /** Direct parents in declaration order (kept deterministic). */
    val directParents: List<ObjClass> = parents.toList()

    /** Optional constructor argument specs for each direct parent (set by compiler for user classes). */
    open val directParentArgs: MutableMap<ObjClass, List<ParsedArgument>> = mutableMapOf()

    /**
     * All ancestors as a Set for fast `isInstanceOf` checks. Order is not guaranteed here and
     * must not be used for resolution
     */
    val allParentsSet: Set<ObjClass> =
        buildSet {
            fun collect(c: ObjClass) {
                if (add(c)) c.directParents.forEach { collect(it) }
            }
            directParents.forEach { collect(it) }
        }

    // --- C3 Method Resolution Order (MRO) ---
    private fun c3Merge(seqs: MutableList<MutableList<ObjClass>>): List<ObjClass> {
        val result = mutableListOf<ObjClass>()
        while (seqs.isNotEmpty()) {
            // remove empty lists
            seqs.removeAll { it.isEmpty() }
            if (seqs.isEmpty()) break
            var candidate: ObjClass? = null
            outer@ for (seq in seqs) {
                val head = seq.first()
                // head must not appear in any other list's tail
                var inTail = false
                for (other in seqs) {
                    if (other === seq || other.size <= 1) continue
                    if (other.drop(1).contains(head)) { inTail = true; break }
                }
                if (!inTail) { candidate = head; break@outer }
            }
            val picked = candidate ?: throw ScriptError(Pos.builtIn, "C3 MRO failed: inconsistent hierarchy for $className")
            result += picked
            // remove picked from heads
            for (seq in seqs) if (seq.isNotEmpty() && seq.first() === picked) seq.removeAt(0)
        }
        return result
    }

    private fun c3Linearize(self: ObjClass, visited: MutableMap<ObjClass, List<ObjClass>>): List<ObjClass> {
        visited[self]?.let { return it }
        // Linearize parents first
        val parentLinearizations = self.directParents.map { c3Linearize(it, visited) }
        // Merge parent MROs with the direct parent list
        val toMerge: MutableList<MutableList<ObjClass>> = mutableListOf()
        parentLinearizations.forEach { toMerge += it.toMutableList() }
        toMerge += self.directParents.toMutableList()
        val merged = c3Merge(toMerge)
        val mro = listOf(self) + merged
        visited[self] = mro
        return mro
    }

    /** Full C3 MRO including this class at index 0. */
    val mro: List<ObjClass> by lazy { c3Linearize(this, mutableMapOf()) }

    /** Parents in C3 order (no self). */
    val mroParents: List<ObjClass> by lazy { mro.drop(1) }

    /** Render current linearization order for diagnostics (C3). */
    fun renderLinearization(includeSelf: Boolean = true): String {
        val list = mutableListOf<String>()
        if (includeSelf) list += className
        mroParents.forEach { list += it.className }
        return list.joinToString(" → ")
    }

    override val objClass: ObjClass by lazy { ObjClassType }

    /**
     * members: fields most often. These are called with [ObjInstance] withs ths [ObjInstance.objClass]
     */
    internal val members = mutableMapOf<String, ObjRecord>()

    override fun toString(): String = className

    override suspend fun compareTo(scope: Scope, other: Obj): Int = if (other === this) 0 else -1

    override suspend fun callOn(scope: Scope): Obj {
        val instance = createInstance(scope)
        initializeInstance(instance, scope.args, runConstructors = true)
        return instance
    }

    /**
     * Create an instance of this class and initialize its [ObjInstance.instanceScope] with
     * methods. Does NOT run initializers or constructors.
     */
    internal fun createInstance(scope: Scope): ObjInstance {
        val instance = ObjInstance(this)
        // Avoid capturing a transient (pooled) call frame as the parent of the instance scope.
        // Bind instance scope to the caller's parent chain directly so name resolution (e.g., stdlib like sqrt)
        // remains stable even when call frames are pooled and reused.
        val stableParent = classScope ?: scope.parent
        instance.instanceScope = Scope(stableParent, scope.args, scope.pos, instance)
        // Expose instance methods (and other callable members) directly in the instance scope for fast lookup
        // This mirrors Obj.autoInstanceScope behavior for ad-hoc scopes and makes fb.method() resolution robust
        // 1) members-defined methods
        for ((k, v) in members) {
            if (v.value is Statement) {
                instance.instanceScope.objects[k] = v
            }
        }
        // 2) class-scope methods registered during class-body execution
        classScope?.objects?.forEach { (k, rec) ->
            if (rec.value is Statement) {
                // if not already present, copy reference for dispatch
                if (!instance.instanceScope.objects.containsKey(k)) {
                    instance.instanceScope.objects[k] = rec
                }
            }
        }
        return instance
    }

    /**
     * Run initializers and optionally constructors for the given [instance].
     * Handles Multiple Inheritance correctly (diamond-safe).
     */
    internal suspend fun initializeInstance(
        instance: ObjInstance,
        args: Arguments?,
        runConstructors: Boolean
    ) {
        val visited = hashSetOf<ObjClass>()
        initClassInternal(instance, visited, this, args, isRoot = true, runConstructors = runConstructors)
    }

    private suspend fun initClassInternal(
        instance: ObjInstance,
        visited: MutableSet<ObjClass>,
        c: ObjClass,
        argsForThis: Arguments?,
        @Suppress("UNUSED_PARAMETER") isRoot: Boolean = false,
        runConstructors: Boolean = true
    ) {
        if (!visited.add(c)) return

        // Bind constructor parameters (both mangled and unmangled)
        // These are needed for:
        // 1) base constructor argument evaluation (if called from a derived class)
        // 2) this class's field initializers and `init` blocks
        // 3) this class's constructor body
        // 4) `compareTo` and other structural operations
        c.constructorMeta?.let { meta ->
            val argsHere = argsForThis ?: Arguments.EMPTY
            // Assign constructor params into instance scope (unmangled)
            meta.assignToContext(instance.instanceScope, argsHere)
            // Also expose them under MI-mangled storage keys `${Class}::name` so qualified views can access them
            // and so that base-class casts like `(obj as Base).field` work.
            for (p in meta.params) {
                val rec = instance.instanceScope.objects[p.name]
                if (rec != null) {
                    val mangled = "${c.className}::${p.name}"
                    // Always point the mangled name to the current record to keep writes consistent
                    // across re-bindings
                    instance.instanceScope.objects[mangled] = rec
                }
            }
        }

        // Initialize direct parents first, in order
        for (p in c.directParents) {
            val raw = c.directParentArgs[p]?.toArguments(instance.instanceScope, false)
            val limited = if (raw != null) {
                val need = p.constructorMeta?.params?.size ?: 0
                if (need == 0) Arguments.EMPTY else Arguments(raw.list.take(need), tailBlockMode = false)
            } else Arguments.EMPTY
            initClassInternal(instance, visited, p, limited, false, runConstructors)
        }

        // Re-bind this class's parameters right before running its initializers and constructor.
        // This ensures that unmangled names in the instance scope correctly refer to THIS class's
        // parameters even if they were shadowed/overwritten by parent class initialization.
        c.constructorMeta?.let { meta ->
            val argsHere = argsForThis ?: Arguments.EMPTY
            meta.assignToContext(instance.instanceScope, argsHere)
            // Re-sync mangled names to point to the fresh records to keep them consistent
            for (p in meta.params) {
                val rec = instance.instanceScope.objects[p.name]
                if (rec != null) {
                    val mangled = "${c.className}::${p.name}"
                    instance.instanceScope.objects[mangled] = rec
                }
            }
        }

        // Execute per-instance initializers collected from class body for this class
        if (c.instanceInitializers.isNotEmpty()) {
            val savedCtx = instance.instanceScope.currentClassCtx
            instance.instanceScope.currentClassCtx = c
            try {
                for (initStmt in c.instanceInitializers) {
                    initStmt.execute(instance.instanceScope)
                }
            } finally {
                instance.instanceScope.currentClassCtx = savedCtx
            }
        }
        // Then run this class' constructor, if any
        if (runConstructors) {
            c.instanceConstructor?.let { ctor ->
                val execScope =
                    instance.instanceScope.createChildScope(args = argsForThis ?: Arguments.EMPTY, newThisObj = instance)
                ctor.execute(execScope)
            }
        }
    }

    suspend fun callWithArgs(scope: Scope, vararg plainArgs: Obj): Obj {
        return callOn(scope.createChildScope(Arguments(*plainArgs)))
    }


    fun createField(
        name: String,
        initialValue: Obj,
        isMutable: Boolean = false,
        visibility: Visibility = Visibility.Public,
        pos: Pos = Pos.builtIn
    ) {
        // Allow overriding ancestors: only prevent redefinition if THIS class already defines an immutable member
        val existingInSelf = members[name]
        if (existingInSelf != null && existingInSelf.isMutable == false)
            throw ScriptError(pos, "$name is already defined in $objClass")
        // Install/override in this class
        members[name] = ObjRecord(initialValue, isMutable, visibility, declaringClass = this)
        // Structural change: bump layout version for PIC invalidation
        layoutVersion += 1
    }

    private fun initClassScope(): Scope {
        if (classScope == null) classScope = Scope()
        return classScope!!
    }

    fun createClassField(
        name: String,
        initialValue: Obj,
        isMutable: Boolean = false,
        visibility: Visibility = Visibility.Public,
        pos: Pos = Pos.builtIn
    ) {
        initClassScope()
        val existing = classScope!!.objects[name]
        if (existing != null)
            throw ScriptError(pos, "$name is already defined in $objClass or one of its supertypes")
        classScope!!.addItem(name, isMutable, initialValue, visibility)
        // Structural change: bump layout version for PIC invalidation
        layoutVersion += 1
    }

    fun addFn(
        name: String,
        isOpen: Boolean = false,
        visibility: Visibility = Visibility.Public,
        code: suspend Scope.() -> Obj
    ) {
        val stmt = statement { code() }
        createField(name, stmt, isOpen, visibility)
    }

    fun addConst(name: String, value: Obj) = createField(name, value, isMutable = false)
    fun addClassConst(name: String, value: Obj) = createClassField(name, value)
    fun addClassFn(name: String, isOpen: Boolean = false, code: suspend Scope.() -> Obj) {
        createClassField(name, statement { code() }, isOpen)
    }


    /**
     * Get instance member traversing the hierarchy if needed. Its meaning is different for different objects.
     */
    fun getInstanceMemberOrNull(name: String): ObjRecord? {
        // Unified traversal in strict C3 order: self, then each ancestor, checking members before classScope
        for (cls in mro) {
            cls.members[name]?.let { return it }
            cls.classScope?.objects?.get(name)?.let { return it }
        }
        // Finally, allow root object fallback (rare; mostly built-ins like toString)
        return rootObjectType.members[name]
    }

    /** Find the declaring class where a member with [name] is defined, starting from this class along MRO. */
    fun findDeclaringClassOf(name: String): ObjClass? {
        if (members.containsKey(name)) return this
        for (anc in mroParents) {
            if (anc.members.containsKey(name)) return anc
        }
        return if (rootObjectType.members.containsKey(name)) rootObjectType else null
    }

    fun getInstanceMember(atPos: Pos, name: String): ObjRecord =
        getInstanceMemberOrNull(name)
            ?: throw ScriptError(atPos, "symbol doesn't exist: $name")

    /**
     * Resolve member starting from a specific ancestor class [start], not from this class.
     * Searches [start] first, then traverses its linearized parents.
     */
    fun getInstanceMemberFromAncestor(start: ObjClass, name: String): ObjRecord? {
        val order = mro
        val idx = order.indexOf(start)
        if (idx < 0) return null
        for (i in idx until order.size) {
            val cls = order[i]
            // Prefer true instance members on the class
            cls.members[name]?.let { return it }
            // Fallback to class-scope function registered during class-body execution
            cls.classScope?.objects?.get(name)?.let { return it }
        }
        return rootObjectType.members[name]
    }

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        classScope?.objects?.get(name)?.let {
            if (it.visibility.isPublic) return it
        }
        return super.readField(scope, name)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        initClassScope().objects[name]?.let {
            if (it.isMutable) it.value = newValue
            else scope.raiseIllegalAssignment("can't assign $name is not mutable")
        }
            ?: super.writeField(scope, name, newValue)
    }

    override suspend fun invokeInstanceMethod(
        scope: Scope, name: String, args: Arguments,
        onNotFoundResult: (suspend () -> Obj?)?
    ): Obj {
        return classScope?.objects?.get(name)?.value?.invoke(scope, this, args)
            ?: super.invokeInstanceMethod(scope, name, args, onNotFoundResult)
    }

    open suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
        scope.raiseNotImplemented()

}


