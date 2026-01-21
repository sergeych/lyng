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

package net.sergeych.lyng.obj

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.sergeych.lyng.*
import net.sergeych.lyng.miniast.*
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

// Simple id generator for class identities (not thread-safe; fine for scripts)
private object ClassIdGen { var c: Long = 1L; fun nextId(): Long = c++ }

val ObjClassType by lazy {
    object : ObjClass("Class") {
        override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
            val name = decoder.decodeObject(scope, ObjString.type, null) as ObjString
            return scope.resolveQualifiedIdentifier(name.value)
        }
    }.apply {
        addPropertyDoc(
            name = "className",
            doc = "Full name of this class including package if available.",
            type = type("lyng.String"),
            moduleName = "lyng.stdlib",
            getter = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj = (scp.thisObj as ObjClass).classNameObj
            }
        )
        addPropertyDoc(
            name = "name",
            doc = "Simple name of this class (without package).",
            type = type("lyng.String"),
            moduleName = "lyng.stdlib",
            getter = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj = (scp.thisObj as ObjClass).classNameObj
            }
        )

        addPropertyDoc(
            name = "fields",
            doc = "Declared instance fields of this class and its ancestors (C3 order), without duplicates.",
            type = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.String"))),
            moduleName = "lyng.stdlib",
            getter = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val cls = scp.thisObj as ObjClass
                    val seen = hashSetOf<String>()
                    val names = mutableListOf<Obj>()
                    for (c in cls.mro) {
                        for ((n, rec) in c.members) {
                            if (rec.value !is Statement && seen.add(n)) names += ObjString(n)
                        }
                    }
                    return ObjList(names.toMutableList())
                }
            }
        )

        addPropertyDoc(
            name = "methods",
            doc = "Declared instance methods of this class and its ancestors (C3 order), without duplicates.",
            type = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.String"))),
            moduleName = "lyng.stdlib",
            getter = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val cls = scp.thisObj as ObjClass
                    val seen = hashSetOf<String>()
                    val names = mutableListOf<Obj>()
                    for (c in cls.mro) {
                        for ((n, rec) in c.members) {
                            if (rec.value is Statement && seen.add(n)) names += ObjString(n)
                        }
                    }
                    return ObjList(names.toMutableList())
                }
            }
        )

        addFnDoc(
            name = "get",
            doc = "Lookup a member by name in this class (including ancestors) and return it, or null if absent.",
            params = listOf(ParamDoc("name", type("lyng.String"))),
            returns = type("lyng.Any", nullable = true),
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val cls = scp.thisAs<ObjClass>()
                    val name = scp.requiredArg<ObjString>(0).value
                    val rec = cls.getInstanceMemberOrNull(name)
                    return rec?.value ?: ObjNull
                }
            }
        )
    }
}

open class ObjClass(
    val className: String,
    vararg parents: ObjClass,
) : Obj() {

    var isAnonymous: Boolean = false

    var isAbstract: Boolean = false

    // Stable identity and simple structural version for PICs
    val classId: Long = ClassIdGen.nextId()
    var layoutVersion: Int = 0

    private val mangledNameCache = mutableMapOf<String, String>()
    fun mangledName(name: String): String = mangledNameCache.getOrPut(name) { "$className::$name" }

    /**
     * Map of public member names to their effective storage keys in instanceScope.objects.
     * This is pre-calculated to avoid MRO traversal and string concatenation during common access.
     */
    val publicMemberResolution: Map<String, String> by lazy {
        val res = mutableMapOf<String, String>()
        // Traverse MRO in REVERSED order so that child classes override parent classes in the map.
        for (cls in mro.reversed()) {
            if (cls.className == "Obj") continue
            for ((name, rec) in cls.members) {
                if (rec.visibility == Visibility.Public) {
                    val key = if (rec.type == ObjRecord.Type.Field || rec.type == ObjRecord.Type.Delegated) cls.mangledName(name) else name
                    res[name] = key
                }
            }
            cls.classScope?.objects?.forEach { (name, rec) ->
                if (rec.visibility == Visibility.Public && (rec.value is Statement || rec.type == ObjRecord.Type.Delegated)) {
                    val key = if (rec.type == ObjRecord.Type.Delegated) cls.mangledName(name) else name
                    res[name] = key
                }
            }
        }
        res
    }

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

    /**
     * Names of additional interfaces this class implements, but they are not (yet) available
     * as [ObjClass] instances. This is used for "implementing existing interfaces" feature.
     */
    val implementingNames = mutableSetOf<String>()

    /**
     * Combined set of [implementingNames] from this class and all its ancestors.
     */
    val allImplementingNames: Set<String> by lazy {
        buildSet {
            addAll(implementingNames)
            for (p in allParentsSet) {
                addAll(p.implementingNames)
            }
        }
    }

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
    val mro: List<ObjClass> by lazy {
        val base = c3Linearize(this, mutableMapOf())
        if (this.className == "Obj" || base.any { it.className == "Obj" }) base
        else {
            val root = Obj.rootObjectType
            base + root
        }
    }

    /** Parents in C3 order (no self). */
    val mroParents: List<ObjClass> by lazy { mro.drop(1) }

    /** Render current linearization order for diagnostics (C3). */
    fun renderLinearization(includeSelf: Boolean = true): String {
        val list = mutableListOf<String>()
        if (includeSelf) list += className
        mroParents.forEach { list += it.className }
        return list.joinToString(", ")
    }

    override val objClass: ObjClass by lazy { ObjClassType }

    /**
     * members: fields most often. These are called with [ObjInstance] withs ths [ObjInstance.objClass]
     */
    internal val members = mutableMapOf<String, ObjRecord>()

    override fun toString(): String = className

    override suspend fun compareTo(scope: Scope, other: Obj): Int = if (other === this) 0 else -1

    override suspend fun callOn(scope: Scope): Obj {
        if (isAbstract) scope.raiseError("can't instantiate abstract class $className")
        val instance = createInstance(scope)
        initializeInstance(instance, scope.args, runConstructors = true)
        return instance
    }

    /** Pre-calculated template for instanceScope.objects. */
    private val instanceObjectsTemplate: Map<String, ObjRecord> by lazy {
        val res = mutableMapOf<String, ObjRecord>()
        for (cls in mro) {
            // 1) members-defined methods and fields
            for ((k, v) in cls.members) {
                if (!v.isAbstract && (v.value is Statement || v.type == ObjRecord.Type.Delegated || v.type == ObjRecord.Type.Field)) {
                    val key = if (v.visibility == Visibility.Private || v.type == ObjRecord.Type.Field || v.type == ObjRecord.Type.Delegated) cls.mangledName(k) else k
                    if (!res.containsKey(key)) {
                        res[key] = v
                    }
                }
            }
            // 2) class-scope members registered during class-body execution
            cls.classScope?.objects?.forEach { (k, rec) ->
                // ONLY copy methods and delegated members from class scope to instance scope.
                // Fields in class scope are static fields and must NOT be per-instance.
                if (!rec.isAbstract && (rec.value is Statement || rec.type == ObjRecord.Type.Delegated)) {
                    val key = if (rec.visibility == Visibility.Private || rec.type == ObjRecord.Type.Delegated) cls.mangledName(k) else k
                    // if not already present, copy reference for dispatch
                    if (!res.containsKey(key)) {
                        res[key] = rec
                    }
                }
            }
        }
        res
    }

    private val templateMethods: Map<String, ObjRecord> by lazy {
        instanceObjectsTemplate.filter { it.value.type == ObjRecord.Type.Fun }
    }

    private val templateOthers: List<Pair<String, ObjRecord>> by lazy {
        instanceObjectsTemplate.filter { it.value.type != ObjRecord.Type.Fun }.toList()
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
        instance.instanceScope.currentClassCtx = null
        // Expose instance methods (and other callable members) directly in the instance scope for fast lookup
        // This mirrors Obj.autoInstanceScope behavior for ad-hoc scopes and makes fb.method() resolution robust
        
        instance.instanceScope.objects.putAll(templateMethods)
        for (p in templateOthers) {
            instance.instanceScope.objects[p.first] = p.second.copy()
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
            val savedCtx = instance.instanceScope.currentClassCtx
            instance.instanceScope.currentClassCtx = c
            try {
                meta.assignToContext(instance.instanceScope, argsHere, declaringClass = c)
            } finally {
                instance.instanceScope.currentClassCtx = savedCtx
            }
            // Also expose them under MI-mangled storage keys `${Class}::name` so qualified views can access them
            // and so that base-class casts like `(obj as Base).field` work.
            for (p in meta.params) {
                val rec = instance.instanceScope.objects[p.name]
                if (rec != null) {
                    val mangled = c.mangledName(p.name)
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
            val savedCtx = instance.instanceScope.currentClassCtx
            instance.instanceScope.currentClassCtx = c
            try {
                meta.assignToContext(instance.instanceScope, argsHere, declaringClass = c)
            } finally {
                instance.instanceScope.currentClassCtx = savedCtx
            }
            // Re-sync mangled names to point to the fresh records to keep them consistent
            for (p in meta.params) {
                val rec = instance.instanceScope.objects[p.name]
                if (rec != null) {
                    val mangled = c.mangledName(p.name)
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
        writeVisibility: Visibility? = null,
        pos: Pos = Pos.builtIn,
        declaringClass: ObjClass? = this,
        isAbstract: Boolean = false,
        isClosed: Boolean = false,
        isOverride: Boolean = false,
        isTransient: Boolean = false,
        type: ObjRecord.Type = ObjRecord.Type.Field,
    ): ObjRecord {
        // Validation of override rules: only for non-system declarations
        if (pos != Pos.builtIn) {
            // Only consider TRUE instance members from ancestors for overrides
            val existing = getInstanceMemberOrNull(name, includeAbstract = true, includeStatic = false)
            var actualOverride = false
            if (existing != null && existing.declaringClass != this) {
                // If the existing member is private in the ancestor, it's not visible for overriding.
                // It should be treated as a new member in this class.
                if (!existing.visibility.isPublic && !canAccessMember(existing.visibility, existing.declaringClass, this, name)) {
                    // It's effectively not there for us, so actualOverride remains false
                } else {
                    actualOverride = true
                    // It's an override (implicit or explicit)
                    if (existing.isClosed)
                        throw ScriptError(pos, "can't override closed member $name from ${existing.declaringClass?.className}")
                    
                    if (!isOverride)
                        throw ScriptError(pos, "member $name overrides parent member but 'override' keyword is missing")

                    if (visibility.ordinal > existing.visibility.ordinal)
                        throw ScriptError(pos, "can't narrow visibility of $name from ${existing.visibility} to $visibility")
                }
            }
            
            if (isOverride && !actualOverride) {
                throw ScriptError(pos, "member $name is marked 'override' but does not override anything")
            }
        }

        // Allow overriding ancestors: only prevent redefinition if THIS class already defines an immutable member
        val existingInSelf = members[name]
        if (existingInSelf != null && existingInSelf.isMutable == false)
            throw ScriptError(pos, "$name is already defined in $objClass")
        
        // Install/override in this class
        val rec = ObjRecord(
            initialValue, isMutable, visibility, writeVisibility, 
            declaringClass = declaringClass,
            isAbstract = isAbstract,
            isClosed = isClosed,
            isOverride = isOverride,
            isTransient = isTransient,
            type = type
        )
        members[name] = rec
        // Structural change: bump layout version for PIC invalidation
        layoutVersion += 1
        return rec
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
        writeVisibility: Visibility? = null,
        pos: Pos = Pos.builtIn,
        isTransient: Boolean = false,
        type: ObjRecord.Type = ObjRecord.Type.Field
    ): ObjRecord {
        initClassScope()
        val existing = classScope!!.objects[name]
        if (existing != null)
            throw ScriptError(pos, "$name is already defined in $objClass or one of its supertypes")
        val rec = classScope!!.addItem(name, isMutable, initialValue, visibility, writeVisibility, recordType = type, isTransient = isTransient)
        // Structural change: bump layout version for PIC invalidation
        layoutVersion += 1
        return rec
    }

    fun addFn(
        name: String,
        isMutable: Boolean = false,
        visibility: Visibility = Visibility.Public,
        writeVisibility: Visibility? = null,
        declaringClass: ObjClass? = this,
        isAbstract: Boolean = false,
        isClosed: Boolean = false,
        isOverride: Boolean = false,
        pos: Pos = Pos.builtIn,
        code: ScopeCallable? = null
    ) {
        val stmt = code?.let { statement(pos, f = it) } ?: ObjNull
        createField(
            name, stmt, isMutable, visibility, writeVisibility, pos, declaringClass,
            isAbstract = isAbstract, isClosed = isClosed, isOverride = isOverride,
            type = ObjRecord.Type.Fun
        )
    }

    fun addConst(name: String, value: Obj) = createField(name, value, isMutable = false)

    fun addProperty(
        name: String,
        getter: ScopeCallable? = null,
        setter: ScopeCallable? = null,
        visibility: Visibility = Visibility.Public,
        writeVisibility: Visibility? = null,
        declaringClass: ObjClass? = this,
        isAbstract: Boolean = false,
        isClosed: Boolean = false,
        isOverride: Boolean = false,
        pos: Pos = Pos.builtIn,
        prop: ObjProperty? = null
    ) {
        val g = getter?.let { statement(pos, f = it) }
        val s = setter?.let { statement(pos, f = it) }
        val finalProp = prop ?: if (isAbstract) ObjNull else ObjProperty(name, g, s)
        createField(
            name, finalProp, false, visibility, writeVisibility, pos, declaringClass,
            isAbstract = isAbstract, isClosed = isClosed, isOverride = isOverride,
            type = ObjRecord.Type.Property
        )
    }

    fun addClassConst(name: String, value: Obj) = createClassField(name, value)
    fun addClassFn(name: String, isOpen: Boolean = false, code: ScopeCallable) {
        createClassField(name, statement(f = code), isOpen, type = ObjRecord.Type.Fun)
    }


    /**
     * Get instance member traversing the hierarchy if needed. Its meaning is different for different objects.
     */
    fun getInstanceMemberOrNull(name: String, includeAbstract: Boolean = false, includeStatic: Boolean = true): ObjRecord? {
        // Unified traversal in strict C3 order: self, then each ancestor, checking members before classScope
        for (cls in mro) {
            cls.members[name]?.let { 
                if (includeAbstract || !it.isAbstract) return it 
            }
            if (includeStatic) {
                cls.classScope?.objects?.get(name)?.let { 
                    if (includeAbstract || !it.isAbstract) return it 
                }
            }
        }
        // Finally, allow root object fallback (rare; mostly built-ins like toString)
        val rootRec = rootObjectType.members[name]
        return if (rootRec != null && (includeAbstract || !rootRec.isAbstract)) rootRec else null
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

    fun findFirstConcreteMember(name: String): ObjRecord? {
        for (cls in mro) {
            cls.members[name]?.let {
                if (!it.isAbstract) return it
            }
        }
        return null
    }

    fun checkAbstractSatisfaction(pos: Pos) {
        if (isAbstract) return

        val missing = mutableSetOf<String>()
        for (cls in mroParents) {
            for ((name, rec) in cls.members) {
                if (rec.isAbstract) {
                    val current = findFirstConcreteMember(name)
                    if (current == null) {
                        missing.add(name)
                    }
                }
            }
        }

        if (missing.isNotEmpty()) {
            throw ScriptError(
                pos,
                "class $className is not abstract and does not implement abstract members: ${missing.joinToString(", ")}"
            )
        }
    }

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
            if (it.visibility.isPublic) return resolveRecord(scope, it, name, this)
        }
        return super.readField(scope, name)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        initClassScope().objects[name]?.let { rec ->
            if (rec.type == ObjRecord.Type.Delegated) {
                val del = rec.delegate ?: scope.raiseError("Internal error: delegated property $name has no delegate")
                del.invokeInstanceMethod(scope, "setValue", Arguments(this, ObjString(name), newValue))
                return
            }
            if (rec.isMutable) rec.value = newValue
            else scope.raiseIllegalAssignment("can't assign $name is not mutable")
            return
        }
            ?: super.writeField(scope, name, newValue)
    }

    override suspend fun invokeInstanceMethod(
        scope: Scope, name: String, args: Arguments,
        onNotFoundResult: OnNotFound?
    ): Obj {
        getInstanceMemberOrNull(name)?.let { rec ->
            val decl = rec.declaringClass ?: findDeclaringClassOf(name) ?: this
            if (rec.type == ObjRecord.Type.Delegated) {
                val del = rec.delegate ?: scope.raiseError("Internal error: delegated member $name has no delegate")
                val allArgs = (listOf(this, ObjString(name)) + args.list).toTypedArray()
                return del.invokeInstanceMethod(scope, "invokeCallable", Arguments(*allArgs), onNotFoundResult = {
                    // Fallback: property delegation
                    val propVal = del.invokeInstanceMethod(scope, "getValue", Arguments(this, ObjString(name)))
                    propVal.invokeCallable(scope, this, args, decl)
                })
            }
            if (rec.type == ObjRecord.Type.Fun) {
                return rec.value.invokeCallable(scope, this, args, decl)
            } else {
                // Resolved field or property value
                val resolved = readField(scope, name)
                return resolved.value.invokeCallable(scope, this, args, decl)
            }
        }
        return super.invokeInstanceMethod(scope, name, args, onNotFoundResult)
    }

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        if (isAnonymous) scope.raiseError("Cannot serialize anonymous class")
        encoder.encodeObject(scope, classNameObj, ObjString.type.lynonType())
    }

    override suspend fun toJson(scope: Scope): JsonElement {
        val result = mutableMapOf<String, JsonElement>()
        result["__class_name"] = classNameObj.toJson(scope)
        classScope?.objects?.forEach { (name, rec) ->
            if (rec.type.serializable && rec.visibility.isPublic && !rec.isTransient) {
                result[name] = rec.value.toJson(scope)
            }
        }
        return JsonObject(result)
    }

    open suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
        scope.raiseNotImplemented()

}


