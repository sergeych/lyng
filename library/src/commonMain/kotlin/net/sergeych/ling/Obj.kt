package net.sergeych.ling

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

//typealias InstanceMethod = (Context, Obj) -> Obj

data class WithAccess<T>(var value: T, val isMutable: Boolean)

data class Accessor(
    val getter: suspend (Context) -> WithAccess<Obj>,
    val setterOrNull: (suspend (Context, Obj) -> Unit)?
) {
    constructor(getter: suspend (Context) -> WithAccess<Obj>) : this(getter, null)

    fun setter(pos: Pos) = setterOrNull ?: throw ScriptError(pos, "can't assign value")
}

sealed class Obj {
    var isFrozen: Boolean = false

    private val monitor = Mutex()

    // members: fields most often
    private val members = mutableMapOf<String, WithAccess<Obj>>()

    //    private val memberMutex = Mutex()
    private val parentInstances = listOf<Obj>()

    open fun inspect(): String = toString()

    /**
     * Some objects are by-value, historically [ObjInt] and [ObjReal] are usually treated as such.
     * When initializing a var with it, by value objects must be copied. By-reference ones aren't.
     *
     * Almost all objects are by-reference.
     */
    open fun byValueCopy(): Obj = this

    /**
     * Get instance member traversing the hierarchy if needed. Its meaning is different for different objects.
     */
    fun getInstanceMemberOrNull(name: String): WithAccess<Obj>? {
        members[name]?.let { return it }
        parentInstances.forEach { parent -> parent.getInstanceMemberOrNull(name)?.let { return it } }
        return null
    }

    fun getInstanceMember(atPos: Pos, name: String): WithAccess<Obj> =
        getInstanceMemberOrNull(name)
            ?: throw ScriptError(atPos, "symbol doesn't exist: $name")

    suspend fun callInstanceMethod(context: Context,
                                   name: String,
                                   args: Arguments = Arguments.EMPTY
    ): Obj =
        // note that getInstanceMember traverses the hierarchy
        objClass.getInstanceMember(context.pos, name).value.invoke(context, this, args)

    // methods that to override

    open suspend fun compareTo(context: Context, other: Obj): Int {
        context.raiseNotImplemented()
    }

    open suspend fun contains(context: Context, other: Obj): Boolean {
        context.raiseNotImplemented()
    }

    open val asStr: ObjString by lazy {
        if (this is ObjString) this else ObjString(this.toString())
    }

    /**
     * Class of the object: definition of member functions (top-level), etc.
     * Note that using lazy allows to avoid endless recursion here
     */
    open val objClass: ObjClass by lazy {
        ObjClass("Obj").apply {
            addFn("toString") {
                thisObj.asStr
            }
            addFn("contains") {
                ObjBool(thisObj.contains(this, args.firstAndOnly()))
            }
        }
    }

    open suspend fun plus(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun minus(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun mul(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun div(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun mod(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun logicalNot(context: Context): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun logicalAnd(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun logicalOr(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun assign(context: Context, other: Obj): Obj? = null

    /**
     * a += b
     * if( the operation is not defined, it returns null and the compiler would try
     * to generate it as 'this = this + other', reassigning its variable
     */
    open suspend fun plusAssign(context: Context, other: Obj): Obj? = null

    /**
     * `-=` operations, see [plusAssign]
     */
    open suspend fun minusAssign(context: Context, other: Obj): Obj? = null
    open suspend fun mulAssign(context: Context, other: Obj): Obj? = null
    open suspend fun divAssign(context: Context, other: Obj): Obj? = null
    open suspend fun modAssign(context: Context, other: Obj): Obj? = null

    open suspend fun getAndIncrement(context: Context): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun incrementAndGet(context: Context): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun decrementAndGet(context: Context): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun getAndDecrement(context: Context): Obj {
        context.raiseNotImplemented()
    }

    fun willMutate(context: Context) {
        if (isFrozen) context.raiseError("attempt to mutate frozen object")
    }

    suspend fun <T> sync(block: () -> T): T = monitor.withLock { block() }

    suspend fun readField(context: Context, name: String): WithAccess<Obj> {
        // could be property or class field:
        val obj = objClass.getInstanceMemberOrNull(name)
        val value = obj?.value
        return when (value) {
            is Statement -> {
                // readonly property, important: call it on this
                value.execute(context.copy(context.pos, newThisObj = this)).asReadonly
            }
            // could be writable property naturally
            else -> getInstanceMember(context.pos, name)
        }
    }

    fun writeField(context: Context, name: String, newValue: Obj) {
        willMutate(context)
        members[name]?.let { if (it.isMutable) it.value = newValue }
            ?: context.raiseError("Can't reassign member: $name")
    }

    open suspend fun getAt(context: Context, index: Int): Obj {
        context.raiseNotImplemented("indexing")
    }

    open suspend fun putAt(context: Context, index: Int, newValue: Obj) {
        context.raiseNotImplemented("indexing")
    }

    fun createField(name: String, initialValue: Obj, isMutable: Boolean = false, pos: Pos = Pos.builtIn) {
        if (name in members || parentInstances.any { name in it.members })
            throw ScriptError(pos, "$name is already defined in $objClass or one of its supertypes")
        members[name] = WithAccess(initialValue, isMutable)
    }

    fun addFn(name: String, isOpen: Boolean = false, code: suspend Context.()->Obj) {
        createField(name, statement { code() }, isOpen)
    }

    fun addConst(name: String, value: Obj) = createField(name, value, isMutable = false)

    open suspend fun callOn(context: Context): Obj {
        context.raiseNotImplemented()
    }

    suspend fun invoke(context: Context, thisObj: Obj, args: Arguments): Obj =
        callOn(context.copy(context.pos, args = args, newThisObj = thisObj))

    suspend fun invoke(context: Context, atPos: Pos, thisObj: Obj, args: Arguments): Obj =
        callOn(context.copy(atPos, args = args, newThisObj = thisObj))



    val asReadonly: WithAccess<Obj> by lazy { WithAccess(this, false) }
    val asMutable: WithAccess<Obj> by lazy { WithAccess(this, true) }


    companion object {
        inline fun <reified T> from(obj: T): Obj {
            return when (obj) {
                is Obj -> obj
                is Double -> ObjReal(obj)
                is Float -> ObjReal(obj.toDouble())
                is Int -> ObjInt(obj.toLong())
                is Long -> ObjInt(obj)
                is String -> ObjString(obj)
                is CharSequence -> ObjString(obj.toString())
                is Boolean -> ObjBool(obj)
                Unit -> ObjVoid
                null -> ObjNull
                else -> throw IllegalArgumentException("cannot convert to Obj: $obj")
            }
        }
    }
}

@Suppress("unused")
inline fun <reified T> T.toObj(): Obj = Obj.from(this)

@Serializable
@SerialName("void")
object ObjVoid : Obj() {
    override fun equals(other: Any?): Boolean {
        return other is ObjVoid || other is Unit
    }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        return if (other === this) 0 else -1
    }

    override fun toString(): String = "void"
}

@Serializable
@SerialName("null")
object ObjNull : Obj() {
    override suspend fun compareTo(context: Context, other: Obj): Int {
        return if (other === this) 0 else -1
    }

    override fun equals(other: Any?): Boolean {
        return other is ObjNull || other == null
    }
}

interface Numeric {
    val longValue: Long
    val doubleValue: Double
    val toObjInt: ObjInt
    val toObjReal: ObjReal
}

fun Obj.toDouble(): Double =
    (this as? Numeric)?.doubleValue
        ?: (this as? ObjString)?.value?.toDouble()
        ?: throw IllegalArgumentException("cannot convert to double $this")

@Suppress("unused")
fun Obj.toLong(): Long =
    (this as? Numeric)?.longValue
        ?: (this as? ObjString)?.value?.toLong()
        ?: throw IllegalArgumentException("cannot convert to double $this")

fun Obj.toInt(): Int = toLong().toInt()

fun Obj.toBool(): Boolean =
    (this as? ObjBool)?.value ?: throw IllegalArgumentException("cannot convert to boolean $this")


class ObjRange(val start: Obj?, val end: Obj?,val inclusiveEnd: Boolean) : Obj() {

    override val objClass: ObjClass = type

    override fun toString(): String {
        val result = StringBuilder()
        result.append("${start ?: '∞'} ..")
        if( !inclusiveEnd) result.append('<')
        result.append(" ${end ?: '∞'}")
        return result.toString()
    }

    suspend fun containsRange(context: Context, other: ObjRange): Boolean {
        if( start != null ) {
            // our start is not -∞ so other start should be GTE or is not contained:
            if( other.start != null && start.compareTo(context, other.start) > 0) return false
        }
        if( end != null ) {
            // same with the end: if it is open, it can't be contained in ours:
            if( other.end == null ) return false
            // both exists, now there could be 4 cases:
            return when {
                other.inclusiveEnd && inclusiveEnd ->
                    end.compareTo(context, other.end) >= 0
                !other.inclusiveEnd && !inclusiveEnd ->
                    end.compareTo(context, other.end) >= 0
                other.inclusiveEnd && !inclusiveEnd ->
                    end.compareTo(context, other.end) > 0
                !other.inclusiveEnd && inclusiveEnd ->
                    end.compareTo(context, other.end) >= 0
                else -> throw IllegalStateException("unknown comparison")
            }
        }
        return true
    }

    override suspend fun contains(context: Context, other: Obj): Boolean {

        if( other is ObjRange)
            return containsRange(context, other)

        if (start == null && end == null) return true
        if (start != null) {
            if (start.compareTo(context, other) > 0) return false
        }
        if (end != null) {
            val cmp = end.compareTo(context, other)
            if (inclusiveEnd && cmp < 0 || !inclusiveEnd && cmp <= 0) return false
        }
        return true
    }

    val isIntRange: Boolean by lazy {
        start is ObjInt && end is ObjInt
    }

    companion object {
        val type = ObjClass("Range").apply {
            addFn("start" ) {
                thisAs<ObjRange>().start ?: ObjNull
            }
            addFn("end") {
                thisAs<ObjRange>().end ?: ObjNull
            }
            addFn("isOpen") {
                thisAs<ObjRange>().let { it.start == null || it.end == null }.toObj()
            }
            addFn("isIntRange") {
                thisAs<ObjRange>().isIntRange.toObj()
            }
            addFn("inclusiveEnd") {
                thisAs<ObjRange>().inclusiveEnd.toObj()
            }
        }
    }
}

data class ObjNamespace(val name: String) : Obj() {
    override fun toString(): String {
        return "namespace ${name}"
    }
}

open class ObjError(val context: Context, val message: String) : Obj() {
    override val asStr: ObjString by lazy { ObjString("Error: $message") }
}

class ObjNullPointerError(context: Context) : ObjError(context, "object is null")

class ObjAssertionError(context: Context, message: String) : ObjError(context, message)
class ObjClassCastError(context: Context, message: String) : ObjError(context, message)
