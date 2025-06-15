package net.sergeych.lyng

val ObjClassType by lazy { ObjClass("Class") }

open class ObjClass(
    val className: String,
    vararg val parents: ObjClass,
) : Obj() {

    var instanceConstructor: Statement? = null

    val allParentsSet: Set<ObjClass> = parents.flatMap {
        listOf(it) + it.allParentsSet
    }.toSet()

    override val objClass: ObjClass by lazy { ObjClassType }

    // members: fields most often
    private val members = mutableMapOf<String, ObjRecord>()

    override fun toString(): String = className

    override suspend fun compareTo(context: Context, other: Obj): Int = if (other === this) 0 else -1

    override suspend fun callOn(context: Context): Obj {
        val instance = ObjInstance(this)
        instance.instanceContext = context.copy(newThisObj = instance,args = context.args)
        if (instanceConstructor != null) {
            instanceConstructor!!.execute(instance.instanceContext)
        }
        return instance
    }

    fun defaultInstance(): Obj = object : Obj() {
        override val objClass: ObjClass = this@ObjClass
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

    fun addFn(name: String, isOpen: Boolean = false, code: suspend Context.() -> Obj) {
        createField(name, statement { code() }, isOpen)
    }

    fun addConst(name: String, value: Obj) = createField(name, value, isMutable = false)


    /**
     * Get instance member traversing the hierarchy if needed. Its meaning is different for different objects.
     */
    fun getInstanceMemberOrNull(name: String): ObjRecord? {
        members[name]?.let { return it }
        allParentsSet.forEach { parent -> parent.getInstanceMemberOrNull(name)?.let { return it } }
        return null
    }

    fun getInstanceMember(atPos: Pos, name: String): ObjRecord =
        getInstanceMemberOrNull(name)
            ?: throw ScriptError(atPos, "symbol doesn't exist: $name")
}


