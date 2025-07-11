package net.sergeych.lyng.obj

import net.sergeych.lyng.*
import net.sergeych.lynon.LynonDecoder

val ObjClassType by lazy { ObjClass("Class") }

open class ObjClass(
    val className: String,
    vararg parents: ObjClass,
) : Obj() {

    var instanceConstructor: Statement? = null

    val allParentsSet: Set<ObjClass> =
        parents.flatMap {
            listOf(it) + it.allParentsSet
        }.toMutableSet()

    override val objClass: ObjClass by lazy { ObjClassType }

    // members: fields most often
    private val members = mutableMapOf<String, ObjRecord>()
    private val classMembers = mutableMapOf<String, ObjRecord>()

    override fun toString(): String = className

    override suspend fun compareTo(scope: Scope, other: Obj): Int = if (other === this) 0 else -1

    override suspend fun callOn(scope: Scope): Obj {
        val instance = ObjInstance(this)
        instance.instanceScope = scope.copy(newThisObj = instance,args = scope.args)
        if (instanceConstructor != null) {
            instanceConstructor!!.execute(instance.instanceScope)
        }
        return instance
    }

    fun createField(
        name: String,
        initialValue: Obj,
        isMutable: Boolean = false,
        visibility: Visibility = Visibility.Public,
        pos: Pos = Pos.builtIn
    ) {
        val existing = members[name] ?: allParentsSet.firstNotNullOfOrNull { it.members[name] }
        if( existing?.isMutable == false)
            throw ScriptError(pos, "$name is already defined in $objClass or one of its supertypes")
        members[name] = ObjRecord(initialValue, isMutable, visibility)
    }

    fun createClassField(
        name: String,
        initialValue: Obj,
        isMutable: Boolean = false,
        visibility: Visibility = Visibility.Public,
        pos: Pos = Pos.builtIn
    ) {
        val existing = classMembers[name]
        if( existing != null)
            throw ScriptError(pos, "$name is already defined in $objClass or one of its supertypes")
        classMembers[name] = ObjRecord(initialValue, isMutable, visibility)
    }

    fun addFn(name: String, isOpen: Boolean = false, code: suspend Scope.() -> Obj) {
        createField(name, statement { code() }, isOpen)
    }

    fun addConst(name: String, value: Obj) = createField(name, value, isMutable = false)
    fun addClassConst(name: String, value: Obj) = createClassField(name, value)


    /**
     * Get instance member traversing the hierarchy if needed. Its meaning is different for different objects.
     */
    fun getInstanceMemberOrNull(name: String): ObjRecord? {
        members[name]?.let { return it }
        allParentsSet.forEach { parent -> parent.getInstanceMemberOrNull(name)?.let { return it } }
        return rootObjectType.members[name]
    }

    fun getInstanceMember(atPos: Pos, name: String): ObjRecord =
        getInstanceMemberOrNull(name)
            ?: throw ScriptError(atPos, "symbol doesn't exist: $name")

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        classMembers[name]?.let {
            return it
        }
        return super.readField(scope, name)
    }

    open fun deserialize(scope: Scope, decoder: LynonDecoder): Obj = scope.raiseNotImplemented()
}


