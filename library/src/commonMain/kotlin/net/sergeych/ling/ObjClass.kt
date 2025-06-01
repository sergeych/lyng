package net.sergeych.ling

val ObjClassType by lazy { ObjClass("Class") }

class ObjClass(
    val className: String,
    vararg val parents: ObjClass,
) : Obj() {

    val allParentsSet: Set<ObjClass> = parents.flatMap {
        listOf(it) + it.allParentsSet
    }.toSet()

    override val objClass: ObjClass by lazy { ObjClassType }

    // members: fields most often
    private val members = mutableMapOf<String, WithAccess<Obj>>()

    override fun toString(): String = className

    override suspend fun compareTo(context: Context, other: Obj): Int = if (other === this) 0 else -1

//    private var initInstanceHandler: (suspend (Context, List<Obj>) -> Obj)? = null

//    suspend fun newInstance(context: Context, vararg args: Obj): Obj =
//        initInstanceHandler?.invoke(context, args.toList())
//            ?: context.raiseError("No initInstance handler for $this")
//
//    fun buildInstance(f: suspend Context.(List<Obj>) -> Obj) {
//        if (initInstanceHandler != null) throw IllegalStateException("initInstance already set")
//        initInstanceHandler = f
//    }
//
//    fun addParent(context: Context, parent: Obj) {
//        val self = context.thisObj
//        self.parentInstances.add(parent)
//    }
//
    fun defaultInstance(): Obj = object : Obj() {
        override val objClass: ObjClass = this@ObjClass
    }

    fun createField(name: String, initialValue: Obj, isMutable: Boolean = false, pos: Pos = Pos.builtIn) {
        if (name in members || allParentsSet.any { name in it.members } == true)
            throw ScriptError(pos, "$name is already defined in $objClass or one of its supertypes")
        members[name] = WithAccess(initialValue, isMutable)
    }

    fun addFn(name: String, isOpen: Boolean = false, code: suspend Context.() -> Obj) {
        createField(name, statement { code() }, isOpen)
    }

    fun addConst(name: String, value: Obj) = createField(name, value, isMutable = false)


    /**
     * Get instance member traversing the hierarchy if needed. Its meaning is different for different objects.
     */
    fun getInstanceMemberOrNull(name: String): WithAccess<Obj>? {
        members[name]?.let { return it }
        allParentsSet.forEach { parent -> parent.getInstanceMemberOrNull(name)?.let { return it } }
        return null
    }

    fun getInstanceMember(atPos: Pos, name: String): WithAccess<Obj> =
        getInstanceMemberOrNull(name)
            ?: throw ScriptError(atPos, "symbol doesn't exist: $name")
}

/**
 * Abstract class that must provide `iterator` method that returns [ObjIterator] instance.
 */
val ObjIterable by lazy { ObjClass("Iterable").apply {

    addFn("toList") {
        val result = mutableListOf<Obj>()
        val iterator = thisObj.invokeInstanceMethod(this, "iterator")

        while( iterator.invokeInstanceMethod(this, "hasNext").toBool() )
            result += iterator.invokeInstanceMethod(this, "next")


//        val next = iterator.getMemberOrNull("next")!!
//        val hasNext = iterator.getMemberOrNull("hasNext")!!
//        while( hasNext.invoke(this, iterator).toBool() )
//            result += next.invoke(this, iterator)
        ObjList(result)
    }

} }

/**
 * Collection is an iterator with `size`]
 */
val ObjCollection by lazy {
    val i: ObjClass = ObjIterable
    ObjClass("Collection", i)
}

val ObjIterator by lazy { ObjClass("Iterator") }

class ObjArrayIterator(val array: Obj) : Obj() {

    override val objClass: ObjClass by lazy { type }

    private var nextIndex = 0
    private var lastIndex = 0

    suspend fun init(context: Context) {
        nextIndex = 0
        lastIndex = array.invokeInstanceMethod(context, "size").toInt()
        ObjVoid
    }

    companion object {
        val type by lazy {
            ObjClass("ArrayIterator", ObjIterator).apply {
                addFn("next") {
                    val self = thisAs<ObjArrayIterator>()
                    if (self.nextIndex < self.lastIndex) {
                        self.array.invokeInstanceMethod(this, "getAt", (self.nextIndex++).toObj())
                    } else raiseError(ObjIterationFinishedError(this))
                }
                addFn("hasNext") {
                    val self = thisAs<ObjArrayIterator>()
                    if (self.nextIndex  < self.lastIndex) ObjTrue else ObjFalse
                }
            }
        }
    }
}


val ObjArray by lazy {

    /**
     * Array abstract class is a [ObjCollection] with `getAt` method.
     */
    ObjClass("Array", ObjCollection).apply {
        // we can create iterators using size/getat:

        addFn("iterator") {
            ObjArrayIterator(thisObj).also { it.init(this) }
        }
        addFn("isample") { "ok".toObj()}
    }
}