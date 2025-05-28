package net.sergeych.ling

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.roundToLong

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

    suspend fun callInstanceMethod(context: Context, name: String, args: Arguments): Obj =
    // instance _methods_ are our ObjClass instance:
    // note that getInstanceMember traverses the hierarchy
    // instance _methods_ are our ObjClass instance:
    // note that getInstanceMember traverses the hierarchy
// instance _methods_ are our ObjClass instance:
        // note that getInstanceMember traverses the hierarchy
        objClass.getInstanceMember(context.pos, name).value.invoke(context, this, args)

    // methods that to override

    open suspend fun compareTo(context: Context, other: Obj): Int {
        context.raiseNotImplemented()
    }

    open val asStr: ObjString by lazy {
        if (this is ObjString) this else ObjString(this.toString())
    }

    /**
     * Class of the object: definition of member functions (top-level), etc.
     * Note that using lazy allows to avoid endless recursion here
     */
    open val objClass: ObjClass by lazy { ObjClass("Obj") }

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

    fun readField(context: Context, name: String): WithAccess<Obj> = getInstanceMember(context.pos, name)

    fun writeField(context: Context, name: String, newValue: Obj) {
        willMutate(context)
        members[name]?.let { if (it.isMutable) it.value = newValue }
            ?: context.raiseError("Can't reassign member: $name")
    }

    fun createField(name: String, initialValue: Obj, isMutable: Boolean = false, pos: Pos = Pos.builtIn) {
        if (name in members || parentInstances.any { name in it.members })
            throw ScriptError(pos, "$name is already defined in $objClass or one of its supertypes")
        members[name] = WithAccess(initialValue, isMutable)
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

@Serializable
@SerialName("string")
data class ObjString(val value: String) : Obj() {

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is ObjString) context.raiseError("cannot compare string with $other")
        return this.value.compareTo(other.value)
    }

    override fun toString(): String = value

    override val objClass: ObjClass
        get() = type

    override suspend fun plus(context: Context, other: Obj): Obj {
        return ObjString(value + other.asStr.value)
    }

    companion object {
        val type = ObjClass("String")
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


data class ObjReal(val value: Double) : Obj(), Numeric {
    override val asStr by lazy { ObjString(value.toString()) }
    override val longValue: Long by lazy { floor(value).toLong() }
    override val doubleValue: Double by lazy { value }
    override val toObjInt: ObjInt by lazy { ObjInt(longValue) }
    override val toObjReal: ObjReal by lazy { ObjReal(value) }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is Numeric) context.raiseError("cannot compare $this with $other")
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String = value.toString()

    override val objClass: ObjClass = type

    override suspend fun plus(context: Context, other: Obj): Obj =
        ObjReal(this.value + other.toDouble())

    override suspend fun minus(context: Context, other: Obj): Obj =
        ObjReal(this.value - other.toDouble())

    override suspend fun mul(context: Context, other: Obj): Obj =
        ObjReal(this.value * other.toDouble())

    override suspend fun div(context: Context, other: Obj): Obj =
        ObjReal(this.value / other.toDouble())

    override suspend fun mod(context: Context, other: Obj): Obj =
        ObjReal(this.value % other.toDouble())

    companion object {
        val type: ObjClass = ObjClass("Real").apply {
            createField(
                "roundToInt",
                statement(Pos.builtIn) {
                    (it.thisObj as ObjReal).value.roundToLong().toObj()
                },
            )
        }
    }
}

data class ObjInt(var value: Long) : Obj(), Numeric {
    override val asStr get() = ObjString(value.toString())
    override val longValue get() = value
    override val doubleValue get() = value.toDouble()
    override val toObjInt get() = this
    override val toObjReal = ObjReal(doubleValue)

    override suspend fun getAndIncrement(context: Context): Obj {
        return ObjInt(value).also { value++ }
    }

    override suspend fun getAndDecrement(context: Context): Obj {
        return ObjInt(value).also { value-- }
    }

    override suspend fun incrementAndGet(context: Context): Obj {
        return ObjInt(++value)
    }

    override suspend fun decrementAndGet(context: Context): Obj {
        return ObjInt(--value)
    }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is Numeric) context.raiseError("cannot compare $this with $other")
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String = value.toString()

    override val objClass: ObjClass = type

    override suspend fun plus(context: Context, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value + other.value)
        else
            ObjReal(this.doubleValue + other.toDouble())

    override suspend fun minus(context: Context, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value - other.value)
        else
            ObjReal(this.doubleValue - other.toDouble())

    override suspend fun mul(context: Context, other: Obj): Obj =
        if (other is ObjInt) {
            ObjInt(this.value * other.value)
        } else ObjReal(this.value * other.toDouble())

    override suspend fun div(context: Context, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value / other.value)
        else ObjReal(this.value / other.toDouble())

    override suspend fun mod(context: Context, other: Obj): Obj =
        if (other is ObjInt)
            ObjInt(this.value % other.value)
        else ObjReal(this.value.toDouble() % other.toDouble())

    companion object {
        val type = ObjClass("Int")
    }
}

data class ObjBool(val value: Boolean) : Obj() {
    override val asStr by lazy { ObjString(value.toString()) }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is ObjBool) context.raiseError("cannot compare $this with $other")
        return value.compareTo(other.value)
    }

    override fun toString(): String = value.toString()

    override val objClass: ObjClass = type

    override suspend fun logicalNot(context: Context): Obj = ObjBool(!value)

    override suspend fun logicalAnd(context: Context, other: Obj): Obj = ObjBool(value && other.toBool())

    override suspend fun logicalOr(context: Context, other: Obj): Obj = ObjBool(value || other.toBool())

    companion object {
        val type = ObjClass("Bool")
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
